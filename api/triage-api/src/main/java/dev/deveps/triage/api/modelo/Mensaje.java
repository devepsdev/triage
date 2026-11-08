package dev.deveps.triage.api.modelo;

import java.util.Optional;

/** rol: system, user, assistant o tool. idLlamada solo en los resultados de herramienta. */
public record Mensaje(String rol, String contenido, Optional<LlamadaFuncion> llamada, Optional<String> idLlamada) {}
