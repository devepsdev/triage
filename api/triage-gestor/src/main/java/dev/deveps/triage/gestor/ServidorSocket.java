package dev.deveps.triage.gestor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/** Atiende un socket local: una petición por conexión, cada una en su hilo virtual. */
final class ServidorSocket implements AutoCloseable {

    private static final int MAX_LINEA = 1024;
    private static final int MAX_LECTURA = 1024 * 1024;

    private final Path ruta;
    private final Protocolo protocolo;
    private final Optional<String> grupo;
    private ServerSocketChannel canal;

    ServidorSocket(Path ruta, Protocolo protocolo) {
        this(ruta, protocolo, Optional.empty());
    }

    ServidorSocket(Path ruta, Protocolo protocolo, Optional<String> grupo) {
        this.ruta = ruta;
        this.protocolo = protocolo;
        this.grupo = grupo;
    }

    void arrancar() throws IOException {
        Files.deleteIfExists(ruta);
        canal = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        canal.bind(UnixDomainSocketAddress.of(ruta));
        restringirPermisos();
        Thread.ofVirtual().start(this::aceptar);
    }

    private void restringirPermisos() throws IOException {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return; // desarrollo en Windows; en el servidor siempre es POSIX
        }
        Files.setPosixFilePermissions(ruta, PosixFilePermissions.fromString("rw-rw----"));
        if (grupo.isPresent()) {
            GroupPrincipal principal = ruta.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(grupo.get());
            Files.getFileAttributeView(ruta, PosixFileAttributeView.class).setGroup(principal);
        }
    }

    private void aceptar() {
        try {
            while (true) {
                SocketChannel cliente = canal.accept();
                Thread.ofVirtual().start(() -> atender(cliente));
            }
        } catch (ClosedChannelException e) {
            // cierre normal del servidor
        } catch (IOException e) {
            System.err.println("El socket dejó de aceptar conexiones");
        }
    }

    private void atender(SocketChannel cliente) {
        try (cliente) {
            Optional<String> linea = leerLinea(cliente);
            String respuesta = linea.map(protocolo::atender).orElse("ERR arg\n");
            ByteBuffer salida = ByteBuffer.wrap(respuesta.getBytes(StandardCharsets.UTF_8));
            while (salida.hasRemaining()) {
                cliente.write(salida);
            }
        } catch (IOException e) {
            // el cliente se fue: no hay a quién contestar
        }
    }

    /** Lee hasta el salto de línea. Vacía lo que sobre para que el cliente pueda terminar de escribir y leer la respuesta. */
    private static Optional<String> leerLinea(SocketChannel cliente) throws IOException {
        ByteArrayOutputStream datos = new ByteArrayOutputStream();
        ByteBuffer bloque = ByteBuffer.allocate(512);
        int total = 0;
        boolean fin = false;
        while (!fin && total < MAX_LECTURA && cliente.read(bloque) >= 0) {
            bloque.flip();
            while (bloque.hasRemaining() && !fin) {
                byte b = bloque.get();
                total++;
                if (b == '\n') {
                    fin = true;
                } else if (datos.size() <= MAX_LINEA) {
                    datos.write(b);
                }
            }
            bloque.clear();
        }
        return fin && datos.size() <= MAX_LINEA ? Optional.of(datos.toString(StandardCharsets.UTF_8)) : Optional.empty();
    }

    @Override
    public void close() throws IOException {
        if (canal != null) {
            canal.close();
        }
        Files.deleteIfExists(ruta);
    }
}
