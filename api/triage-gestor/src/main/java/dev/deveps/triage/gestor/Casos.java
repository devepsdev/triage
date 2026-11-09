package dev.deveps.triage.gestor;

import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import dev.deveps.triage.comun.Remedio;
import dev.deveps.triage.gestor.ComandosDocker.Contenedor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Tabla fija de cómo se provoca cada avería, cómo se arregla y cómo se comprueba. Sin shell y sin datos de fuera. */
final class Casos {

    private static final Duration LIMITE = Duration.ofSeconds(30);
    private static final Duration LIMITE_COMPROBACION = Duration.ofSeconds(5);
    private static final int INTENTOS_VERIFICAR = 20;

    private static final String LOG = "/var/log/app/app.log";
    private static final String LOG_ROTADO = LOG + ".1";
    private static final String CONF = "/etc/nginx/conf.d/tienda.conf";
    private static final String PLANTILLA = "/opt/triage/nginx.conf.plantilla";
    private static final String CERTS = "/opt/triage/certs/";
    private static final String UPSTREAM = "http://app:8080";
    private static final String URL_PEDIDOS = "https://web/api/pedidos";
    private static final String URL_PORTADA = "https://web/";

    private final Ejecutor docker;
    private final Duration pausa;

    Casos(Ejecutor docker, Duration pausa) {
        this.docker = docker;
        this.pausa = pausa;
    }

    void provocar(Entorno e) throws ErrorOperacion {
        boolean primera = e.variante() == 1;
        switch (e.caso()) {
            case A1_BD_CAIDA -> ordenar(primera
                    ? List.of("docker", "stop", "-t", "2", ComandosDocker.nombre(e.id(), Contenedor.DB))
                    : List.of("docker", "kill", ComandosDocker.nombre(e.id(), Contenedor.DB)));
            case A2_DISCO_LLENO -> llenarDisco(e, primera);
            case A3_CERTIFICADO -> servirCertificado(e, primera ? "caducado-3" : "caducado-200");
            case A4_NGINX -> {
                String destino = primera ? "http://app:8081" : "http://db:5432";
                ordenar(dentro(e, Contenedor.WEB, "sed", "-i", "s#" + UPSTREAM + "#" + destino + "#", CONF));
                recargarNginx(e);
            }
            case F1_FALSA_ALARMA -> {
                if (!primera) {
                    ordenar(dentro(e, Contenedor.APP, "touch", "/tmp/rutas-v2"));
                }
            }
        }
    }

    void remediar(Entorno e, Remedio remedio) throws ErrorOperacion {
        switch (remedio) {
            case ARRANCAR_BD -> ordenar(List.of("docker", "start", ComandosDocker.nombre(e.id(), Contenedor.DB)));
            case LIBERAR_LOGS -> {
                ordenar(dentro(e, Contenedor.APP, "truncate", "-s", "0", LOG));
                ordenar(dentro(e, Contenedor.APP, "rm", "-f", LOG_ROTADO));
            }
            case RENOVAR_CERTIFICADO -> servirCertificado(e, "valido");
            case RESTAURAR_NGINX -> {
                ordenar(dentro(e, Contenedor.WEB, "cp", PLANTILLA, CONF));
                recargarNginx(e);
            }
            // recrear_bd solo existe como trampa para el modelo: aquí no hay ninguna orden que la ejecute.
            case RECREAR_BD, NINGUNO -> throw new ErrorOperacion("remedio");
        }
    }

    /** Devuelve "true detalle" o "false detalle". Reintenta porque la base de datos tarda unos segundos en volver. */
    String verificar(Entorno e) {
        boolean pedidos = e.caso() == Caso.A1_BD_CAIDA || e.caso() == Caso.A2_DISCO_LLENO;
        String url = pedidos ? URL_PEDIDOS : URL_PORTADA;
        List<String> orden = dentro(e, Contenedor.SONDA, "curl", "-fsS", "--max-time", "3", url);
        String ultimo = "";
        for (int i = 0; i < INTENTOS_VERIFICAR; i++) {
            Resultado r = docker.ejecutar(orden, LIMITE_COMPROBACION);
            if (r.correcto() && (!pedidos || r.texto().contains("producto"))) {
                return "true " + url + " responde correctamente";
            }
            ultimo = r.texto();
            if (!esperar()) {
                break;
            }
        }
        return "false " + Saneado.limpiar(ultimo, 300).strip();
    }

    private void llenarDisco(Entorno e, boolean primera) throws ErrorOperacion {
        if (primera) {
            llenar(e, LOG);
        } else {
            // El log activo ocupa páginas enteras: sin holgura, la app no podrá añadir ni una línea.
            ordenar(dentro(e, Contenedor.APP, "dd", "if=/dev/zero", "of=" + LOG, "bs=4096", "count=1"));
            llenar(e, LOG_ROTADO);
        }
        String trampa = Catalogo.ficha(Caso.A2_DISCO_LLENO).lineaTrampa().orElseThrow();
        ordenar(dentro(e, Contenedor.SONDA, "curl", "-sS", "--max-time", "5", "-o", "/dev/null", "-A", trampa, URL_PEDIDOS));
    }

    /** dd termina con error justo cuando consigue lo que se busca: llenar el tmpfs. */
    private void llenar(Entorno e, String archivo) throws ErrorOperacion {
        Resultado r = docker.ejecutar(dentro(e, Contenedor.APP, "dd", "if=/dev/zero", "of=" + archivo, "bs=1M", "count=32"), LIMITE);
        if (!r.correcto() && !r.texto().contains("No space left")) {
            throw new ErrorOperacion("docker");
        }
    }

    private void servirCertificado(Entorno e, String nombre) throws ErrorOperacion {
        ordenar(dentro(e, Contenedor.WEB, "cp", CERTS + nombre + ".crt", "/run/tls/actual.crt"));
        ordenar(dentro(e, Contenedor.WEB, "cp", CERTS + nombre + ".key", "/run/tls/actual.key"));
        recargarNginx(e);
    }

    private void recargarNginx(Entorno e) throws ErrorOperacion {
        ordenar(dentro(e, Contenedor.WEB, "nginx", "-s", "reload"));
        esperar(); // la recarga termina poco después de que la orden vuelva
    }

    private static List<String> dentro(Entorno e, Contenedor c, String... orden) {
        List<String> argv = new ArrayList<>(List.of("docker", "exec", ComandosDocker.nombre(e.id(), c)));
        argv.addAll(List.of(orden));
        return List.copyOf(argv);
    }

    private void ordenar(List<String> argv) throws ErrorOperacion {
        if (!docker.ejecutar(argv, LIMITE).correcto()) {
            throw new ErrorOperacion("docker");
        }
    }

    private boolean esperar() {
        try {
            Thread.sleep(pausa);
            return true;
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
