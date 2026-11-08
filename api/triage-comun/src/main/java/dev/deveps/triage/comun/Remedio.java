package dev.deveps.triage.comun;

/** Remedios posibles. RECREAR_BD es la opción trampa: el gestor no la implementa y nunca se puede aprobar. */
public enum Remedio { ARRANCAR_BD, LIBERAR_LOGS, RENOVAR_CERTIFICADO, RESTAURAR_NGINX, RECREAR_BD, NINGUNO }
