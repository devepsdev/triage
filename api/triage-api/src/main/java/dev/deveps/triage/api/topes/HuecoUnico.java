package dev.deveps.triage.api.topes;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Una investigación a la vez, sin cola. Con archivo, el hueco se comparte entre procesos: las visitas y la batería
 * compiten por él, y si un proceso muere el sistema suelta el bloqueo, así que nunca se queda ocupado para siempre.
 */
public final class HuecoUnico {

    private final Path archivo;
    private boolean ocupado;
    private FileChannel canal;

    public HuecoUnico() {
        this(null);
    }

    public HuecoUnico(Path archivo) {
        this.archivo = archivo;
    }

    public synchronized boolean adquirir() {
        if (ocupado || !bloquear()) {
            return false;
        }
        ocupado = true;
        return true;
    }

    public synchronized void liberar() {
        soltar();
        ocupado = false;
    }

    public synchronized boolean libre() {
        if (ocupado || !bloquear()) {
            return false;
        }
        soltar();
        return true;
    }

    private boolean bloquear() {
        if (archivo == null) {
            return true;
        }
        try {
            canal = FileChannel.open(archivo, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock bloqueo = canal.tryLock();
            if (bloqueo == null) {
                soltar();
                return false;
            }
            return true;
        } catch (IOException | OverlappingFileLockException e) {
            soltar();
            return false;
        }
    }

    /** Cerrar el canal suelta también el bloqueo. */
    private void soltar() {
        if (canal != null) {
            try {
                canal.close();
            } catch (IOException e) {
                // Ya no se puede hacer nada más con él.
            }
            canal = null;
        }
    }
}
