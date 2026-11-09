package dev.deveps.triage.api.investigacion;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Los eventos de una investigación. Quien mira lee a su ritmo desde su propio hilo, así que un cliente lento
 * no frena al agente, y reconectar es volver a leer desde el último evento recibido.
 */
public final class CanalEventos {

    public record Evento(long id, String tipo, Map<String, Object> datos) {}

    public interface Emisor {
        void evento(Evento evento) throws IOException;

        /** Sirve para descubrir que el visitante se ha ido aunque no haya eventos que mandarle. */
        void latido() throws IOException;
    }

    private final ScheduledExecutorService programador;
    private final Duration esperaSinPublico;
    private final Duration latido;
    private final Runnable sinPublico;
    private final List<Evento> eventos = new ArrayList<>();
    private int suscriptores;
    private long generacion;
    private boolean cerrado;

    public CanalEventos(ScheduledExecutorService programador, Duration esperaSinPublico, Duration latido, Runnable sinPublico) {
        this.programador = programador;
        this.esperaSinPublico = esperaSinPublico;
        this.latido = latido;
        this.sinPublico = sinPublico;
    }

    /** Empieza a contar: si nadie llega a conectarse, también se corta. */
    public synchronized void vigilar() {
        programarCorte();
    }

    public synchronized void emitir(String tipo, Map<String, Object> datos) {
        eventos.add(new Evento(eventos.size() + 1L, tipo, datos));
        notifyAll();
    }

    public synchronized void cerrar() {
        cerrado = true;
        notifyAll();
    }

    public synchronized List<Evento> eventos() {
        return List.copyOf(eventos);
    }

    /** Bloquea hasta que termine la investigación o el visitante se vaya. {@code desde} es el último id ya recibido. */
    public void seguir(long desde, Emisor emisor) {
        entrar();
        try {
            int posicion = (int) Math.max(desde, 0);
            while (true) {
                List<Evento> nuevos;
                boolean terminado;
                synchronized (this) {
                    if (posicion >= eventos.size() && !cerrado) {
                        wait(latido.toMillis());
                    }
                    nuevos = posicion < eventos.size() ? List.copyOf(eventos.subList(posicion, eventos.size())) : List.of();
                    terminado = cerrado;
                }
                if (nuevos.isEmpty() && !terminado) {
                    emisor.latido();
                }
                for (Evento e : nuevos) {
                    emisor.evento(e);
                }
                posicion += nuevos.size();
                if (terminado) {
                    return;
                }
            }
        } catch (IOException | RuntimeException e) {
            // El visitante se ha ido: puede volver con su último id.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            salir();
        }
    }

    private synchronized void entrar() {
        suscriptores++;
        generacion++;
    }

    private synchronized void salir() {
        suscriptores--;
        generacion++;
        if (suscriptores == 0 && !cerrado) {
            programarCorte();
        }
    }

    private void programarCorte() {
        long esta = generacion;
        programador.schedule(() -> cortarSiSigueSinPublico(esta), esperaSinPublico.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void cortarSiSigueSinPublico(long programada) {
        boolean cortar;
        synchronized (this) {
            cortar = generacion == programada && suscriptores == 0 && !cerrado;
        }
        if (cortar) {
            sinPublico.run();
        }
    }
}
