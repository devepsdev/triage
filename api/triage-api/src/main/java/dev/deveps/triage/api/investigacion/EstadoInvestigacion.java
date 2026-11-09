package dev.deveps.triage.api.investigacion;

public enum EstadoInvestigacion {
    PREPARANDO, PROVOCANDO, INVESTIGANDO, PROPUESTA, APROBADA, APLICANDO, VERIFICANDO, RECHAZADA, DESTRUIDA, AGOTADA, FALLIDA;

    public boolean terminal() {
        return this == DESTRUIDA || this == AGOTADA || this == FALLIDA;
    }
}
