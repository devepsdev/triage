package dev.deveps.triage.api.investigacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import dev.deveps.triage.api.agente.Agente;
import dev.deveps.triage.api.agente.Corrector;
import dev.deveps.triage.api.agente.Herramientas;
import dev.deveps.triage.api.modelo.DefinicionFuncion;
import dev.deveps.triage.api.modelo.ProveedorException;
import dev.deveps.triage.api.topes.Admision;
import dev.deveps.triage.api.topes.BaseDatosPrueba;
import dev.deveps.triage.api.topes.ContadorDiario;
import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.api.topes.HuecoUnico;
import dev.deveps.triage.api.topes.LimitadorIp;
import dev.deveps.triage.api.topes.Turnstile;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

class ControladorInvestigacionTest {

    private static final String CUERPO = "{\"caso\":\"A1_BD_CAIDA\",\"captcha\":\"tok\"}";

    private final ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor();
    private final JdbcClient db = BaseDatosPrueba.conPrecio();
    private final ControlGasto gasto = new ControlGasto(db, Clock.systemUTC(), BaseDatosPrueba.VERSION, new BigDecimal("1.00"));
    private final HuecoUnico hueco = new HuecoUnico();
    private final CountDownLatch soltarModelo = new CountDownLatch(1);
    private HttpServer captcha;
    private MockMvc mvc;

    private final Herramientas herramientas = new Herramientas() {
        @Override
        public List<DefinicionFuncion> definiciones() {
            return List.of();
        }

        @Override
        public String llamar(String nombre, Map<String, Object> args) {
            return "";
        }
    };

    @BeforeEach
    void montar() throws IOException {
        captcha = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        captcha.createContext("/", intercambio -> {
            byte[] cuerpo = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        captcha.start();
        Turnstile turnstile = new Turnstile(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + captcha.getAddress().getPort() + "/"), "secreto");
        Admision admision = new Admision(turnstile, new LimitadorIp(Clock.systemUTC(), 3), new ContadorDiario(db, gasto, 30), gasto, hueco, true);
        RepositorioInvestigacion repositorio = new RepositorioInvestigacion(db);
        Agente agente = new Agente(peticion -> {
            try {
                soltarModelo.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new ProveedorException("fin de la prueba", false);
        }, BaseDatosPrueba.VERSION);
        ServicioInvestigacion.Tiempos tiempos = new ServicioInvestigacion.Tiempos(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMillis(20));
        ServicioInvestigacion servicio = new ServicioInvestigacion((op, args) -> op.equals("crear") ? "e t" : "", t -> herramientas, agente, new Corrector(), gasto, hueco,
                repositorio, programador, Clock.systemUTC(), tiempos, v -> 1);
        mvc = MockMvcBuilders.standaloneSetup(new ControladorInvestigacion(admision, servicio, repositorio, "clave-publica")).build();
    }

    @AfterEach
    void parar() {
        soltarModelo.countDown();
        await().atMost(Duration.ofSeconds(5)).until(hueco::libre);
        programador.shutdownNow();
        captcha.stop(0);
    }

    private int publicar() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/investigaciones").header("X-Real-IP", "1.2.3.4").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void dos_post_a_la_vez_un_202_y_un_409() throws Exception {
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Integer>> resultados = new ArrayList<>();
        try (var hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2; i++) {
                resultados.add(hilos.submit(() -> {
                    salida.await();
                    return publicar();
                }));
            }
            salida.countDown();
        }

        assertThat(List.of(resultados.get(0).get(), resultados.get(1).get())).containsExactlyInAnyOrder(202, 409);
    }

    private static String campo(String json, String nombre) {
        var coincidencia = java.util.regex.Pattern.compile("\"" + nombre + "\":\"([0-9a-f]+)\"").matcher(json);
        assertThat(coincidencia.find()).isTrue();
        return coincidencia.group(1);
    }

    private String leerHastaElFin(String id, String token, String ultimo) throws Exception {
        var peticion = MockMvcRequestBuilders.get("/api/investigaciones/" + id + "/eventos").param("token", token);
        if (ultimo != null) {
            peticion.header("Last-Event-ID", ultimo);
        }
        var respuesta = mvc.perform(peticion).andReturn().getResponse();
        await().atMost(Duration.ofSeconds(5)).until(() -> respuesta.getContentAsString().contains("event:fin"));
        return respuesta.getContentAsString();
    }

    @Test
    void los_eventos_salen_en_formato_sse_y_se_reanudan_con_last_event_id() throws Exception {
        String creada = mvc.perform(MockMvcRequestBuilders.post("/api/investigaciones").header("X-Real-IP", "1.2.3.4").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andReturn().getResponse().getContentAsString();
        String id = campo(creada, "id");
        String token = campo(creada, "tokenVer");
        soltarModelo.countDown();
        await().atMost(Duration.ofSeconds(5)).until(hueco::libre);

        String entero = leerHastaElFin(id, token, null);
        String reanudado = leerHastaElFin(id, token, "2");

        assertThat(entero).contains("id:1\nevent:estado\ndata:{\"estado\":\"PREPARANDO\"}", "event:fin");
        assertThat(reanudado).startsWith("id:3\n").doesNotContain("PREPARANDO");
    }

    @Test
    void estado_informa_sin_gastar() throws Exception {
        var respuesta = mvc.perform(MockMvcRequestBuilders.get("/api/estado")).andReturn().getResponse();

        assertThat(respuesta.getStatus()).isEqualTo(200);
        assertThat(respuesta.getContentAsString()).contains("\"disponible\":true", "clave-publica", "A1_BD_CAIDA", "\"total\":0");
        assertThat(hueco.libre()).isTrue();
    }

    @Test
    void caso_inventado_400() throws Exception {
        int estado = mvc.perform(MockMvcRequestBuilders.post("/api/investigaciones").contentType(MediaType.APPLICATION_JSON).content("{\"caso\":\"X\",\"captcha\":\"tok\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(estado).isEqualTo(400);
    }

    @Test
    void eventos_con_token_malo_404() throws Exception {
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/api/investigaciones/abc/eventos").param("token", "x")).andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aprobar_sin_propuesta_409() throws Exception {
        int estado = mvc.perform(MockMvcRequestBuilders.post("/api/investigaciones/abc/aprobar").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
                .andReturn().getResponse().getStatus();

        assertThat(estado).isEqualTo(409);
    }
}
