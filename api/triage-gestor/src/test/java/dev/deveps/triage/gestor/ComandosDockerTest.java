package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.gestor.ComandosDocker.Contenedor;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ComandosDockerTest {

    private static final String ID = "abc123";
    private static final long CADUCA = 1_900_000_000L;

    @Test
    void red_aislada_sin_direccion_en_el_puente() {
        List<String> argv = ComandosDocker.crearRed(ID, CADUCA);

        assertThat(argv).startsWith("docker", "network", "create");
        assertThat(argv).contains("--internal", "-o", "com.docker.network.bridge.gateway_mode_ipv4=isolated", "tabc123-red");
        assertThat(argv).contains("triage.env=abc123", "triage.caduca=1900000000");
    }

    @ParameterizedTest
    @CsvSource({"WEB,64m,0.25,64", "APP,128m,0.25,64", "DB,256m,0.50,128", "SONDA,64m,0.25,32"})
    void contenedor_endurecido_y_limitado(Contenedor c, String memoria, String cpus, String procesos) {
        List<String> argv = ComandosDocker.arrancar(ID, CADUCA, c);

        assertThat(argv).startsWith("docker", "run");
        assertThat(argv).contains("--read-only", "--cap-drop=ALL", "--security-opt", "no-new-privileges",
                "--cgroup-parent=triage.slice", "--restart=no", "--pull=never", "--network", "tabc123-red");
        assertThat(argv).contains("--memory=" + memoria, "--memory-swap=" + memoria, "--cpus=" + cpus, "--pids-limit=" + procesos);
        assertThat(argv).contains("triage.env=abc123", "triage.caduca=1900000000");
    }

    @ParameterizedTest
    @CsvSource({"WEB,web", "APP,app", "DB,db"})
    void el_nombre_no_delata_el_caso_y_el_alias_es_el_servicio(Contenedor c, String alias) {
        List<String> argv = ComandosDocker.arrancar(ID, CADUCA, c);

        assertThat(argv).contains("--name", "tabc123-" + alias, "--network-alias", alias);
    }

    @Test
    void el_tmpfs_de_logs_de_la_app_mide_16_mb() {
        assertThat(ComandosDocker.arrancar(ID, CADUCA, Contenedor.APP))
                .anyMatch(a -> a.startsWith("/var/log/app:") && a.contains("size=16m"));
    }

    @Test
    void ninguna_orden_usa_un_shell() {
        List<List<String>> todas = List.of(
                ComandosDocker.crearRed(ID, CADUCA),
                ComandosDocker.arrancar(ID, CADUCA, Contenedor.WEB),
                ComandosDocker.arrancar(ID, CADUCA, Contenedor.APP),
                ComandosDocker.arrancar(ID, CADUCA, Contenedor.DB),
                ComandosDocker.arrancar(ID, CADUCA, Contenedor.SONDA),
                ComandosDocker.tiendaLista(ID),
                ComandosDocker.borrarContenedores(ID),
                ComandosDocker.borrarRed(ID),
                ComandosDocker.listarContenedoresTriage(),
                ComandosDocker.listarRedesTriage());

        assertThat(todas).allSatisfy(argv -> {
            assertThat(argv.getFirst()).isEqualTo("docker");
            assertThat(argv).doesNotContain("sh", "bash", "-c");
        });
    }
}
