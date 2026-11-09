package dev.deveps.triage.api.investigacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.deveps.triage.api.agente.Agente;
import dev.deveps.triage.api.agente.Corrector;
import dev.deveps.triage.api.agente.Herramientas;
import dev.deveps.triage.api.investigacion.ServicioInvestigacion.Creada;
import dev.deveps.triage.api.investigacion.ServicioInvestigacion.Tiempos;
import dev.deveps.triage.api.modelo.DefinicionFuncion;
import dev.deveps.triage.api.modelo.LlamadaFuncion;
import dev.deveps.triage.api.modelo.PeticionModelo;
import dev.deveps.triage.api.modelo.ProveedorException;
import dev.deveps.triage.api.modelo.ProveedorModelo;
import dev.deveps.triage.api.modelo.RespuestaModelo;
import dev.deveps.triage.api.topes.BaseDatosPrueba;
import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.api.topes.HuecoUnico;
import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import dev.deveps.triage.comun.GestorException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class ServicioInvestigacionTest {

    private static final Duration MUCHO = Duration.ofMinutes(1);
    private static final String BD_PARADA = """
            {"veredicto":"averia","causa":"bd_parada","pruebas":[{"id_llamada":"c1","cita":"db: estado=exited"}],"descartado":"","remedio":"arrancar_bd","explicacion":"x"}""";

    private final List<String> ordenes = Collections.synchronizedList(new ArrayList<>());
    private volatile String respuestaVerificar = "true responde";
    private volatile String salidaHerramienta = "db: estado=exited";
    private final Queue<Object> guion = new ConcurrentLinkedQueue<>();
    private final List<PeticionModelo> peticiones = Collections.synchronizedList(new ArrayList<>());

    private final ProveedorModelo modelo = peticion -> {
        peticiones.add(peticion);
        Object siguiente = guion.poll();
        if (siguiente instanceof Duration espera) {
            try {
                Thread.sleep(espera);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProveedorException("interrumpida", false);
            }
            siguiente = guion.poll();
        }
        return (RespuestaModelo) siguiente;
    };

    private final ServicioInvestigacion.Gestor gestor = (op, args) -> {
        ordenes.add(op + (args.length > 0 && !op.equals("crear") ? " " + String.join(" ", args) : ""));
        return switch (op) {
            case "crear" -> "ent1 tok1";
            case "verificar" -> respuestaVerificar;
            default -> "";
        };
    };

    private final Herramientas herramientas = new Herramientas() {
        @Override
        public List<DefinicionFuncion> definiciones() {
            return List.of(new DefinicionFuncion("listar_servicios", "estado", Map.of("type", "object", "properties", Map.of())));
        }

        @Override
        public String llamar(String nombre, Map<String, Object> args) {
            return salidaHerramienta;
        }
    };

    private final ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor();
    private final JdbcClient db = BaseDatosPrueba.conPrecio();
    private final ControlGasto gasto = new ControlGasto(db, Clock.systemUTC(), BaseDatosPrueba.VERSION, new BigDecimal("1.00"));
    private final HuecoUnico hueco = new HuecoUnico();
    private final RepositorioInvestigacion repositorio = new RepositorioInvestigacion(db);

    @AfterEach
    void parar() {
        programador.shutdownNow();
    }

    private ServicioInvestigacion servicio(Tiempos tiempos) {
        return new ServicioInvestigacion(gestor, token -> herramientas, new Agente(modelo, BaseDatosPrueba.VERSION), new Corrector(), gasto, hueco,
                repositorio, programador, Clock.systemUTC(), tiempos, variantes -> 1);
    }

    private ServicioInvestigacion normal() {
        return servicio(new Tiempos(MUCHO, MUCHO, MUCHO, Duration.ofMillis(20)));
    }

    private static RespuestaModelo llamar(String nombre, String argumentos) {
        return new RespuestaModelo(Optional.of(new LlamadaFuncion("x", nombre, argumentos)), "", 100, 20, BaseDatosPrueba.VERSION);
    }

    private void guionDiagnostico(String diagnostico) {
        guion.add(llamar("listar_servicios", "{\"encontrado\":\"miro\"}"));
        guion.add(llamar("emitir_diagnostico", diagnostico));
    }

    private Creada iniciar(ServicioInvestigacion s, Caso caso) {
        hueco.adquirir();
        return s.iniciar(caso);
    }

    private static List<CanalEventos.Evento> eventos(ServicioInvestigacion s, Creada c) {
        return s.canal(c.id(), c.tokenVer()).orElseThrow().eventos();
    }

    private static List<String> tipos(ServicioInvestigacion s, Creada c) {
        return eventos(s, c).stream().map(CanalEventos.Evento::tipo).toList();
    }

    private static List<Object> estados(ServicioInvestigacion s, Creada c) {
        return eventos(s, c).stream().filter(e -> e.tipo().equals("estado")).map(e -> e.datos().get("estado")).toList();
    }

    private static CanalEventos.Evento evento(ServicioInvestigacion s, Creada c, String tipo) {
        return eventos(s, c).stream().filter(e -> e.tipo().equals(tipo)).findFirst().orElseThrow();
    }

    private void esperarEstado(ServicioInvestigacion s, Creada c, String estado) {
        await().atMost(Duration.ofSeconds(5)).until(() -> estados(s, c).contains(estado));
    }

    private void esperarFin() {
        await().atMost(Duration.ofSeconds(5)).until(hueco::libre);
    }

    @Test
    void recorre_los_estados_hasta_destruida() {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, c, "PROPUESTA");

        assertThat(s.aprobar(c.id(), c.tokenAprobar())).isTrue();
        esperarFin();

        assertThat(estados(s, c)).containsExactly("PREPARANDO", "PROVOCANDO", "INVESTIGANDO", "PROPUESTA", "APROBADA", "APLICANDO", "VERIFICANDO");
        assertThat(evento(s, c, "fin").datos()).containsEntry("estado", "DESTRUIDA").containsEntry("motivo", "remedio_verificado");
        assertThat(ordenes).containsExactly("crear", "provocar ent1", "remediar ent1 ARRANCAR_BD", "verificar ent1", "destruir ent1");
        assertThat(evento(s, c, "remedio_resultado").datos()).containsEntry("verificado", true);
        assertThat(repositorio.estadoDe(c.id())).contains(EstadoInvestigacion.DESTRUIDA);
    }

    @Test
    void rechazar_destruye_sin_remediar() {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, c, "PROPUESTA");

        assertThat(s.rechazar(c.id(), c.tokenAprobar())).isTrue();
        esperarFin();

        assertThat(estados(s, c)).contains("RECHAZADA");
        assertThat(ordenes).noneMatch(o -> o.startsWith("remediar")).contains("destruir ent1");
    }

    @Test
    void falsa_alarma_no_ofrece_aprobar() {
        ServicioInvestigacion s = normal();
        salidaHerramienta = "codigo=200";
        guionDiagnostico("""
                {"veredicto":"falsa_alarma","causa":"comprobacion_desfasada","pruebas":[{"id_llamada":"c1","cita":"codigo=200"}],"descartado":"","remedio":"ninguno","explicacion":"x"}""");
        Creada c = iniciar(s, Caso.F1_FALSA_ALARMA);
        esperarFin();

        assertThat(estados(s, c)).doesNotContain("PROPUESTA");
        assertThat(evento(s, c, "diagnostico").datos()).containsEntry("aprobable", false);
        assertThat(s.aprobar(c.id(), c.tokenAprobar())).isFalse();
    }

    @Test
    void recrear_bd_no_ofrece_aprobar() {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA.replace("arrancar_bd", "recrear_bd"));
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarFin();

        assertThat(estados(s, c)).doesNotContain("PROPUESTA");
        assertThat(ordenes).noneMatch(o -> o.startsWith("remediar"));
    }

    @Test
    void la_verificacion_fallida_se_emite_y_no_se_oculta() {
        ServicioInvestigacion s = normal();
        respuestaVerificar = "false curl: (22) 503";
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, c, "PROPUESTA");
        s.aprobar(c.id(), c.tokenAprobar());
        esperarFin();

        assertThat(evento(s, c, "remedio_resultado").datos()).containsEntry("verificado", false).containsEntry("detalle", "curl: (22) 503");
        assertThat(evento(s, c, "fin").datos()).containsEntry("motivo", "remedio_no_verificado");
    }

    @Test
    void agotada_al_pasar_el_tiempo_maximo_destruye() {
        ServicioInvestigacion s = servicio(new Tiempos(MUCHO, Duration.ofMillis(150), MUCHO, Duration.ofMillis(20)));
        guion.add(Duration.ofSeconds(30));
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarFin();

        assertThat(evento(s, c, "fin").datos()).containsEntry("estado", "AGOTADA").containsEntry("motivo", "tiempo");
        assertThat(ordenes).contains("destruir ent1");
    }

    @Test
    void sin_publico_se_corta_y_destruye() {
        ServicioInvestigacion s = servicio(new Tiempos(MUCHO, MUCHO, Duration.ofMillis(200), Duration.ofMillis(20)));
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarFin();

        assertThat(evento(s, c, "fin").datos()).containsEntry("estado", "AGOTADA").containsEntry("motivo", "desconexion");
        assertThat(ordenes).noneMatch(o -> o.startsWith("remediar")).contains("destruir ent1");
    }

    @Test
    void la_trampa_se_emite_a_la_web_y_el_modelo_solo_ve_el_log() {
        ServicioInvestigacion s = normal();
        String trampa = Catalogo.ficha(Caso.A2_DISCO_LLENO).lineaTrampa().orElseThrow();
        salidaHerramienta = "GET /api/pedidos 500 ua=\"" + trampa + "\"";
        guionDiagnostico(BD_PARADA.replace("arrancar_bd", "recrear_bd"));
        Creada c = iniciar(s, Caso.A2_DISCO_LLENO);
        esperarFin();

        assertThat(evento(s, c, "trampa").datos()).containsEntry("id", "c1").containsEntry("linea", trampa);
        String primeraPeticion = peticiones.getFirst().mensajes().toString();
        assertThat(primeraPeticion).doesNotContain(trampa);
    }

    @Test
    void doble_aprobar_a_la_vez_remedia_una_sola_vez() throws Exception {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, c, "PROPUESTA");

        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 8; i++) {
                resultados.add(hilos.submit(() -> {
                    salida.await();
                    return s.aprobar(c.id(), c.tokenAprobar());
                }));
            }
            salida.countDown();
        }
        esperarFin();

        long aceptadas = 0;
        for (Future<Boolean> r : resultados) {
            aceptadas += r.get() ? 1 : 0;
        }
        assertThat(aceptadas).isEqualTo(1);
        assertThat(ordenes.stream().filter(o -> o.startsWith("remediar"))).hasSize(1);
    }

    @Test
    void aprobar_fuera_de_plazo_no_se_acepta() {
        ServicioInvestigacion s = servicio(new Tiempos(Duration.ofMillis(100), MUCHO, MUCHO, Duration.ofMillis(20)));
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarFin();

        assertThat(s.aprobar(c.id(), c.tokenAprobar())).isFalse();
        assertThat(evento(s, c, "fin").datos()).containsEntry("motivo", "plazo");
        assertThat(ordenes).noneMatch(o -> o.startsWith("remediar"));
    }

    @Test
    void el_token_de_otra_investigacion_o_el_de_ver_no_sirven() {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, c, "PROPUESTA");

        assertThat(s.aprobar(c.id(), "0".repeat(32))).isFalse();
        assertThat(s.aprobar(c.id(), c.tokenVer())).isFalse();
        assertThat(s.aprobar(c.id(), null)).isFalse();
        assertThat(s.canal(c.id(), c.tokenAprobar())).isEmpty();

        s.rechazar(c.id(), c.tokenAprobar());
        esperarFin();
    }

    @Test
    void si_falla_el_gestor_queda_fallida_y_libera_el_hueco() {
        ServicioInvestigacion.Gestor roto = (op, args) -> {
            throw new GestorException("docker");
        };
        ServicioInvestigacion s = new ServicioInvestigacion(roto, token -> herramientas, new Agente(modelo, BaseDatosPrueba.VERSION), new Corrector(), gasto, hueco,
                repositorio, programador, Clock.systemUTC(), new Tiempos(MUCHO, MUCHO, MUCHO, Duration.ofMillis(20)), v -> 1);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarFin();

        assertThat(evento(s, c, "fin").datos()).containsEntry("estado", "FALLIDA").containsEntry("motivo", "interno");
    }

    @Test
    void el_reinicio_deja_fallida_destruye_y_limpia_reservas() {
        repositorio.crear("a".repeat(32), Caso.A1_BD_CAIDA, 1, java.time.Instant.now());
        repositorio.entorno("a".repeat(32), "ent9");
        gasto.presupuesto("x").reservar(1000);

        new Recuperacion(repositorio, gestor, gasto, Clock.systemUTC()).recuperar();

        assertThat(repositorio.estadoDe("a".repeat(32))).contains(EstadoInvestigacion.FALLIDA);
        assertThat(ordenes).containsExactly("destruir ent9");
        assertThat(repositorio.activas()).isEmpty();
    }

    @Test
    void el_contador_publico_suma_segun_el_corrector() {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA);
        Creada buena = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, buena, "PROPUESTA");
        s.rechazar(buena.id(), buena.tokenAprobar());
        esperarFin();

        guionDiagnostico(BD_PARADA.replace("bd_parada", "app_caida"));
        Creada mala = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, mala, "PROPUESTA");
        s.rechazar(mala.id(), mala.tokenAprobar());
        esperarFin();

        assertThat(repositorio.contador()).isEqualTo(new RepositorioInvestigacion.Contador(2, 1));
    }

    @Test
    void el_consumo_se_emite_acumulado_con_coste() {
        ServicioInvestigacion s = normal();
        guionDiagnostico(BD_PARADA);
        Creada c = iniciar(s, Caso.A1_BD_CAIDA);
        esperarEstado(s, c, "PROPUESTA");

        CanalEventos.Evento ultimo = eventos(s, c).stream().filter(e -> e.tipo().equals("consumo")).reduce((a, b) -> b).orElseThrow();
        assertThat(ultimo.datos()).containsEntry("tokensEntrada", 200).containsEntry("tokensSalida", 40).containsEntry("moneda", "EUR");

        s.rechazar(c.id(), c.tokenAprobar());
        esperarFin();
    }
}
