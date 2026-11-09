package dev.deveps.triage.gestor;

import java.util.ArrayList;
import java.util.List;

/**
 * Todas las órdenes de Docker que existen, como listas de argumentos.
 * Nunca pasan por un shell, así que ningún dato puede convertirse en código.
 */
final class ComandosDocker {

    enum Contenedor {
        WEB("web", "64m", "0.25", 64, "101:101", "triage/web",
                List.of("/etc/nginx/conf.d:rw,size=1m,uid=101,gid=101", "/run/tls:rw,size=1m,uid=101,gid=101", "/tmp:rw,size=8m,uid=101,gid=101")),
        APP("app", "128m", "0.25", 64, "1000:1000", "triage/app",
                List.of("/var/log/app:rw,size=16m,uid=1000,gid=1000", "/tmp:rw,size=4m,uid=1000,gid=1000")),
        DB("db", "256m", "0.50", 128, "70:70", "triage/db",
                List.of("/var/lib/postgresql/data:rw,size=96m,uid=70,gid=70,mode=0700", "/var/run/postgresql:rw,size=1m,uid=70,gid=70", "/tmp:rw,size=8m,uid=70,gid=70")),
        SONDA("sonda", "64m", "0.25", 32, "1000:1000", "triage/sonda",
                List.of("/tmp:rw,size=1m,uid=1000,gid=1000"));

        final String alias;
        private final String memoria;
        private final String cpus;
        private final int procesos;
        private final String usuario;
        private final String imagen;
        private final List<String> tmpfs;

        Contenedor(String alias, String memoria, String cpus, int procesos, String usuario, String imagen, List<String> tmpfs) {
            this.alias = alias;
            this.memoria = memoria;
            this.cpus = cpus;
            this.procesos = procesos;
            this.usuario = usuario;
            this.imagen = imagen;
            this.tmpfs = tmpfs;
        }
    }

    private static final String FORMATO_CADUCIDAD = "{{.ID}} {{.Label \"triage.caduca\"}}";

    private ComandosDocker() {
    }

    static String nombre(String id, Contenedor c) {
        return "t" + id + "-" + c.alias;
    }

    private static String red(String id) {
        return "t" + id + "-red";
    }

    static List<String> crearRed(String id, long caduca) {
        return List.of("docker", "network", "create", "--internal",
                "-o", "com.docker.network.bridge.gateway_mode_ipv4=isolated",
                "--label", "triage.env=" + id, "--label", "triage.caduca=" + caduca, red(id));
    }

    static List<String> arrancar(String id, long caduca, Contenedor c) {
        List<String> argv = new ArrayList<>(List.of("docker", "run", "--detach", "--pull=never",
                "--name", nombre(id, c), "--network", red(id), "--network-alias", c.alias,
                "--read-only", "--cap-drop=ALL", "--security-opt", "no-new-privileges",
                "--cgroup-parent=triage.slice", "--restart=no", "--log-opt", "max-size=2m",
                // Sin esto, resolver el nombre de un servicio parado cuelga la petición más de 5 s y el fallo no parece un 5xx.
                "--dns-opt", "timeout:1", "--dns-opt", "attempts:1",
                "--label", "triage.env=" + id, "--label", "triage.caduca=" + caduca,
                "--user", c.usuario, "--memory=" + c.memoria, "--memory-swap=" + c.memoria,
                "--cpus=" + c.cpus, "--pids-limit=" + c.procesos));
        for (String volumen : c.tmpfs) {
            argv.add("--tmpfs");
            argv.add(volumen);
        }
        argv.add(c.imagen);
        return List.copyOf(argv);
    }

    static List<String> tiendaLista(String id) {
        return List.of("docker", "exec", nombre(id, Contenedor.SONDA), "curl", "-fsS", "--max-time", "3", "https://web/api/pedidos");
    }

    static List<String> borrarContenedores(String id) {
        List<String> argv = new ArrayList<>(List.of("docker", "rm", "-f"));
        for (Contenedor c : Contenedor.values()) {
            argv.add(nombre(id, c));
        }
        return List.copyOf(argv);
    }

    static List<String> borrarRed(String id) {
        return List.of("docker", "network", "rm", red(id));
    }

    static List<String> listarContenedoresTriage() {
        return List.of("docker", "ps", "-a", "--filter", "label=triage.env", "--format", FORMATO_CADUCIDAD);
    }

    static List<String> listarRedesTriage() {
        return List.of("docker", "network", "ls", "--filter", "label=triage.env", "--format", FORMATO_CADUCIDAD);
    }
}
