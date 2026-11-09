package dev.deveps.triage.api.agente;

import java.util.List;
import java.util.Optional;

/** {@code motivoFin}: diagnostico, rondas, tope, proveedor o cancelada. */
public record ResultadoAgente(Optional<Diagnostico> diagnostico, List<LlamadaRegistrada> llamadas, String motivoFin) {}
