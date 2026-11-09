package dev.deveps.triage.gestor;

/** Error previsto de una operación. El código viaja por el socket: no lleva detalles. */
final class ErrorOperacion extends Exception {
    private final String codigo;

    ErrorOperacion(String codigo) {
        super(codigo);
        this.codigo = codigo;
    }

    String codigo() {
        return codigo;
    }
}
