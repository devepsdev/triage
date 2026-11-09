package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.deveps.triage.comun.ClienteGestor;
import dev.deveps.triage.comun.GestorException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServidorSocketTest {

    private Path socket;
    private ServidorSocket servidor;

    @BeforeEach
    void arrancar() throws Exception {
        socket = Files.createTempDirectory("tg").resolve("s.sock");
        servidor = new ServidorSocket(socket, new Protocolo(Map.of("eco", args -> "ñandú " + args.getFirst())));
        servidor.arrancar();
    }

    @AfterEach
    void parar() throws Exception {
        servidor.close();
    }

    @Test
    void cliente_y_servidor_se_entienden_con_texto_utf8() throws Exception {
        assertThat(new ClienteGestor(socket).pedir("eco", "hola")).isEqualTo("ñandú hola");
    }

    @Test
    void los_errores_llegan_como_codigo() {
        assertThatThrownBy(() -> new ClienteGestor(socket).pedir("otra"))
                .isInstanceOfSatisfying(GestorException.class, e -> assertThat(e.codigo()).isEqualTo("op"));
    }

    @Test
    void una_peticion_demasiado_larga_se_rechaza() {
        assertThatThrownBy(() -> new ClienteGestor(socket).pedir("eco", "x".repeat(5000)))
                .isInstanceOfSatisfying(GestorException.class, e -> assertThat(e.codigo()).isEqualTo("arg"));
    }
}
