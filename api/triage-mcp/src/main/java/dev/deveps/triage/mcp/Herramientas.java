package dev.deveps.triage.mcp;

import dev.deveps.triage.comun.GestorException;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Las once herramientas. Todo parámetro es una lista cerrada o un rango: aquí no existen las rutas ni el texto libre. */
public final class Herramientas {

    @FunctionalInterface
    public interface Gestor {
        String pedir(String operacion, String... argumentos) throws GestorException;
    }

    /** Un parámetro: o tiene valores cerrados, o es un entero con mínimo y máximo. */
    record Parametro(String nombre, String descripcion, List<?> valores, int minimo, int maximo) {

        static Parametro lista(String nombre, String descripcion, Object... valores) {
            return new Parametro(nombre, descripcion, List.of(valores), 0, 0);
        }

        static Parametro rango(String nombre, String descripcion, int minimo, int maximo) {
            return new Parametro(nombre, descripcion, List.of(), minimo, maximo);
        }

        boolean admite(Object valor) {
            if (!valores.isEmpty()) {
                return valores.contains(valor);
            }
            return valor instanceof Integer n && n >= minimo && n <= maximo;
        }

        Map<String, Object> esquema() {
            Map<String, Object> esquema = new LinkedHashMap<>();
            if (valores.isEmpty()) {
                esquema.put("type", "integer");
                esquema.put("minimum", minimo);
                esquema.put("maximum", maximo);
            } else {
                esquema.put("type", valores.getFirst() instanceof Integer ? "integer" : "string");
                esquema.put("enum", valores);
            }
            esquema.put("description", descripcion);
            return esquema;
        }
    }

    record Herramienta(String nombre, String descripcion, List<Parametro> parametros) {
    }

    private static final Parametro SERVICIO = Parametro.lista("servicio", "Servicio de la tienda", "web", "app", "db");

    static final List<Herramienta> CATALOGO = List.of(
            new Herramienta("listar_servicios", "Estado, código de salida, reinicios y salud de web, app y db.", List.of()),
            new Herramienta("leer_logs", "Últimas líneas de la salida de un servicio. Funciona aunque esté parado.",
                    List.of(SERVICIO, Parametro.rango("lineas", "Número de líneas", 1, 200))),
            new Herramienta("comprobar_http", "Hace una petición a la tienda. Devuelve código, tiempo, redirección y los primeros 2 KB.",
                    List.of(Parametro.lista("ruta", "Ruta a pedir", "/", "/salud", "/api/pedidos", "/demo/"),
                            Parametro.lista("esquema", "Protocolo", "http", "https"))),
            new Herramienta("comprobar_tls", "Certificado que sirve web: emisor, sujeto, fechas, días restantes y resultado de la verificación.", List.of()),
            new Herramienta("comprobar_puerto", "Indica si un puerto de un servicio está abierto, rechazado o sin respuesta.",
                    List.of(SERVICIO, Parametro.lista("puerto", "Puerto", 80, 443, 5432, 8080, 8081))),
            new Herramienta("ver_uso_disco", "Uso de los volúmenes de un servicio.", List.of(SERVICIO)),
            new Herramienta("ver_mayores_archivos", "Los 10 archivos más grandes de una zona de un servicio.",
                    List.of(SERVICIO, Parametro.lista("zona", "Zona", "logs", "datos"))),
            new Herramienta("ver_config_nginx", "Configuración efectiva de nginx y resultado de nginx -t.", List.of()),
            new Herramienta("ver_estado_bd", "Disponibilidad de la base de datos, número de conexiones y tamaño.", List.of()),
            new Herramienta("ver_definicion_alerta", "URL, código esperado y texto buscado por la comprobación que disparó la alerta.", List.of()),
            new Herramienta("ver_cambios_recientes", "Historial de despliegues y cambios recientes.", List.of()));

    private final Gestor gestor;

    public Herramientas(Gestor gestor) {
        this.gestor = gestor;
    }

    List<SyncToolSpecification> especificaciones() {
        return CATALOGO.stream().map(h -> SyncToolSpecification.builder()
                .tool(definicion(h))
                .callHandler((intercambio, peticion) -> llamar(h, String.valueOf(intercambio.transportContext().get(FiltroToken.CLAVE)), peticion.arguments()))
                .build()).toList();
    }

    private static McpSchema.Tool definicion(Herramienta h) {
        Map<String, Object> propiedades = new LinkedHashMap<>();
        h.parametros().forEach(p -> propiedades.put(p.nombre(), p.esquema()));
        List<String> obligatorios = h.parametros().stream().map(Parametro::nombre).toList();
        return McpSchema.Tool.builder()
                .name(h.nombre())
                .description(h.descripcion())
                .inputSchema(new McpSchema.JsonSchema("object", propiedades, obligatorios, false, null, null))
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, null))
                .build();
    }

    /** Segunda barrera tras el esquema del SDK: lo que no pasa de aquí nunca llega al gestor. */
    CallToolResult llamar(Herramienta h, String token, Map<String, Object> recibidos) {
        Map<String, Object> argumentos = recibidos == null ? Map.of() : recibidos;
        Optional<List<String>> validados = validar(h, argumentos);
        if (validados.isEmpty()) {
            return error("argumentos no validos");
        }
        List<String> orden = new ArrayList<>(List.of(token));
        orden.addAll(validados.get());
        try {
            return CallToolResult.builder().addTextContent(gestor.pedir(h.nombre(), orden.toArray(String[]::new))).build();
        } catch (GestorException e) {
            return error("la consulta ha fallado: " + e.codigo());
        }
    }

    private static Optional<List<String>> validar(Herramienta h, Map<String, Object> argumentos) {
        if (argumentos.size() != h.parametros().size()) {
            return Optional.empty();
        }
        List<String> validados = new ArrayList<>();
        for (Parametro p : h.parametros()) {
            if (!p.admite(argumentos.get(p.nombre()))) {
                return Optional.empty();
            }
            validados.add(String.valueOf(argumentos.get(p.nombre())));
        }
        return Optional.of(validados);
    }

    private static CallToolResult error(String mensaje) {
        return CallToolResult.builder().addTextContent(mensaje).isError(true).build();
    }
}
