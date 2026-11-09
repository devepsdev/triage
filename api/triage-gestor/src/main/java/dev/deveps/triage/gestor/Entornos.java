package dev.deveps.triage.gestor;

import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.gestor.ComandosDocker.Contenedor;
import java.time.Duration;
import java.util.List;

/** Crea y destruye entornos. Si algo falla a medias, no deja nada detrás. */
final class Entornos {

    private static final Duration LIMITE_ORDEN = Duration.ofSeconds(30);
    private static final Duration LIMITE_SONDA = Duration.ofSeconds(5);
    private static final int INTENTOS_TIENDA = 60;

    private final Registro registro;
    private final Ejecutor docker;
    private final Duration pausa;

    Entornos(Registro registro, Ejecutor docker, Duration pausa) {
        this.registro = registro;
        this.docker = docker;
        this.pausa = pausa;
    }

    Entorno crear(Caso caso, int variante) throws ErrorOperacion {
        Entorno entorno = registro.registrar(caso, variante);
        try {
            levantar(entorno);
            esperarTienda(entorno);
            return entorno;
        } catch (ErrorOperacion e) {
            limpiar(entorno.id());
            throw e;
        }
    }

    void destruir(String id) throws ErrorOperacion {
        existente(id);
        limpiar(id);
    }

    Entorno existente(String id) throws ErrorOperacion {
        return registro.porId(id).orElseThrow(() -> new ErrorOperacion("id"));
    }

    void destruirVencidos() {
        registro.vencidos().forEach(e -> limpiar(e.id()));
    }

    private void levantar(Entorno entorno) throws ErrorOperacion {
        long caduca = entorno.caduca().getEpochSecond();
        ordenar(ComandosDocker.crearRed(entorno.id(), caduca));
        // db y app antes que web: nginx resuelve el nombre de la app al arrancar.
        for (Contenedor c : List.of(Contenedor.DB, Contenedor.APP, Contenedor.WEB, Contenedor.SONDA)) {
            ordenar(ComandosDocker.arrancar(entorno.id(), caduca, c));
        }
    }

    private void ordenar(List<String> argv) throws ErrorOperacion {
        if (!docker.ejecutar(argv, LIMITE_ORDEN).correcto()) {
            throw new ErrorOperacion("docker");
        }
    }

    private void esperarTienda(Entorno entorno) throws ErrorOperacion {
        for (int i = 0; i < INTENTOS_TIENDA; i++) {
            if (docker.ejecutar(ComandosDocker.tiendaLista(entorno.id()), LIMITE_SONDA).correcto()) {
                return;
            }
            try {
                Thread.sleep(pausa);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new ErrorOperacion("arranque");
    }

    private void limpiar(String id) {
        docker.ejecutar(ComandosDocker.borrarContenedores(id), LIMITE_ORDEN);
        docker.ejecutar(ComandosDocker.borrarRed(id), LIMITE_ORDEN);
        registro.quitar(id);
    }
}
