package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProtocoloTest {

    private final EjecutorFalso docker = new EjecutorFalso();
    private final Registro registro = new Registro(Clock.systemUTC(), Duration.ofMinutes(6));
    private final Protocolo control = Protocolo.control(new Entornos(registro, docker, Duration.ZERO));
    private final Protocolo lectura = new Protocolo(Map.of("listar_servicios", args -> "estado " + String.join(",", args)));

    @Test
    void operacion_correcta_devuelve_ok_con_longitud_en_bytes() {
        assertThat(lectura.atender("listar_servicios tok")).isEqualTo("OK 10\nestado tok");
    }

    @Test
    void operacion_desconocida_en_cada_socket() {
        assertThat(control.atender("borrar_todo")).isEqualTo("ERR op\n");
        assertThat(lectura.atender("borrar_todo")).isEqualTo("ERR op\n");
    }

    @Test
    void crear_por_el_socket_de_lectura_no_existe() {
        assertThat(lectura.atender("crear A1_BD_CAIDA 1")).isEqualTo("ERR op\n");
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void consulta_por_el_socket_de_control_no_existe() {
        assertThat(control.atender("listar_servicios tok")).isEqualTo("ERR op\n");
    }

    @Test
    void crear_devuelve_id_y_token() {
        assertThat(control.atender("crear A1_BD_CAIDA 1")).matches("(?s)OK \\d+\n[0-9a-f]{12} [0-9a-f]{64}");
    }

    @Test
    void argumentos_fuera_de_lista_no_llegan_a_docker() {
        for (String linea : List.of("crear NO_EXISTE 1", "crear A1_BD_CAIDA 99", "crear A1_BD_CAIDA 0", "crear A1_BD_CAIDA x", "crear A1_BD_CAIDA", "crear A1_BD_CAIDA 1 extra")) {
            assertThat(control.atender(linea)).as(linea).isEqualTo("ERR arg\n");
        }
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void caracteres_peligrosos_se_rechazan_antes_de_buscar_la_operacion() {
        for (String linea : List.of("destruir ../../etc", "destruir a;rm", "destruir $(x)", "destruir a b|c", "")) {
            assertThat(control.atender(linea)).as(linea).isEqualTo("ERR arg\n");
        }
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void un_fallo_inesperado_no_filtra_detalles() {
        Protocolo roto = new Protocolo(Map.of("op", args -> { throw new IllegalStateException("/ruta/secreta"); }));

        assertThat(roto.atender("op")).isEqualTo("ERR interno\n");
    }
}
