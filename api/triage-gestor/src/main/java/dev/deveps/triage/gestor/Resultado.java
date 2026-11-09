package dev.deveps.triage.gestor;

/** Código de salida y texto combinado de una orden. 124 significa que superó su tiempo. */
record Resultado(int salida, String texto) {

    boolean correcto() {
        return salida == 0;
    }
}
