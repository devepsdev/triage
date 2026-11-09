package dev.deveps.triage.gestor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Registra cada orden y responde según la función indicada; por defecto, éxito sin salida. */
final class EjecutorFalso implements Ejecutor {
    final List<List<String>> ordenes = new ArrayList<>();
    Function<List<String>, Resultado> respuesta = argv -> new Resultado(0, "");

    @Override
    public synchronized Resultado ejecutar(List<String> argv, Duration limite) {
        ordenes.add(argv);
        return respuesta.apply(argv);
    }

    List<List<String>> ordenesCon(String palabra) {
        return ordenes.stream().filter(o -> o.contains(palabra)).toList();
    }
}
