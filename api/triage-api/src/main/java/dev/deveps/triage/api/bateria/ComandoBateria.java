package dev.deveps.triage.api.bateria;

import dev.deveps.triage.api.agente.Agente;
import dev.deveps.triage.api.agente.Corrector;
import dev.deveps.triage.api.agente.Presupuesto;
import dev.deveps.triage.api.investigacion.CanalEventos;
import dev.deveps.triage.api.investigacion.RepositorioInvestigacion;
import dev.deveps.triage.api.investigacion.ServicioInvestigacion;
import dev.deveps.triage.api.modelo.ProveedorModelo;
import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.api.topes.HuecoUnico;
import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * La batería: se lanza a mano y nunca de forma programada. Pasa por el mismo servicio que una visita, para que lo medido
 * sea lo que se publica, pero rechaza todas las propuestas y no suma en el contador público ni en el gasto diario.
 */
public final class ComandoBateria implements ApplicationRunner {

    public record Parametros(String modelo, int repeticiones, BigDecimal confirmoCoste) {

        private static final int MAX_REPETICIONES = 20;

        /** Sin un importe confirmado a mano no se arranca: es lo que impide gastar sin querer. */
        public static Parametros de(List<String> argumentos) {
            String modelo = valor(argumentos, "--modelo=");
            int repeticiones = Integer.parseInt(valor(argumentos, "--repeticiones="));
            BigDecimal coste = new BigDecimal(valor(argumentos, "--confirmo-coste="));
            if (repeticiones < 1 || repeticiones > MAX_REPETICIONES) {
                throw new IllegalArgumentException("--repeticiones debe estar entre 1 y " + MAX_REPETICIONES);
            }
            if (coste.signum() <= 0) {
                throw new IllegalArgumentException("--confirmo-coste debe ser mayor que 0");
            }
            return new Parametros(modelo, repeticiones, coste);
        }

        private static String valor(List<String> argumentos, String opcion) {
            return argumentos.stream().filter(a -> a.startsWith(opcion)).map(a -> a.substring(opcion.length())).filter(v -> !v.isBlank()).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Falta " + opcion + "<valor>"));
        }
    }

    public record Dependencias(ProveedorModelo proveedor, ServicioInvestigacion.Gestor gestor, ServicioInvestigacion.FabricaHerramientas fabrica, JdbcClient db,
            HuecoUnico hueco, ScheduledExecutorService programador, Clock reloj, boolean hayClaveModelo, Path raiz) {}

    /** Lo gastado en toda la batería. Se reserva el peor caso antes de cada llamada, igual que con las visitas. */
    private static final class Gasto {
        private final ControlGasto precios;
        private final String modelo;
        private final BigDecimal tope;
        private BigDecimal acumulado = BigDecimal.ZERO;
        private boolean agotado;

        Gasto(ControlGasto precios, String modelo, BigDecimal tope) {
            this.precios = precios;
            this.modelo = modelo;
            this.tope = tope;
        }

        synchronized boolean agotado() {
            return agotado;
        }

        synchronized BigDecimal acumulado() {
            return acumulado;
        }

        synchronized boolean cabe(int tokensMax) {
            BigDecimal peor = precios.coste(tokensMax, 0, modelo).max(precios.coste(0, tokensMax, modelo));
            if (acumulado.add(peor).compareTo(tope) > 0) {
                agotado = true;
                return false;
            }
            return true;
        }

        synchronized void sumar(int entrada, int salida, String version) {
            acumulado = acumulado.add(precios.coste(entrada, salida, version));
        }

        Presupuesto presupuesto() {
            return new Presupuesto() {
                @Override
                public boolean reservar(int tokensMax) {
                    return cabe(tokensMax);
                }

                @Override
                public void liquidar(int entrada, int salida, String version) {
                    sumar(entrada, salida, version);
                }
            };
        }
    }

    private static final ServicioInvestigacion.Tiempos TIEMPOS = new ServicioInvestigacion.Tiempos(Duration.ofSeconds(90), Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(5));
    private static final DateTimeFormatter FORMATO_NOMBRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HHmm").withZone(ZoneId.of("Europe/Madrid"));
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Dependencias d;

    public ComandoBateria(Dependencias dependencias) {
        this.d = dependencias;
    }

    @Override
    public void run(ApplicationArguments argumentos) throws IOException {
        if (!argumentos.containsOption("bateria")) {
            return;
        }
        Parametros parametros = Parametros.de(List.of(argumentos.getSourceArgs()));
        if (!d.hayClaveModelo()) {
            throw new IllegalStateException("Sin clave del modelo no se puede lanzar la batería");
        }
        InformeBateria informe = ejecutar(parametros);
        System.out.println("Batería terminada. Cambiar de modelo: " + informe.cambiar() + " " + informe.motivosParaCambiar());
    }

    public InformeBateria ejecutar(Parametros p) throws IOException {
        if (!d.hueco().adquirir()) {
            throw new IllegalStateException("El hueco está ocupado: hay una investigación en marcha");
        }
        try {
            return recorrer(p);
        } finally {
            d.hueco().liberar();
        }
    }

    private InformeBateria recorrer(Parametros p) throws IOException {
        ControlGasto precios = new ControlGasto(d.db(), d.reloj(), p.modelo(), BigDecimal.ZERO);
        Gasto gasto = new Gasto(precios, p.modelo(), p.confirmoCoste());
        // El hueco común ya es de la batería durante todo el recorrido; este solo cubre cada repetición.
        HuecoUnico porRepeticion = new HuecoUnico();
        ServicioInvestigacion servicio = new ServicioInvestigacion(d.gestor(), d.fabrica(), new Agente(d.proveedor(), p.modelo()), new Corrector(), precios, porRepeticion,
                RepositorioInvestigacion.sinGuardar(), d.programador(), d.reloj(), TIEMPOS, variantes -> 1, id -> gasto.presupuesto());
        String base = FORMATO_NOMBRE.format(d.reloj().instant()) + "-" + p.modelo();
        List<InformeBateria.Repeticion> resultados = new ArrayList<>();
        boolean completa = true;
        recorrido:
        for (Caso caso : Caso.values()) {
            int variantes = Catalogo.ficha(caso).variantes();
            for (int numero = 1; numero <= p.repeticiones(); numero++) {
                Optional<InformeBateria.Repeticion> r = repeticion(servicio, porRepeticion, gasto, p, base, caso, (numero - 1) % variantes + 1, numero);
                if (r.isEmpty()) {
                    completa = false;
                    break recorrido;
                }
                resultados.add(r.get());
            }
        }
        InformeBateria informe = new InformeBateria(d.reloj().instant().toString(), p.modelo(), huellaPrompt(), p.repeticiones(), completa, gasto.acumulado(), precios.moneda(), resultados);
        escribir(d.raiz().resolve("bateria/resultados").resolve(base + ".json"), informe.datos());
        return informe;
    }

    /** Vacío si se acabó lo confirmado: esa repetición no dice nada del agente y no se cuenta. */
    private Optional<InformeBateria.Repeticion> repeticion(ServicioInvestigacion servicio, HuecoUnico porRepeticion, Gasto gasto, Parametros p, String base,
            Caso caso, int variante, int numero) throws IOException {
        if (!porRepeticion.adquirir()) {
            throw new IllegalStateException("La repetición anterior no ha terminado");
        }
        ServicioInvestigacion.Creada creada = servicio.iniciar(caso, variante);
        List<Map<String, Object>> eventos = new ArrayList<>();
        long inicio = d.reloj().millis();
        servicio.canal(creada.id(), creada.tokenVer()).orElseThrow().seguir(0, new CanalEventos.Emisor() {
            @Override
            public void evento(CanalEventos.Evento e) {
                Map<String, Object> grabado = new LinkedHashMap<>();
                grabado.put("ms", d.reloj().millis() - inicio);
                grabado.put("tipo", e.tipo());
                grabado.put("datos", e.datos());
                eventos.add(grabado);
                if (e.tipo().equals("propuesta")) {
                    servicio.rechazar(creada.id(), creada.tokenAprobar());
                }
            }

            @Override
            public void latido() {
                // Aquí no hay nadie que pueda irse.
            }
        });
        if (gasto.agotado()) {
            return Optional.empty();
        }
        String id = "%s-%s-%02d".formatted(base, caso.name(), numero);
        Map<String, Object> grabacion = new LinkedHashMap<>();
        grabacion.put("id", id);
        grabacion.put("fecha", d.reloj().instant().toString());
        grabacion.put("modelo", p.modelo());
        grabacion.put("caso", caso.name());
        grabacion.put("variante", variante);
        grabacion.put("eventos", eventos);
        escribir(d.raiz().resolve(ExportadorGrabacion.ORIGEN).resolve(id + ".json"), grabacion);
        return Optional.of(resultado(caso, variante, numero, id, eventos));
    }

    private static InformeBateria.Repeticion resultado(Caso caso, int variante, int numero, String id, List<Map<String, Object>> eventos) {
        Optional<Map<?, ?>> correccion = datosDe(eventos, "correccion");
        if (correccion.isPresent()) {
            Map<?, ?> c = correccion.get();
            return new InformeBateria.Repeticion(caso, variante, numero, Boolean.TRUE.equals(c.get("acierto")), (String) c.get("tipoError"), Boolean.TRUE.equals(c.get("enganadoPorInyeccion")), id);
        }
        String motivo = datosDe(eventos, "fin").map(f -> String.valueOf(f.get("motivo"))).orElse("desconocido");
        return new InformeBateria.Repeticion(caso, variante, numero, false, "corte_" + motivo, false, id);
    }

    private static Optional<Map<?, ?>> datosDe(List<Map<String, Object>> eventos, String tipo) {
        return eventos.stream().filter(e -> tipo.equals(e.get("tipo"))).findFirst().map(e -> (Map<?, ?>) e.get("datos"));
    }

    private static void escribir(Path destino, Object datos) throws IOException {
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, JSON.writeValueAsString(datos));
    }

    private static String huellaPrompt() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Agente.promptSistema().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
