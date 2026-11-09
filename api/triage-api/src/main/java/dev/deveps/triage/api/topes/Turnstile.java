package dev.deveps.triage.api.topes;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Comprueba el captcha con Cloudflare. No le envía la IP del visitante. Ante cualquier duda, responde que no. */
public final class Turnstile {

    private static final int LARGO_MAXIMO = 2048;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http;
    private final URI url;
    private final String secreto;

    public Turnstile(HttpClient http, URI url, String secreto) {
        this.http = http;
        this.url = url;
        this.secreto = secreto;
    }

    public boolean configurado() {
        return secreto != null && !secreto.isBlank();
    }

    public boolean valido(String token) {
        if (!configurado() || token == null || token.isBlank() || token.length() > LARGO_MAXIMO) {
            return false;
        }
        String cuerpo = "secret=" + codificar(secreto) + "&response=" + codificar(token);
        HttpRequest peticion = HttpRequest.newBuilder(url)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo))
                .build();
        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            return respuesta.statusCode() == 200 && json.readTree(respuesta.body()).path("success").asBoolean(false);
        } catch (IOException | JacksonException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String codificar(String texto) {
        return URLEncoder.encode(texto, StandardCharsets.UTF_8);
    }
}
