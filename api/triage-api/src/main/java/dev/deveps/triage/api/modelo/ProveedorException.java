package dev.deveps.triage.api.modelo;

/** El mensaje nunca incluye el cuerpo de la respuesta ni la clave. */
public class ProveedorException extends Exception {
    private final boolean recuperable;

    public ProveedorException(String mensaje, boolean recuperable) {
        super(mensaje);
        this.recuperable = recuperable;
    }

    /** true para 429, 5xx y tiempo agotado: merece un reintento. */
    public boolean recuperable() {
        return recuperable;
    }
}
