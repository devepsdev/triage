package dev.deveps.triage.api.modelo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MistralProveedorTest {

    private static final String CLAVE = "clave-secreta-de-prueba";

    private static final String RESPUESTA_CON_LLAMADA = """
            {"id":"cmpl-1","object":"chat.completion","created":1,"model":"mistral-small-2603",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
               "tool_calls":[{"id":"abc123def","type":"function","index":0,
                 "function":{"name":"leer_logs","arguments":"{\\"servicio\\":\\"db\\"}"}}]}}],
             "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer servidor;
    private volatile String cuerpoRecibido;
    private volatile String autorizacionRecibida;
    private volatile int codigoRespuesta = 200;
    private volatile String respuesta = RESPUESTA_CON_LLAMADA;
    private volatile long retrasoMs = 0;

    @BeforeEach
    void arrancar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/chat/completions", intercambio -> {
            cuerpoRecibido = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            autorizacionRecibida = intercambio.getRequestHeaders().getFirst("Authorization");
            try {
                Thread.sleep(retrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = respuesta.getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(codigoRespuesta, bytes.length);
            intercambio.getResponseBody().write(bytes);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void parar() {
        servidor.stop(0);
    }

    private MistralProveedor proveedor(Duration tiempoMaximo) {
        URI url = URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1/chat/completions");
        return new MistralProveedor(url, CLAVE, HttpClient.newHttpClient(), tiempoMaximo);
    }

    private MistralProveedor proveedor() {
        return proveedor(Duration.ofSeconds(5));
    }

    private static PeticionModelo peticion() {
        return new PeticionModelo("mistral-small-2603",
                List.of(new Mensaje("user", "Alerta: GET / responde 5xx", Optional.empty(), Optional.empty())),
                List.of(new DefinicionFuncion("leer_logs", "Lee logs",
                        Map.of("type", "object", "properties", Map.of()))),
                500);
    }

    private JsonNode cuerpoEnviado() {
        return json.readTree(cuerpoRecibido);
    }

    @Test
    void envia_version_fija_y_una_herramienta_por_turno() throws Exception {
        proveedor().completar(peticion());

        JsonNode cuerpo = cuerpoEnviado();
        assertThat(cuerpo.get("model").asString()).isEqualTo("mistral-small-2603");
        assertThat(cuerpo.get("tool_choice").asString()).isEqualTo("any");
        assertThat(cuerpo.get("parallel_tool_calls").asBoolean(true)).isFalse();
        assertThat(cuerpo.get("max_tokens").asInt()).isEqualTo(500);
    }

    @Test
    void declara_las_funciones_con_su_esquema() throws Exception {
        proveedor().completar(peticion());

        JsonNode herramienta = cuerpoEnviado().get("tools").get(0);
        assertThat(herramienta.get("type").asString()).isEqualTo("function");
        assertThat(herramienta.get("function").get("name").asString()).isEqualTo("leer_logs");
        assertThat(herramienta.get("function").get("parameters").get("type").asString()).isEqualTo("object");
    }

    @Test
    void envia_la_clave_como_bearer() throws Exception {
        proveedor().completar(peticion());

        assertThat(autorizacionRecibida).isEqualTo("Bearer " + CLAVE);
    }

    @Test
    void serializa_llamada_previa_y_su_resultado() throws Exception {
        LlamadaFuncion previa = new LlamadaFuncion("abc123def", "leer_logs", "{\"servicio\":\"db\"}");
        PeticionModelo conHistorial = new PeticionModelo("mistral-small-2603", List.of(
                new Mensaje("assistant", "", Optional.of(previa), Optional.empty()),
                new Mensaje("tool", "{\"datos_no_fiables\":\"x\"}", Optional.empty(), Optional.of("abc123def"))),
                peticion().funciones(), 500);

        proveedor().completar(conHistorial);

        JsonNode mensajes = cuerpoEnviado().get("messages");
        JsonNode llamada = mensajes.get(0).get("tool_calls").get(0);
        assertThat(mensajes.get(0).get("role").asString()).isEqualTo("assistant");
        assertThat(llamada.get("id").asString()).isEqualTo("abc123def");
        assertThat(llamada.get("function").get("name").asString()).isEqualTo("leer_logs");
        assertThat(llamada.get("function").get("arguments").asString()).isEqualTo("{\"servicio\":\"db\"}");
        assertThat(mensajes.get(1).get("role").asString()).isEqualTo("tool");
        assertThat(mensajes.get(1).get("tool_call_id").asString()).isEqualTo("abc123def");
    }

    @Test
    void lee_la_llamada_a_funcion() throws Exception {
        RespuestaModelo r = proveedor().completar(peticion());

        assertThat(r.llamada()).contains(new LlamadaFuncion("abc123def", "leer_logs", "{\"servicio\":\"db\"}"));
        assertThat(r.versionDevuelta()).isEqualTo("mistral-small-2603");
    }

    @Test
    void argumentos_como_objeto_se_convierten_a_texto_json() throws Exception {
        respuesta = RESPUESTA_CON_LLAMADA.replace("\"{\\\"servicio\\\":\\\"db\\\"}\"", "{\"servicio\":\"db\"}");

        RespuestaModelo r = proveedor().completar(peticion());

        assertThat(r.llamada().orElseThrow().argumentosJson()).isEqualTo("{\"servicio\":\"db\"}");
    }

    @Test
    void lee_tokens_de_usage() throws Exception {
        RespuestaModelo r = proveedor().completar(peticion());

        assertThat(r.tokensEntrada()).isEqualTo(120);
        assertThat(r.tokensSalida()).isEqualTo(30);
    }

    @Test
    void respuesta_sin_llamada_devuelve_el_texto() throws Exception {
        respuesta = """
                {"model":"mistral-small-2603","choices":[{"message":{"role":"assistant","content":"hola"}}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                """;

        RespuestaModelo r = proveedor().completar(peticion());

        assertThat(r.llamada()).isEmpty();
        assertThat(r.texto()).isEqualTo("hola");
    }

    @Test
    void contenido_nulo_es_texto_vacio() throws Exception {
        respuesta = RESPUESTA_CON_LLAMADA.replace("\"content\":\"\"", "\"content\":null");

        assertThat(proveedor().completar(peticion()).texto()).isEmpty();
    }

    @Test
    void sin_usage_es_error_porque_no_se_podria_contar_el_gasto() {
        respuesta = """
                {"model":"mistral-small-2603","choices":[{"message":{"role":"assistant","content":"hola"}}]}
                """;

        ProveedorException e = catchThrowableOfType(ProveedorException.class, () -> proveedor().completar(peticion()));

        assertThat(e).isNotNull();
        assertThat(e.recuperable()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"429,true", "500,true", "503,true", "400,false", "401,false"})
    void clasifica_los_errores_http(int codigo, boolean recuperable) {
        codigoRespuesta = codigo;
        respuesta = "{\"message\":\"error\"}";

        ProveedorException e = catchThrowableOfType(ProveedorException.class, () -> proveedor().completar(peticion()));

        assertThat(e).isNotNull();
        assertThat(e.recuperable()).isEqualTo(recuperable);
    }

    @Test
    void tiempo_agotado_es_recuperable() {
        retrasoMs = 1000;

        ProveedorException e = catchThrowableOfType(ProveedorException.class,
                () -> proveedor(Duration.ofMillis(100)).completar(peticion()));

        assertThat(e).isNotNull();
        assertThat(e.recuperable()).isTrue();
    }

    @Test
    void json_invalido_no_es_recuperable() {
        respuesta = "<html>no es json</html>";

        ProveedorException e = catchThrowableOfType(ProveedorException.class, () -> proveedor().completar(peticion()));

        assertThat(e).isNotNull();
        assertThat(e.recuperable()).isFalse();
    }

    @Test
    void la_clave_nunca_aparece_en_el_mensaje_de_excepcion() {
        codigoRespuesta = 401;
        respuesta = "{\"message\":\"Clave no valida: " + CLAVE + "\"}";

        ProveedorException e = catchThrowableOfType(ProveedorException.class, () -> proveedor().completar(peticion()));

        assertThat(e).isNotNull();
        assertThat(e.getMessage()).doesNotContain(CLAVE);
    }
}
