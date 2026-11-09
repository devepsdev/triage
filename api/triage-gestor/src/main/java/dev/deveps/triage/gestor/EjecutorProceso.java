package dev.deveps.triage.gestor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Lanza la orden directamente, sin shell, y limita su tiempo y el tamaño de su salida. */
final class EjecutorProceso implements Ejecutor {

    private static final int MAX_SALIDA = 1024 * 1024;

    @Override
    public Resultado ejecutar(List<String> argv, Duration limite) {
        Process proceso;
        try {
            proceso = new ProcessBuilder(argv).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new Resultado(127, "");
        }

        byte[][] salida = {new byte[0]};
        Thread lector = Thread.ofVirtual().start(() -> salida[0] = leer(proceso.getInputStream()));
        try {
            boolean termino = proceso.waitFor(limite.toMillis(), TimeUnit.MILLISECONDS);
            if (!termino) {
                proceso.descendants().forEach(ProcessHandle::destroyForcibly);
                proceso.destroyForcibly();
            }
            lector.join(Duration.ofSeconds(2));
            String texto = new String(salida[0], StandardCharsets.UTF_8);
            return new Resultado(termino ? proceso.exitValue() : 124, texto);
        } catch (InterruptedException e) {
            proceso.destroyForcibly();
            Thread.currentThread().interrupt();
            return new Resultado(124, "");
        }
    }

    private static byte[] leer(InputStream entrada) {
        try (entrada) {
            byte[] datos = entrada.readNBytes(MAX_SALIDA);
            entrada.transferTo(java.io.OutputStream.nullOutputStream()); // el proceso no debe bloquearse por una salida enorme
            return datos;
        } catch (IOException e) {
            return new byte[0];
        }
    }
}
