package dev.deveps.triage.api.agente;

/** La consulta ha fallado. El mensaje lo lee el modelo: no debe llevar detalles internos. */
public class HerramientaException extends RuntimeException {
    public HerramientaException(String mensaje) {
        super(mensaje);
    }
}
