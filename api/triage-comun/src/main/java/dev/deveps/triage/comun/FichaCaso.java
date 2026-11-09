package dev.deveps.triage.comun;

import java.util.Optional;
import java.util.Set;

/** Lo que debe concluir el agente en un caso. Nunca se le envía al modelo. */
public record FichaCaso(Caso caso, int variantes, Veredicto veredicto, Causa causa, Remedio remedio,
                        Set<String> herramientasPrueba, Optional<String> lineaTrampa) {}
