package dev.deveps.triage.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.comun.GestorException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HerramientasTest {

    private final List<List<String>> llamadas = new ArrayList<>();
    private final Herramientas herramientas = new Herramientas((op, args) -> {
        List<String> llamada = new ArrayList<>(List.of(op));
        llamada.addAll(List.of(args));
        llamadas.add(llamada);
        return "respuesta";
    });

    private static Herramientas.Herramienta llamada(String nombre) {
        return Herramientas.CATALOGO.stream().filter(h -> h.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    @Test
    void solo_conoce_el_socket_de_lectura() throws IOException {
        try (var entrada = getClass().getResourceAsStream("/application.properties")) {
            String configuracion = new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuracion).contains("lectura.sock").doesNotContain("control");
        }
    }

    @Test
    void pasa_el_token_primero_y_los_argumentos_en_orden() {
        herramientas.llamar(llamada("leer_logs"), "TOK", Map.of("lineas", 5, "servicio", "db"));

        assertThat(llamadas).containsExactly(List.of("leer_logs", "TOK", "db", "5"));
    }

    @Test
    void rechaza_sin_llegar_al_gestor() {
        for (Map<String, Object> malos : List.of(
                Map.<String, Object>of("servicio", "sonda", "lineas", 5),
                Map.<String, Object>of("servicio", "db", "lineas", 201),
                Map.<String, Object>of("servicio", "db", "lineas", "5"),
                Map.<String, Object>of("servicio", "db"),
                Map.<String, Object>of("servicio", "db", "lineas", 5, "extra", "x"))) {
            CallToolResult resultado = herramientas.llamar(llamada("leer_logs"), "TOK", malos);
            assertThat(resultado.isError()).as(malos.toString()).isTrue();
        }
        assertThat(llamadas).isEmpty();
    }

    @Test
    void sin_argumentos_es_valido_si_la_herramienta_no_los_tiene() {
        herramientas.llamar(llamada("listar_servicios"), "TOK", null);

        assertThat(llamadas).containsExactly(List.of("listar_servicios", "TOK"));
    }

    @Test
    void un_error_del_gestor_solo_da_su_codigo() {
        Herramientas rotas = new Herramientas((op, args) -> {
            throw new GestorException("token");
        });

        CallToolResult resultado = rotas.llamar(llamada("listar_servicios"), "TOK", Map.of());

        assertThat(resultado.isError()).isTrue();
        assertThat(resultado.content().toString()).contains("token");
    }
}
