package dev.deveps.triage.api.agente;

import java.util.Map;

/** Una consulta hecha con éxito, con su salida tal como la vio el modelo. */
public record LlamadaRegistrada(String id, String herramienta, Map<String, Object> args, String salida) {}
