package dev.deveps.triage.comun;

/** El gestor rechazó la petición o no se pudo hablar con él. */
public final class GestorException extends Exception {
    private final String codigo;

    public GestorException(String codigo) {
        super("Gestor: " + codigo);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }
}
