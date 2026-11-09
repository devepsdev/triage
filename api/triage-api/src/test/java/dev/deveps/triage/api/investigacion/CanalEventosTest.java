package dev.deveps.triage.api.investigacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CanalEventosTest {

    private final ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger cortes = new AtomicInteger();

    @AfterEach
    void parar() {
        programador.shutdownNow();
    }

    private CanalEventos canal(Duration espera) {
        return new CanalEventos(programador, espera, Duration.ofMillis(20), cortes::incrementAndGet);
    }

    /** Un visitante: recoge lo que le llega hasta que se le dice que se vaya. */
    private static final class Visitante implements CanalEventos.Emisor {
        final List<Long> ids = new CopyOnWriteArrayList<>();
        final AtomicBoolean seVa = new AtomicBoolean();

        @Override
        public void evento(CanalEventos.Evento e) throws IOException {
            comprobar();
            ids.add(e.id());
        }

        @Override
        public void latido() throws IOException {
            comprobar();
        }

        private void comprobar() throws IOException {
            if (seVa.get()) {
                throw new IOException("se ha ido");
            }
        }
    }

    private static Thread seguir(CanalEventos canal, long desde, Visitante v) {
        return Thread.startVirtualThread(() -> canal.seguir(desde, v));
    }

    @Test
    void entrega_en_orden_y_termina_al_cerrar() throws Exception {
        CanalEventos canal = canal(Duration.ofMinutes(1));
        Visitante v = new Visitante();
        Thread hilo = seguir(canal, 0, v);

        canal.emitir("a", Map.of());
        canal.emitir("b", Map.of());
        canal.cerrar();
        hilo.join(2000);

        assertThat(hilo.isAlive()).isFalse();
        assertThat(v.ids).containsExactly(1L, 2L);
    }

    @Test
    void la_reconexion_reenvia_desde_el_ultimo_evento_y_no_corta() {
        CanalEventos canal = canal(Duration.ofMillis(300));
        Visitante primero = new Visitante();
        seguir(canal, 0, primero);
        canal.emitir("a", Map.of());
        canal.emitir("b", Map.of());
        await().until(() -> primero.ids.size() == 2);
        primero.seVa.set(true);
        await().pollDelay(Duration.ofMillis(60)).until(() -> true);

        Visitante segundo = new Visitante();
        seguir(canal, 2, segundo);
        canal.emitir("c", Map.of());

        await().until(() -> segundo.ids.size() == 1);
        assertThat(segundo.ids).containsExactly(3L);
        await().pollDelay(Duration.ofMillis(400)).until(() -> true);
        assertThat(cortes).hasValue(0);
        canal.cerrar();
    }

    @Test
    void sin_nadie_durante_la_espera_corta_una_sola_vez() {
        CanalEventos canal = canal(Duration.ofMillis(80));
        Visitante v = new Visitante();
        seguir(canal, 0, v);
        canal.emitir("a", Map.of());
        await().until(() -> v.ids.size() == 1);

        v.seVa.set(true);

        await().atMost(Duration.ofSeconds(2)).until(() -> cortes.get() == 1);
        await().pollDelay(Duration.ofMillis(200)).until(() -> true);
        assertThat(cortes).hasValue(1);
    }

    @Test
    void si_nadie_llega_a_conectarse_tambien_corta() {
        canal(Duration.ofMillis(50)).vigilar();

        await().atMost(Duration.ofSeconds(2)).until(() -> cortes.get() == 1);
    }

    @Test
    void conectarse_a_tiempo_evita_el_corte_inicial() {
        CanalEventos canal = canal(Duration.ofMillis(100));
        canal.vigilar();
        seguir(canal, 0, new Visitante());

        await().pollDelay(Duration.ofMillis(250)).until(() -> true);

        assertThat(cortes).hasValue(0);
        canal.cerrar();
    }

    @Test
    void una_vez_cerrado_no_corta() {
        CanalEventos canal = canal(Duration.ofMillis(50));
        Visitante v = new Visitante();
        canal.cerrar();

        canal.seguir(0, v);

        await().pollDelay(Duration.ofMillis(150)).until(() -> true);
        assertThat(cortes).hasValue(0);
    }
}
