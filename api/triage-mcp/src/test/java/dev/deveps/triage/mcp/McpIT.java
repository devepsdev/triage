package dev.deveps.triage.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Con el cliente oficial contra el servidor real, y un gestor falso que cuenta las llamadas. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "triage.lectura-sock=/no/existe.sock")
class McpIT {

    private static final String TOKEN = "a".repeat(64);
    private static final List<List<String>> LLAMADAS = Collections.synchronizedList(new ArrayList<>());

    @TestConfiguration
    static class GestorFalso {
        @Bean
        @Primary
        Herramientas.Gestor gestorFalso() {
            return (op, args) -> {
                List<String> llamada = new ArrayList<>(List.of(op));
                llamada.addAll(List.of(args));
                LLAMADAS.add(llamada);
                return "respuesta de " + op;
            };
        }
    }

    @Autowired
    private GestorFalso ignorado;

    @LocalServerPort
    private int puerto;

    private McpSyncClient cliente;

    @BeforeEach
    void conectar() {
        LLAMADAS.clear();
        var transporte = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + puerto)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + TOKEN))
                .build();
        cliente = McpClient.sync(transporte).requestTimeout(Duration.ofSeconds(10)).build();
        cliente.initialize();
    }

    @AfterEach
    void desconectar() {
        cliente.closeGracefully();
    }

    private List<Tool> herramientas() {
        return cliente.listTools().tools();
    }

    private Tool herramienta(String nombre) {
        return herramientas().stream().filter(t -> t.name().equals(nombre)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propiedades(Tool t) {
        return (Map<String, Object>) t.inputSchema().getOrDefault("properties", Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> propiedad(Tool t, String nombre) {
        return (Map<String, Object>) propiedades(t).get(nombre);
    }

    @Test
    void lista_exacta_de_once() {
        assertThat(herramientas()).extracting(Tool::name).containsExactlyInAnyOrder(
                "listar_servicios", "leer_logs", "comprobar_http", "comprobar_tls", "comprobar_puerto", "ver_uso_disco",
                "ver_mayores_archivos", "ver_config_nginx", "ver_estado_bd", "ver_definicion_alerta", "ver_cambios_recientes");
    }

    @Test
    void los_parametros_son_listas_cerradas_o_rangos() {
        assertThat(propiedad(herramienta("leer_logs"), "servicio").get("enum")).isEqualTo(List.of("web", "app", "db"));
        assertThat(propiedad(herramienta("leer_logs"), "lineas")).containsEntry("minimum", 1).containsEntry("maximum", 200);

        for (Tool t : herramientas()) {
            assertThat(t.inputSchema().get("additionalProperties")).as(t.name()).isEqualTo(false);
            for (String nombre : propiedades(t).keySet()) {
                Map<String, Object> p = propiedad(t, nombre);
                assertThat(p.containsKey("enum") || (p.containsKey("minimum") && p.containsKey("maximum"))).as(t.name() + "." + nombre).isTrue();
            }
        }
    }

    @Test
    void todas_se_declaran_de_solo_lectura() {
        assertThat(herramientas()).allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
    }

    @Test
    void el_token_nunca_es_un_argumento_visible() {
        assertThat(herramientas()).allSatisfy(t -> assertThat(propiedades(t).keySet()).doesNotContain("token"));
    }

    @Test
    void una_llamada_valida_llega_con_el_token_de_la_cabecera() {
        CallToolResult r = cliente.callTool(new CallToolRequest("comprobar_puerto", Map.of("servicio", "app", "puerto", 8081)));

        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(LLAMADAS).containsExactly(List.of("comprobar_puerto", TOKEN, "app", "8081"));
    }

    @Test
    void lo_invalido_no_llega_al_gestor() {
        intentar("borrar_todo", Map.of());
        intentar("leer_logs", Map.of("servicio", "sonda", "lineas", 5));
        intentar("comprobar_http", Map.of("ruta", "/etc/passwd", "esquema", "https"));
        intentar("listar_servicios", Map.of("token", "b".repeat(64)));

        assertThat(LLAMADAS).isEmpty();
    }

    private void intentar(String nombre, Map<String, Object> argumentos) {
        try {
            assertThat(cliente.callTool(new CallToolRequest(nombre, argumentos)).isError()).as(nombre).isTrue();
        } catch (RuntimeException rechazadoPorElProtocolo) {
            assertThat(rechazadoPorElProtocolo).as(nombre).isNotNull();
        }
    }

    private int estadoCon(String autorizacion) throws Exception {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/mcp"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (autorizacion != null) {
            peticion.header("Authorization", autorizacion);
        }
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(peticion.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    @Test
    void sin_token_o_mal_formado_401() throws Exception {
        assertThat(estadoCon(null)).isEqualTo(401);
        assertThat(estadoCon("Bearer corto")).isEqualTo(401);
        assertThat(estadoCon("Basic " + TOKEN)).isEqualTo(401);
    }
}
