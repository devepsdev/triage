package dev.deveps.triage.comun;

import java.io.ByteArrayOutputStream;
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

    private static final int MAX_CABECERA = 128;

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
                return leerRespuesta(entrada);
            }
        } catch (IOException e) {
            throw new GestorException("conexion");
        }
    }

    /** Lee los bytes que anuncia la cabecera, sin esperar al cierre: en Windows el aviso de cierre a veces no llega y el cliente se quedaría colgado. */
    private static String leerRespuesta(InputStream entrada) throws IOException, GestorException {
        String cabecera = leerCabecera(entrada);
        if (cabecera.startsWith("ERR ")) {
            throw new GestorException(cabecera.substring(4));
        }
        if (!cabecera.startsWith("OK ")) {
            throw new GestorException("protocolo");
        }
        int longitud = longitud(cabecera.substring(3));
        byte[] cuerpo = entrada.readNBytes(longitud);
        if (cuerpo.length != longitud) {
            throw new GestorException("protocolo");
        }
        return new String(cuerpo, StandardCharsets.UTF_8);
    }

    private static String leerCabecera(InputStream entrada) throws IOException, GestorException {
        ByteArrayOutputStream cabecera = new ByteArrayOutputStream();
        int b;
        while ((b = entrada.read()) != '\n') {
            if (b < 0 || cabecera.size() >= MAX_CABECERA) {
                throw new GestorException("protocolo");
            }
            cabecera.write(b);
        }
        return cabecera.toString(StandardCharsets.UTF_8);
    }

    private static int longitud(String texto) throws GestorException {
        try {
            int valor = Integer.parseInt(texto);
            if (valor < 0) {
                throw new GestorException("protocolo");
            }
            return valor;
        } catch (NumberFormatException e) {
            throw new GestorException("protocolo");
        }
    }
}
