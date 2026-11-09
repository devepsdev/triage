package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EjecutorProcesoTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElseThrow();

    @Test
    void devuelve_codigo_y_salida_del_proceso() {
        Resultado r = new EjecutorProceso().ejecutar(List.of(JAVA, "--version"), Duration.ofSeconds(30));

        assertThat(r.salida()).isZero();
        assertThat(r.texto()).contains("25");
    }

    @Test
    void mata_el_proceso_al_superar_el_limite(@TempDir Path dir) throws Exception {
        Path dormir = dir.resolve("Dormir.java");
        Files.writeString(dormir, "class Dormir { public static void main(String[] a) throws Exception { Thread.sleep(60000); } }");
        long inicio = System.nanoTime();

        Resultado r = new EjecutorProceso().ejecutar(List.of(JAVA, dormir.toString()), Duration.ofSeconds(2));

        assertThat(r.salida()).isEqualTo(124);
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofSeconds(20));
    }
}
