package dev.deveps.triage.gestor;

import java.time.Duration;
import java.util.List;

interface Ejecutor {
    Resultado ejecutar(List<String> argv, Duration limite);
}
