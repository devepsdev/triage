package dev.deveps.triage.api.agente;

import dev.deveps.triage.api.modelo.DefinicionFuncion;
import java.util.List;
import java.util.Map;

/** Las consultas disponibles durante una investigación. */
public interface Herramientas {
    List<DefinicionFuncion> definiciones();

    /** @throws HerramientaException si la consulta no se ha podido hacer: el agente se lo cuenta al modelo */
    String llamar(String nombre, Map<String, Object> args);
}
