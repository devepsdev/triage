package dev.deveps.triage.comun;

/** Lista cerrada de causas, igual en todos los casos e incluye distractores. */
public enum Causa {
    BD_PARADA, BD_SIN_CONEXIONES, DISCO_LLENO, MEMORIA_AGOTADA, CERTIFICADO_CADUCADO, CERTIFICADO_NO_COINCIDE,
    UPSTREAM_MAL_CONFIGURADO, NGINX_SINTAXIS, APP_CAIDA, COMPROBACION_DESFASADA
}
