package dev.deveps.triage.api.modelo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Llama a la API de chat de Mistral. Sin librerías del proveedor: solo HTTP y JSON. */
public final class MistralProveedor implements ProveedorModelo {

    private final JsonMapper json = JsonMapper.builder().build();
    private final URI url;
    private final String clave;
    private final HttpClient http;
    private final Duration tiempoMaximo;

    public MistralProveedor(URI url, String clave, HttpClient http, Duration tiempoMaximo) {
        this.url = url;
        this.clave = clave;
        this.http = http;
        this.tiempoMaximo = tiempoMaximo;
    }

    @Override
    public RespuestaModelo completar(PeticionModelo peticion) throws ProveedorException {
        HttpResponse<String> respuesta = enviar(HttpRequest.newBuilder(url)
                .timeout(tiempoMaximo)
                .header("Authorization", "Bearer " + clave)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(BodyPublishers.ofString(cuerpo(peticion)))
                .build());

        int codigo = respuesta.statusCode();
        if (codigo != 200) {
            // Solo el código: el cuerpo de un error podría repetir la clave.
            throw new ProveedorException("Mistral respondió HTTP " + codigo, codigo == 429 || codigo >= 500);
        }
        return interpretar(respuesta.body());
    }

    private HttpResponse<String> enviar(HttpRequest peticion) throws ProveedorException {
        try {
            return http.send(peticion, BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ProveedorException("Mistral no respondió a tiempo", true);
        } catch (IOException e) {
            throw new ProveedorException("Fallo de conexión con Mistral", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProveedorException("Llamada a Mistral interrumpida", false);
        }
    }

    private String cuerpo(PeticionModelo peticion) {
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.put("model", peticion.version());
        cuerpo.put("max_tokens", peticion.maxTokensSalida());
        cuerpo.put("tool_choice", "any");
        cuerpo.put("parallel_tool_calls", false);

        ArrayNode mensajes = cuerpo.putArray("messages");
        peticion.mensajes().forEach(m -> mensajes.add(mensaje(m)));

        ArrayNode herramientas = cuerpo.putArray("tools");
        for (DefinicionFuncion f : peticion.funciones()) {
            ObjectNode herramienta = herramientas.addObject();
            herramienta.put("type", "function");
            ObjectNode funcion = herramienta.putObject("function");
            funcion.put("name", f.nombre());
            funcion.put("description", f.descripcion());
            funcion.set("parameters", json.valueToTree(f.esquema()));
        }
        return json.writeValueAsString(cuerpo);
    }

    private ObjectNode mensaje(Mensaje m) {
        ObjectNode nodo = json.createObjectNode();
        nodo.put("role", m.rol());
        nodo.put("content", m.contenido());
        m.idLlamada().ifPresent(id -> nodo.put("tool_call_id", id));
        m.llamada().ifPresent(l -> {
            ObjectNode llamada = nodo.putArray("tool_calls").addObject();
            llamada.put("id", l.id());
            llamada.put("type", "function");
            ObjectNode funcion = llamada.putObject("function");
            funcion.put("name", l.nombre());
            funcion.put("arguments", l.argumentosJson());
        });
        return nodo;
    }

    private RespuestaModelo interpretar(String texto) throws ProveedorException {
        JsonNode raiz;
        try {
            raiz = json.readTree(texto);
        } catch (JacksonException e) {
            throw new ProveedorException("Mistral devolvió una respuesta que no es JSON", false);
        }

        JsonNode usage = raiz.path("usage");
        if (!usage.path("prompt_tokens").isNumber() || !usage.path("completion_tokens").isNumber()) {
            throw new ProveedorException("Respuesta sin recuento de tokens: no se puede contar el gasto", false);
        }

        JsonNode mensaje = raiz.path("choices").path(0).path("message");
        if (mensaje.isMissingNode()) {
            throw new ProveedorException("Respuesta de Mistral sin mensaje", false);
        }

        JsonNode contenido = mensaje.path("content");
        return new RespuestaModelo(
                llamada(mensaje.path("tool_calls").path(0)),
                contenido.isString() ? contenido.asString() : "",
                usage.get("prompt_tokens").asInt(),
                usage.get("completion_tokens").asInt(),
                raiz.path("model").asString(""));
    }

    private static Optional<LlamadaFuncion> llamada(JsonNode nodo) {
        if (nodo.isMissingNode()) {
            return Optional.empty();
        }
        JsonNode funcion = nodo.path("function");
        JsonNode argumentos = funcion.path("arguments");
        // Mistral puede devolver los argumentos como texto o como objeto.
        String argumentosJson = argumentos.isString() ? argumentos.asString() : argumentos.toString();
        return Optional.of(new LlamadaFuncion(nodo.path("id").asString(""), funcion.path("name").asString(""), argumentosJson));
    }
}
