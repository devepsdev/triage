package dev.deveps.triage.api.modelo;

import java.util.Optional;

public record RespuestaModelo(Optional<LlamadaFuncion> llamada, String texto, int tokensEntrada, int tokensSalida, String versionDevuelta) {}
