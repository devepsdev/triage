package dev.deveps.triage.api.modelo;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Comando manual, una sola petición por versión: comprueba si el modelo admite herramientas.
 * La clave se lee de MISTRAL_API_KEY y nunca se imprime.
 */
public final class ComprobarHerramientas {

    private static final URI URL = URI.create("https://api.mistral.ai/v1/chat/completions");
    private static final List<String> VERSIONES = List.of("mistral-small-2603", "mistral-medium-2604");

    private ComprobarHerramientas() {
    }

    public static void main(String[] argumentos) {
        String clave = System.getenv("MISTRAL_API_KEY");
        if (clave == null || clave.isBlank()) {
            System.out.println("Falta MISTRAL_API_KEY en el entorno");
            return;
        }

        ProveedorModelo proveedor = new MistralProveedor(URL, clave, HttpClient.newHttpClient(), Duration.ofSeconds(30));
        DefinicionFuncion funcion = new DefinicionFuncion("comprobar_estado", "Comprueba el estado de un servicio", Map.of(
                "type", "object",
                "properties", Map.of("servicio", Map.of("type", "string", "enum", List.of("web", "app", "db"))),
                "required", List.of("servicio")));
        Mensaje mensaje = new Mensaje("user", "Comprueba el estado del servicio db.", Optional.empty(), Optional.empty());

        for (String version : VERSIONES) {
            try {
                RespuestaModelo r = proveedor.completar(new PeticionModelo(version, List.of(mensaje), List.of(funcion), 100));
                System.out.printf("%s (devuelve %s): herramienta=%s entrada=%d salida=%d%n", version, r.versionDevuelta(),
                        r.llamada().map(l -> l.nombre() + " " + l.argumentosJson()).orElse("NINGUNA"),
                        r.tokensEntrada(), r.tokensSalida());
            } catch (ProveedorException e) {
                System.out.printf("%s: ERROR %s%n", version, e.getMessage());
            }
        }
    }
}
