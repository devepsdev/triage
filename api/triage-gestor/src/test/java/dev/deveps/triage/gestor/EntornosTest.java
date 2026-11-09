package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.deveps.triage.comun.Caso;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntornosTest {

    private final EjecutorFalso docker = new EjecutorFalso();
    private final Registro registro = new Registro(Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(6));
    private final Entornos entornos = new Entornos(registro, docker, Duration.ZERO);

    @Test
    void crear_levanta_la_red_antes_que_los_cuatro_contenedores() throws Exception {
        entornos.crear(Caso.A1_BD_CAIDA, 1);

        assertThat(docker.ordenes.getFirst()).startsWith("docker", "network", "create");
        assertThat(docker.ordenesCon("run")).hasSize(4);
    }

    @Test
    void crear_no_devuelve_hasta_que_la_tienda_responde() throws Exception {
        int[] intentos = {0};
        docker.respuesta = argv -> argv.contains("curl") && ++intentos[0] < 3 ? new Resultado(22, "") : new Resultado(0, "");

        entornos.crear(Caso.A1_BD_CAIDA, 1);

        assertThat(intentos[0]).isEqualTo(3);
    }

    @Test
    void si_falla_un_contenedor_limpia_lo_creado_y_olvida_el_entorno() {
        docker.respuesta = argv -> argv.contains("run") && argv.contains("triage/db") ? new Resultado(125, "error") : new Resultado(0, "");

        assertThatThrownBy(() -> entornos.crear(Caso.A1_BD_CAIDA, 1)).isInstanceOf(ErrorOperacion.class);

        assertThat(docker.ordenes).anyMatch(o -> o.subList(0, 2).equals(List.of("docker", "rm")));
        assertThat(docker.ordenes).anyMatch(o -> o.subList(0, 3).equals(List.of("docker", "network", "rm")));
        assertThat(registro.vencidos()).isEmpty();
    }

    @Test
    void si_la_tienda_nunca_responde_tambien_limpia() {
        docker.respuesta = argv -> argv.contains("curl") ? new Resultado(22, "") : new Resultado(0, "");

        assertThatThrownBy(() -> entornos.crear(Caso.A1_BD_CAIDA, 1)).isInstanceOf(ErrorOperacion.class);

        assertThat(docker.ordenes).anyMatch(o -> o.subList(0, 3).equals(List.of("docker", "network", "rm")));
    }

    @Test
    void destruir_borra_contenedores_y_red_y_olvida_el_token() throws Exception {
        Entorno e = entornos.crear(Caso.A1_BD_CAIDA, 1);

        entornos.destruir(e.id());

        assertThat(docker.ordenesCon("rm")).hasSize(2);
        assertThat(registro.porToken(e.token())).isEmpty();
    }

    @Test
    void los_entornos_vencidos_se_destruyen() throws Exception {
        Registro yaVencido = new Registro(Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(-1));
        Entornos conVencidos = new Entornos(yaVencido, docker, Duration.ZERO);
        Entorno e = conVencidos.crear(Caso.A1_BD_CAIDA, 1);

        conVencidos.destruirVencidos();

        assertThat(yaVencido.porId(e.id())).isEmpty();
        assertThat(docker.ordenes).anyMatch(o -> o.subList(0, 3).equals(List.of("docker", "network", "rm")));
    }

    @Test
    void destruir_un_id_desconocido_da_error_id() {
        assertThatThrownBy(() -> entornos.destruir("nada"))
                .isInstanceOfSatisfying(ErrorOperacion.class, e -> assertThat(e.codigo()).isEqualTo("id"));
        assertThat(docker.ordenes).isEmpty();
    }
}
