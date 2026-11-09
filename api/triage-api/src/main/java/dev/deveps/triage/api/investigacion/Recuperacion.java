package dev.deveps.triage.api.investigacion;

import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.comun.GestorException;
import java.time.Clock;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/** Al arrancar no puede haber nada en marcha: lo que figure activo es de un proceso que murió. */
public final class Recuperacion implements ApplicationRunner {

    private final RepositorioInvestigacion repositorio;
    private final ServicioInvestigacion.Gestor gestor;
    private final ControlGasto gasto;
    private final Clock reloj;

    public Recuperacion(RepositorioInvestigacion repositorio, ServicioInvestigacion.Gestor gestor, ControlGasto gasto, Clock reloj) {
        this.repositorio = repositorio;
        this.gestor = gestor;
        this.gasto = gasto;
        this.reloj = reloj;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        // La batería es otro proceso: la API puede estar atendiendo a alguien y no hay que tocar lo suyo.
        if (!argumentos.containsOption("bateria")) {
            recuperar();
        }
    }

    public void recuperar() {
        gasto.limpiarReservas();
        for (RepositorioInvestigacion.Activa activa : repositorio.activas()) {
            if (activa.idEntorno() != null) {
                try {
                    gestor.pedir("destruir", activa.idEntorno());
                } catch (GestorException yaNoExiste) {
                    // Puede que ya no exista: el gestor los borra por caducidad.
                }
            }
            repositorio.terminar(activa.id(), EstadoInvestigacion.FALLIDA, "reinicio", reloj.instant());
        }
    }
}
