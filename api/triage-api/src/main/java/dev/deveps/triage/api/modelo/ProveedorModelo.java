package dev.deveps.triage.api.modelo;

/** Aísla al proveedor del modelo: cambiar de proveedor es escribir otra implementación de esto. */
public interface ProveedorModelo {
    RespuestaModelo completar(PeticionModelo peticion) throws ProveedorException;
}
