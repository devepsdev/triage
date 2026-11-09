package dev.deveps.triage.api.bateria;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/** Publica en la web una grabación ya hecha, tal cual: lo que se ve es lo que pasó. */
public final class ExportadorGrabacion {

    static final String ORIGEN = "bateria/resultados/grabaciones";
    private static final String DESTINO = "web/public/grabaciones";
    private static final String OPCION = "--exportar-grabacion=";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private ExportadorGrabacion() {
    }

    public static Optional<String> solicitada(String[] argumentos) {
        return Arrays.stream(argumentos).filter(a -> a.startsWith(OPCION)).map(a -> a.substring(OPCION.length())).findFirst();
    }

    public static Path exportar(String id, Path raiz) throws IOException {
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Identificador de grabación no válido");
        }
        Path origen = raiz.resolve(ORIGEN).resolve(id + ".json");
        if (!Files.isRegularFile(origen)) {
            throw new IllegalArgumentException("No existe la grabación " + id);
        }
        Path destino = raiz.resolve(DESTINO).resolve(id + ".json");
        Files.createDirectories(destino.getParent());
        return Files.copy(origen, destino, StandardCopyOption.REPLACE_EXISTING);
    }
}
