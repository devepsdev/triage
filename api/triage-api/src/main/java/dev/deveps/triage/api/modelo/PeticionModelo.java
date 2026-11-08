package dev.deveps.triage.api.modelo;

import java.util.List;

public record PeticionModelo(String version, List<Mensaje> mensajes, List<DefinicionFuncion> funciones, int maxTokensSalida) {}
