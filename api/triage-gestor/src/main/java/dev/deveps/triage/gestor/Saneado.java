package dev.deveps.triage.gestor;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Limpia todo lo que sale de un contenedor antes de que lo vea nadie. */
final class Saneado {

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");
    private static final Pattern RUTA_DOCKER = Pattern.compile("/var/lib/docker\\S*");
    private static final Pattern ID_LARGO = Pattern.compile("\\b[0-9a-f]{64}\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}\\b");

    private Saneado() {
    }

    static String limpiar(String texto, int maxBytes) {
        String limpio = CONTROL.matcher(ANSI.matcher(texto).replaceAll("")).replaceAll("");
        limpio = ID_LARGO.matcher(RUTA_DOCKER.matcher(limpio).replaceAll("[ruta]")).replaceAll("[id]");
        return recortar(ocultarIpsPublicas(limpio), maxBytes);
    }

    private static String ocultarIpsPublicas(String texto) {
        return IPV4.matcher(texto).replaceAll(m -> privada(m) ? Matcher.quoteReplacement(m.group()) : "[ip]");
    }

    private static boolean privada(java.util.regex.MatchResult ip) {
        int a = Integer.parseInt(ip.group(1));
        int b = Integer.parseInt(ip.group(2));
        return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    }

    /** Conserva el final, que en un log es lo reciente, sin partir un carácter de varios bytes. */
    private static String recortar(String texto, int maxBytes) {
        byte[] bytes = texto.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return texto;
        }
        int inicio = bytes.length - maxBytes;
        while (inicio < bytes.length && (bytes[inicio] & 0xC0) == 0x80) {
            inicio++;
        }
        return new String(bytes, inicio, bytes.length - inicio, StandardCharsets.UTF_8);
    }
}
