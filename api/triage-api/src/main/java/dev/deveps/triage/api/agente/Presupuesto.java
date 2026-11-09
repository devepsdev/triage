package dev.deveps.triage.api.agente;

/** Se reserva el máximo antes de cada llamada al modelo y se liquida con lo que costó de verdad. */
public interface Presupuesto {
    boolean reservar(int tokensMax);

    /** Devuelve lo reservado. Con 0 y 0 si la llamada falló. */
    void liquidar(int entrada, int salida, String version);
}
