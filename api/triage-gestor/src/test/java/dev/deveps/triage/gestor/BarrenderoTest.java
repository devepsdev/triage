package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BarrenderoTest {

    private static final Instant AHORA = Instant.ofEpochSecond(2_000);

    private final EjecutorFalso docker = new EjecutorFalso();
    private final Barrendero barrendero = new Barrendero(docker);

    private void conRecursos(String contenedores, String redes) {
        docker.respuesta = argv -> {
            if (argv.contains("ps")) return new Resultado(0, contenedores);
            if (argv.contains("ls")) return new Resultado(0, redes);
            return new Resultado(0, "");
        };
    }

    @Test
    void solo_lista_recursos_con_la_etiqueta_triage_env() {
        conRecursos("", "");

        barrendero.barrerTodo();

        assertThat(docker.ordenes).hasSize(2).allSatisfy(o -> assertThat(o).contains("--filter", "label=triage.env"));
    }

    @Test
    void al_arrancar_borra_todo_lo_suyo_primero_contenedores() {
        conRecursos("c1 3000\nc2 1000\n", "r1 3000\n");

        barrendero.barrerTodo();

        assertThat(docker.ordenes.get(1)).isEqualTo(List.of("docker", "rm", "-f", "c1", "c2"));
        assertThat(docker.ordenes.getLast()).isEqualTo(List.of("docker", "network", "rm", "r1"));
    }

    @Test
    void el_barrido_periodico_respeta_los_que_siguen_vivos() {
        conRecursos("vivo 3000\nviejo 1000\n", "redviva 3000\nredvieja 1000\n");

        barrendero.barrerCaducados(AHORA);

        assertThat(docker.ordenes).contains(List.of("docker", "rm", "-f", "viejo"), List.of("docker", "network", "rm", "redvieja"));
        assertThat(docker.ordenes).noneMatch(o -> o.contains("vivo") || o.contains("redviva"));
    }

    @Test
    void sin_recursos_no_lanza_borrados() {
        conRecursos("\n", "");

        barrendero.barrerTodo();

        assertThat(docker.ordenes).noneMatch(o -> o.contains("rm"));
    }

    @Test
    void una_etiqueta_de_caducidad_ilegible_se_trata_como_caducada() {
        conRecursos("raro basura\n", "");

        barrendero.barrerCaducados(AHORA);

        assertThat(docker.ordenes).contains(List.of("docker", "rm", "-f", "raro"));
    }
}
