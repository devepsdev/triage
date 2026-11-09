package dev.deveps.triage.gestor;

import dev.deveps.triage.comun.Caso;
import java.time.Instant;

record Entorno(String id, String token, Caso caso, int variante, Instant caduca) {}
