package dev.deveps.triage.api.agente;

import dev.deveps.triage.api.modelo.DefinicionFuncion;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Una conexión MCP por investigación, con el token de ese entorno en la cabecera: el modelo nunca lo ve. */
public final class HerramientasMcp implements Herramientas, AutoCloseable {

    private final McpSyncClient cliente;

    public HerramientasMcp(String servidor, String token) {
        var transporte = HttpClientStreamableHttpTransport.builder(servidor)
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .build();
        this.cliente = McpClient.sync(transporte)
                .requestTimeout(Duration.ofSeconds(20))
                .initializationTimeout(Duration.ofSeconds(10))
                .build();
        this.cliente.initialize();
    }

    @Override
    public List<DefinicionFuncion> definiciones() {
        return cliente.listTools().tools().stream()
                .map(t -> new DefinicionFuncion(t.name(), t.description(), t.inputSchema()))
                .toList();
    }

    @Override
    public String llamar(String nombre, Map<String, Object> args) {
        CallToolResult resultado;
        try {
            resultado = cliente.callTool(new CallToolRequest(nombre, args));
        } catch (RuntimeException e) {
            throw new HerramientaException("La consulta no se ha podido hacer.");
        }
        String texto = resultado.content().stream()
                .filter(TextContent.class::isInstance).map(c -> ((TextContent) c).text())
                .collect(Collectors.joining("\n"));
        if (Boolean.TRUE.equals(resultado.isError())) {
            throw new HerramientaException(texto);
        }
        return texto;
    }

    @Override
    public void close() {
        cliente.closeGracefully();
    }
}
