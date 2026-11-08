package dev.deveps.triage.api.modelo;

import java.util.Map;

public record DefinicionFuncion(String nombre, String descripcion, Map<String, Object> esquema) {}
