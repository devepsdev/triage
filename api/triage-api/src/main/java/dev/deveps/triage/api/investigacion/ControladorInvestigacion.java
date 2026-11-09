package dev.deveps.triage.api.investigacion;

import dev.deveps.triage.api.topes.Admision;
import dev.deveps.triage.api.topes.Rechazo;
import dev.deveps.triage.comun.Caso;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api")
public class ControladorInvestigacion {

    public record PeticionNueva(String caso, String captcha) {}

    public record PeticionDecision(String token) {}

    /** Algo más que la duración máxima: quien cierra la conexión es el fin de la investigación. */
    private static final long VIDA_EMISOR_MS = 7 * 60 * 1000L;

    private final Admision admision;
    private final ServicioInvestigacion servicio;
    private final RepositorioInvestigacion repositorio;
    private final String sitekey;

    public ControladorInvestigacion(Admision admision, ServicioInvestigacion servicio, RepositorioInvestigacion repositorio, String sitekey) {
        this.admision = admision;
        this.servicio = servicio;
        this.repositorio = repositorio;
        this.sitekey = sitekey;
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        Optional<Rechazo> motivo = admision.motivoNoDisponible();
        RepositorioInvestigacion.Contador contador = repositorio.contador();
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("disponible", motivo.isEmpty());
        respuesta.put("motivo", motivo.map(Enum::name).orElse(null));
        respuesta.put("sitekey", sitekey);
        respuesta.put("casos", ServicioInvestigacion.casos());
        respuesta.put("contador", Map.of("total", contador.total(), "aciertos", contador.aciertos()));
        return respuesta;
    }

    @PostMapping("/investigaciones")
    public ResponseEntity<?> crear(@RequestBody PeticionNueva peticion, HttpServletRequest http) {
        Optional<Rechazo> rechazo = admision.comprobar(peticion.caso(), peticion.captcha(), direccion(http));
        if (rechazo.isPresent()) {
            return ResponseEntity.status(codigo(rechazo.get())).body(Map.of("motivo", rechazo.get().name()));
        }
        return ResponseEntity.accepted().body(servicio.iniciar(Caso.valueOf(peticion.caso())));
    }

    /** nginx escribe X-Real-IP y la API solo escucha en 127.0.0.1: el visitante no puede falsificarla. */
    private static String direccion(HttpServletRequest http) {
        String real = http.getHeader("X-Real-IP");
        return real != null && !real.isBlank() ? real : http.getRemoteAddr();
    }

    private static HttpStatus codigo(Rechazo rechazo) {
        return switch (rechazo) {
            case OCUPADO -> HttpStatus.CONFLICT;
            case LIMITE_IP, LIMITE_DIARIO -> HttpStatus.TOO_MANY_REQUESTS;
            case GASTO, NO_DISPONIBLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case CAPTCHA -> HttpStatus.FORBIDDEN;
            case CASO_INVALIDO -> HttpStatus.BAD_REQUEST;
        };
    }

    @GetMapping(path = "/investigaciones/{id}/eventos", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> eventos(@PathVariable String id, @RequestParam String token,
            @RequestHeader(name = "Last-Event-ID", required = false) String ultimo) {
        Optional<CanalEventos> canal = servicio.canal(id, token);
        if (canal.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emisor = new SseEmitter(VIDA_EMISOR_MS);
        long desde = desde(ultimo);
        Thread.startVirtualThread(() -> {
            canal.get().seguir(desde, new CanalEventos.Emisor() {
                @Override
                public void evento(CanalEventos.Evento e) throws IOException {
                    emisor.send(SseEmitter.event().id(String.valueOf(e.id())).name(e.tipo()).data(e.datos(), MediaType.APPLICATION_JSON));
                }

                @Override
                public void latido() throws IOException {
                    emisor.send(SseEmitter.event().comment("latido"));
                }
            });
            emisor.complete();
        });
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").body(emisor);
    }

    private static long desde(String ultimo) {
        try {
            return ultimo == null ? 0 : Long.parseLong(ultimo.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @PostMapping("/investigaciones/{id}/aprobar")
    public ResponseEntity<Void> aprobar(@PathVariable String id, @RequestBody PeticionDecision peticion) {
        return respuesta(servicio.aprobar(id, peticion.token()));
    }

    @PostMapping("/investigaciones/{id}/rechazar")
    public ResponseEntity<Void> rechazar(@PathVariable String id, @RequestBody PeticionDecision peticion) {
        return respuesta(servicio.rechazar(id, peticion.token()));
    }

    private static ResponseEntity<Void> respuesta(boolean aceptada) {
        return aceptada ? ResponseEntity.accepted().build() : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
