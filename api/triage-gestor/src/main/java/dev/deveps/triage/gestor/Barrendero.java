package dev.deveps.triage.gestor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Borra lo que sobre, y solo lo que lleve la etiqueta triage.env. */
final class Barrendero {

    private static final Duration LIMITE = Duration.ofSeconds(60);

    private final Ejecutor docker;

    Barrendero(Ejecutor docker) {
        this.docker = docker;
    }

    /** Al arrancar el gestor no hay entornos legítimos: todo lo que quede es huérfano. */
    void barrerTodo() {
        barrer(linea -> true);
    }

    void barrerCaducados(Instant ahora) {
        barrer(linea -> caducado(linea, ahora));
    }

    private void barrer(Predicate<String[]> borrar) {
        borrarSeleccion(ComandosDocker.listarContenedoresTriage(), borrar, List.of("docker", "rm", "-f"));
        borrarSeleccion(ComandosDocker.listarRedesTriage(), borrar, List.of("docker", "network", "rm"));
    }

    private void borrarSeleccion(List<String> listar, Predicate<String[]> borrar, List<String> orden) {
        List<String> argv = new ArrayList<>(orden);
        docker.ejecutar(listar, LIMITE).texto().lines()
                .map(String::strip)
                .filter(linea -> !linea.isEmpty())
                .map(linea -> linea.split("\\s+"))
                .filter(borrar)
                .forEach(campos -> argv.add(campos[0]));
        if (argv.size() > orden.size()) {
            docker.ejecutar(List.copyOf(argv), LIMITE);
        }
    }

    private static boolean caducado(String[] campos, Instant ahora) {
        try {
            return campos.length < 2 || Instant.ofEpochSecond(Long.parseLong(campos[1])).isBefore(ahora);
        } catch (NumberFormatException e) {
            return true;
        }
    }
}
