package dev.deveps.triage.gestor;

import dev.deveps.triage.comun.Caso;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Peticiones "OP arg1 arg2", respuestas "OK bytes\ncuerpo" o "ERR codigo\n".
 * Cada socket tiene su propio mapa: una operación que no está en él, para ese socket no existe.
 */
final class Protocolo {

    @FunctionalInterface
    interface Operacion {
        String ejecutar(List<String> args) throws ErrorOperacion;
    }

    private static final Pattern PARTE_SEGURA = Pattern.compile("[A-Za-z0-9_./-]+");
    private static final int VARIANTES = 2;

    private final Map<String, Operacion> operaciones;

    Protocolo(Map<String, Operacion> operaciones) {
        this.operaciones = Map.copyOf(operaciones);
    }

    static Protocolo control(Entornos entornos) {
        return new Protocolo(Map.of(
                "crear", args -> {
                    exigir(args.size() == 2);
                    Entorno e = entornos.crear(caso(args.get(0)), variante(args.get(1)));
                    return e.id() + " " + e.token();
                },
                "destruir", args -> {
                    exigir(args.size() == 1);
                    entornos.destruir(args.getFirst());
                    return "";
                }));
    }

    static Protocolo lectura(Consultas consultas) {
        return new Protocolo(consultas.operaciones());
    }

    String atender(String linea) {
        String[] partes = linea.split(" ", -1);
        if (!Arrays.stream(partes).allMatch(Protocolo::segura)) {
            return error("arg");
        }
        Operacion operacion = operaciones.get(partes[0]);
        if (operacion == null) {
            return error("op");
        }
        try {
            String cuerpo = operacion.ejecutar(List.of(partes).subList(1, partes.length));
            return "OK " + cuerpo.getBytes(StandardCharsets.UTF_8).length + "\n" + cuerpo;
        } catch (ErrorOperacion e) {
            return error(e.codigo());
        } catch (RuntimeException e) {
            return error("interno");
        }
    }

    private static boolean segura(String parte) {
        return PARTE_SEGURA.matcher(parte).matches() && !parte.contains("..");
    }

    private static String error(String codigo) {
        return "ERR " + codigo + "\n";
    }

    static void exigir(boolean condicion) throws ErrorOperacion {
        if (!condicion) {
            throw new ErrorOperacion("arg");
        }
    }

    private static Caso caso(String texto) throws ErrorOperacion {
        try {
            return Caso.valueOf(texto);
        } catch (IllegalArgumentException e) {
            throw new ErrorOperacion("arg");
        }
    }

    private static int variante(String texto) throws ErrorOperacion {
        try {
            int variante = Integer.parseInt(texto);
            exigir(variante >= 1 && variante <= VARIANTES);
            return variante;
        } catch (NumberFormatException e) {
            throw new ErrorOperacion("arg");
        }
    }
}
