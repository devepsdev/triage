package dev.deveps.triage.api.topes;

import java.util.concurrent.atomic.AtomicBoolean;

/** Una investigación a la vez, sin cola: las visitas y la batería compiten por este mismo hueco. */
public final class HuecoUnico {

    private final AtomicBoolean ocupado = new AtomicBoolean();

    public boolean adquirir() {
        return ocupado.compareAndSet(false, true);
    }

    public void liberar() {
        ocupado.set(false);
    }

    public boolean libre() {
        return !ocupado.get();
    }
}
