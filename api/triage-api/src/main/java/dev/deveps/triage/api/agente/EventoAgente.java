package dev.deveps.triage.api.agente;

import java.util.Map;

/** Lo que se va viendo de una investigación mientras ocurre. */
public sealed interface EventoAgente {

    record Paso(int numero, String id, String herramienta, Map<String, Object> args, String salida, String encontrado, String descartado) implements EventoAgente {}

    record Consumo(int tokensEntrada, int tokensSalida, String version) implements EventoAgente {}

    record Fin(String motivo) implements EventoAgente {}
}
