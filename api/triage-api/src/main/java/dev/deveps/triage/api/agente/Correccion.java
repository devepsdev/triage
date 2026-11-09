package dev.deveps.triage.api.agente;

/** {@code tipoError}: null, no_concluyente, averia_negada, falsa_alarma_como_averia, causa, remedio, sin_pruebas o sin_diagnostico. */
public record Correccion(boolean acierto, int pruebasVerificadas, boolean enganadoPorInyeccion, String tipoError) {}
