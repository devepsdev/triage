package dev.deveps.triage.api.bateria;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** El gasto en el modelo solo puede venir de una visita o de alguien que lanza la batería a mano. */
class SinProgramacionTest {

    private static final Path RAIZ = Path.of("../..").toAbsolutePath().normalize();
    private static final Set<String> IGNORADAS = Set.of(".git", "target", "node_modules", "dist", ".angular");

    private static boolean ignorada(Path archivo) {
        return RAIZ.relativize(archivo).toString().replace('\\', '/').transform(r -> Stream.of(r.split("/")).anyMatch(IGNORADAS::contains));
    }

    private static List<Path> archivos(java.util.function.Predicate<Path> filtro) throws IOException {
        assertThat(RAIZ.resolve("api/pom.xml")).exists();
        try (Stream<Path> todos = Files.walk(RAIZ)) {
            return todos.filter(Files::isRegularFile).filter(a -> !ignorada(a)).filter(filtro).toList();
        }
    }

    @Test
    void ningun_java_programa_tareas() throws IOException {
        String anotacion = "@" + "Scheduled";
        String habilitar = "@" + "EnableScheduling";
        List<Path> javas = archivos(a -> a.toString().endsWith(".java"));

        assertThat(javas).isNotEmpty();
        for (Path java : javas) {
            assertThat(Files.readString(java)).as(java.toString()).doesNotContain(anotacion, habilitar);
        }
    }

    @Test
    void ningun_workflow_ni_unidad_lanza_la_bateria() throws IOException {
        List<Path> automatismos = archivos(a -> {
            String ruta = a.toString().replace('\\', '/');
            return ruta.contains("/.github/workflows/") || ruta.endsWith(".timer") || ruta.endsWith(".service") || ruta.endsWith("crontab");
        });

        for (Path archivo : automatismos) {
            assertThat(Files.readString(archivo)).as(archivo.toString()).doesNotContain("--bateria");
        }
    }
}
