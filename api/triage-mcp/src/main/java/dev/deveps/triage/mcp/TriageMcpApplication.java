package dev.deveps.triage.mcp;

import dev.deveps.triage.comun.ClienteGestor;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TriageMcpApplication {

    private static final String RUTA = "/mcp";

    public static void main(String[] argumentos) {
        SpringApplication.run(TriageMcpApplication.class, argumentos);
    }

    @Bean
    Herramientas.Gestor gestor(@Value("${triage.lectura-sock}") Path socket) {
        return new ClienteGestor(socket)::pedir;
    }

    @Bean
    Herramientas herramientas(Herramientas.Gestor gestor) {
        return new Herramientas(gestor);
    }

    @Bean
    HttpServletStreamableServerTransportProvider transporte() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(RUTA)
                .contextExtractor(peticion -> McpTransportContext.create(Map.of(FiltroToken.CLAVE, FiltroToken.token(peticion).orElse(""))))
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servlet(HttpServletStreamableServerTransportProvider transporte) {
        return new ServletRegistrationBean<>(transporte, RUTA);
    }

    @Bean
    FilterRegistrationBean<FiltroToken> filtroToken() {
        FilterRegistrationBean<FiltroToken> registro = new FilterRegistrationBean<>(new FiltroToken());
        registro.addUrlPatterns(RUTA);
        return registro;
    }

    @Bean(destroyMethod = "closeGracefully")
    McpSyncServer servidor(HttpServletStreamableServerTransportProvider transporte, Herramientas herramientas) {
        return McpServer.sync(transporte)
                .serverInfo("triage-mcp", "0.1.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(herramientas.especificaciones())
                .build();
    }
}
