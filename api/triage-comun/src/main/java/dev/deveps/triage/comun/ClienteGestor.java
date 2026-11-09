package dev.deveps.triage.comun;

import java.io.IOException;
import java.io.InputStream;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Cliente del gestor por socket local. Una conexión por petición. */
public final class ClienteGestor {

    private final Path socket;

    public ClienteGestor(Path socket) {
        this.socket = socket;
    }

    public String pedir(String operacion, String... argumentos) throws GestorException {
        String linea = argumentos.length == 0 ? operacion : operacion + " " + String.join(" ", argumentos);
        try (SocketChannel canal = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            ByteBuffer salida = ByteBuffer.wrap((linea + "\n").getBytes(StandardCharsets.UTF_8));
            while (salida.hasRemaining()) {
                canal.write(salida);
            }
            try (InputStream entrada = Channels.newInputStream(canal)) {
                return interpretar(entrada.readAllBytes());
            }
        } catch (IOException e) {
            throw new GestorException("conexion");
        }
    }

    private static String interpretar(byte[] datos) throws GestorException {
        String texto = new String(datos, StandardCharsets.UTF_8);
        int salto = texto.indexOf('\n');
        if (salto < 0) {
            throw new GestorException("protocolo");
        }
        String cabecera = texto.substring(0, salto);
        if (cabecera.startsWith("ERR ")) {
            throw new GestorException(cabecera.substring(4));
        }
        if (!cabecera.startsWith("OK ")) {
            throw new GestorException("protocolo");
        }
        return texto.substring(salto + 1);
    }
}
