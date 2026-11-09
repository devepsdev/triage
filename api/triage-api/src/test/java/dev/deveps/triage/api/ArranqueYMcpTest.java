package dev.deveps.triage.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.deveps.triage.api.agente.HerramientaException;
import dev.deveps.triage.api.agente.HerramientasMcp;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Que la aplicación real arranca con las migraciones reales, y que el cliente MCP funciona contra un servidor de verdad. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:arranque;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.flyway.locations=classpath:db/migration,classpath:db/prueba",
        "triage.control-sock=/no/existe.sock"
})
class ArranqueYMcpTest {

    private static final String TOKEN = "c".repeat(64);

    @TestConfiguration
    static class ServidorMcp {
        @Bean
        HttpServletStreamableServerTransportProvider transporteDePrueba() {
            return HttpServletStreamableServerTransportProvider.builder()
                    .mcpEndpoint("/mcp")
                    .contextExtractor(p -> McpTransportContext.create(Map.of("cabecera", String.valueOf(p.getHeader("Authorization")))))
                    .build();
        }

        @Bean
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servletDePrueba(HttpServletStreamableServerTransportProvider t) {
            return new ServletRegistrationBean<>(t, "/mcp");
        }

        @Bean(destroyMethod = "closeGracefully")
        McpSyncServer servidorDePrueba(HttpServletStreamableServerTransportProvider t) {
            var eco = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder().name("eco").description("devuelve la cabecera").inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build())
                    .callHandler((i, p) -> CallToolResult.builder().addTextContent(String.valueOf(i.transportContext().get("cabecera"))).build())
                    .build();
            var falla = SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder().name("falla").description("error").inputSchema(new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null)).build())
                    .callHandler((i, p) -> CallToolResult.builder().addTextContent("argumentos no validos").isError(true).build())
                    .build();
            return McpServer.sync(t).serverInfo("prueba", "1").capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tools(eco, falla).build();
        }
    }

    @LocalServerPort
    private int puerto;

    @Test
    void el_cliente_lista_llama_con_el_token_e_informa_de_errores() {
        try (HerramientasMcp h = new HerramientasMcp("http://127.0.0.1:" + puerto, TOKEN)) {
            assertThat(h.definiciones()).extracting(d -> d.nombre()).containsExactlyInAnyOrder("eco", "falla");
            assertThat(h.llamar("eco", Map.of())).isEqualTo("Bearer " + TOKEN);
            assertThatThrownBy(() -> h.llamar("falla", Map.of())).isInstanceOf(HerramientaException.class).hasMessage("argumentos no validos");
            assertThatThrownBy(() -> h.llamar("inexistente", Map.of())).isInstanceOf(HerramientaException.class);
        }
    }
}
