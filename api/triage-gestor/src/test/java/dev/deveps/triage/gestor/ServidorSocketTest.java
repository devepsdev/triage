package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.deveps.triage.comun.ClienteGestor;
import dev.deveps.triage.comun.GestorException;
import java.io.Closeable;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ServidorSocketTest {

    private Path socket;
    private ServidorSocket servidor;
    private final List<Closeable> abiertos = new CopyOnWriteArrayList<>();

    @BeforeEach
    void arrancar() throws Exception {
        socket = Files.createTempDirectory("tg").resolve("s.sock");
        servidor = new ServidorSocket(socket, new Protocolo(Map.of("eco", args -> "ñandú " + args.getFirst())));
        servidor.arrancar();
    }

    @AfterEach
    void parar() throws Exception {
        servidor.close();
        for (Closeable abierto : abiertos) {
            abierto.close();
        }
    }

    @Test
    void cliente_y_servidor_se_entienden_con_texto_utf8() throws Exception {
        assertThat(new ClienteGestor(socket).pedir("eco", "hola")).isEqualTo("ñandú hola");
    }

    @Test
    void miles_de_conexiones_seguidas_reciben_respuesta() throws Exception {
        for (int i = 0; i < 5000; i++) {
            assertThat(new ClienteGestor(socket).pedir("eco", "hola")).isEqualTo("ñandú hola");
        }
    }

    /** Un servidor a medida: contesta lo indicado y deja la conexión abierta o la cierra. */
    private Path servidorCrudo(String respuesta, boolean cerrar) throws Exception {
        Path ruta = Files.createTempDirectory("tc").resolve("c.sock");
        ServerSocketChannel escucha = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        escucha.bind(UnixDomainSocketAddress.of(ruta));
        abiertos.add(escucha);
        Thread.ofPlatform().daemon().start(() -> {
            try {
                SocketChannel cliente = escucha.accept();
                abiertos.add(cliente);
                cliente.read(ByteBuffer.allocate(512));
                cliente.write(ByteBuffer.wrap(respuesta.getBytes(StandardCharsets.UTF_8)));
                if (cerrar) {
                    cliente.close();
                }
            } catch (IOException e) {
                // la prueba ya ha terminado
            }
        });
        return ruta;
    }

    @Test
    @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void el_cliente_no_espera_al_cierre_si_ya_tiene_los_bytes_anunciados() throws Exception {
        assertThat(new ClienteGestor(servidorCrudo("OK 4\nhola", false)).pedir("eco")).isEqualTo("hola");
    }

    @Test
    void una_respuesta_mas_corta_de_lo_anunciado_es_error_de_protocolo() throws Exception {
        Path ruta = servidorCrudo("OK 10\nhola", true);

        assertThatThrownBy(() -> new ClienteGestor(ruta).pedir("eco"))
                .isInstanceOfSatisfying(GestorException.class, e -> assertThat(e.codigo()).isEqualTo("protocolo"));
    }

    @Test
    void una_longitud_que_no_es_un_numero_es_error_de_protocolo() throws Exception {
        Path ruta = servidorCrudo("OK xx\nhola", false);

        assertThatThrownBy(() -> new ClienteGestor(ruta).pedir("eco"))
                .isInstanceOfSatisfying(GestorException.class, e -> assertThat(e.codigo()).isEqualTo("protocolo"));
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
