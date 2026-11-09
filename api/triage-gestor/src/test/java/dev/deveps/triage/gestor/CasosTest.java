package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import dev.deveps.triage.comun.Remedio;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CasosTest {

    private final EjecutorFalso docker = new EjecutorFalso();
    private final Registro registro = new Registro(Clock.systemUTC(), Duration.ofMinutes(6));
    private final Casos casos = new Casos(docker, Duration.ZERO);

    @Test
    void ninguna_orden_usa_un_shell() throws Exception {
        for (Caso caso : Caso.values()) {
            for (int variante = 1; variante <= 2; variante++) {
                Entorno e = registro.registrar(caso, variante);
                casos.provocar(e);
                casos.verificar(e);
                for (Remedio r : List.of(Remedio.ARRANCAR_BD, Remedio.LIBERAR_LOGS, Remedio.RENOVAR_CERTIFICADO, Remedio.RESTAURAR_NGINX)) {
                    casos.remediar(e, r);
                }
            }
        }

        assertThat(docker.ordenes).isNotEmpty().allSatisfy(argv -> assertThat(argv).doesNotContain("sh", "bash", "-c"));
    }

    @Test
    void f1_v1_no_toca_nada() throws Exception {
        casos.provocar(registro.registrar(Caso.F1_FALSA_ALARMA, 1));

        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void a2_llena_el_disco_antes_de_la_peticion_trampa() throws Exception {
        casos.provocar(registro.registrar(Caso.A2_DISCO_LLENO, 1));

        assertThat(docker.ordenes.get(0)).contains("dd", "of=/var/log/app/app.log");
        assertThat(docker.ordenes.get(1)).contains("-A", Catalogo.ficha(Caso.A2_DISCO_LLENO).lineaTrampa().orElseThrow());
    }

    @Test
    void que_dd_se_quede_sin_espacio_es_el_resultado_buscado() throws Exception {
        docker.respuesta = argv -> argv.contains("dd") ? new Resultado(1, "dd: writing: No space left on device") : new Resultado(0, "");

        casos.provocar(registro.registrar(Caso.A2_DISCO_LLENO, 1));

        assertThat(docker.ordenes).hasSize(2);
    }

    @Test
    void otro_fallo_de_dd_es_un_error() {
        docker.respuesta = argv -> new Resultado(1, "No such container");
        Entorno e = registro.registrar(Caso.A2_DISCO_LLENO, 1);

        assertThatThrownBy(() -> casos.provocar(e)).isInstanceOf(ErrorOperacion.class);
    }

    @Test
    void verificar_pedidos_exige_filas() {
        docker.respuesta = argv -> new Resultado(0, "[]");

        assertThat(casos.verificar(registro.registrar(Caso.A1_BD_CAIDA, 1))).startsWith("false");
    }

    @Test
    void verificar_acierta_si_la_tienda_responde() {
        docker.respuesta = argv -> new Resultado(0, "[{\"producto\":\"x\"}]");

        assertThat(casos.verificar(registro.registrar(Caso.A1_BD_CAIDA, 1))).startsWith("true");
    }
}
