package dev.deveps.triage.api.agente;

import dev.deveps.triage.comun.Causa;
import dev.deveps.triage.comun.Remedio;
import dev.deveps.triage.comun.Veredicto;
import java.util.List;

/** Resultado estructurado. {@code causa} es null solo si el veredicto es NO_CONCLUYENTE. */
public record Diagnostico(Veredicto veredicto, Causa causa, List<Prueba> pruebas, String descartado, Remedio remedio, String explicacion) {}
