package dev.deveps.triage.gestor;

import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import dev.deveps.triage.gestor.ComandosDocker.Contenedor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Las consultas de solo lectura. Ninguna recibe rutas, comandos ni texto libre: solo valores de listas cerradas. */
final class Consultas {

    @FunctionalInterface
    private interface Consulta {
        String responder(Entorno entorno, List<String> args) throws ErrorOperacion;
    }

    private static final int MAX_SALIDA = 4096;
    private static final int MAX_CUERPO = 2048;
    private static final Duration LIMITE = Duration.ofSeconds(10);
    private static final String MARCA = "__TRIAGE__";
    private static final String PARADO = "servicio parado";
    private static final String FORMATO_ESTADO = "{{.State.Status}} {{.State.ExitCode}} {{.RestartCount}} {{.State.OOMKilled}} "
            + "{{if .State.Health}}{{.State.Health.Status}}{{else}}-{{end}}";
    private static final DateTimeFormatter FECHA_CERT = DateTimeFormatter.ofPattern("MMM d HH:mm:ss yyyy z", Locale.ENGLISH);

    private static final Map<String, Contenedor> SERVICIOS = Map.of("web", Contenedor.WEB, "app", Contenedor.APP, "db", Contenedor.DB);
    private static final Set<String> RUTAS = Set.of("/", "/salud", "/api/pedidos", "/demo/");
    private static final Set<String> ESQUEMAS = Set.of("http", "https");
    private static final Set<String> PUERTOS = Set.of("80", "443", "5432", "8080", "8081");
    private static final Map<Contenedor, List<String>> VOLUMENES = Map.of(
            Contenedor.WEB, List.of("/etc/nginx/conf.d", "/run/tls", "/tmp"),
            Contenedor.APP, List.of("/var/log/app", "/tmp"),
            Contenedor.DB, List.of("/var/lib/postgresql/data", "/tmp"));
    private static final Map<Contenedor, Map<String, String>> ZONAS = Map.of(
            Contenedor.WEB, Map.of("logs", "/tmp", "datos", "/etc/nginx/conf.d"),
            Contenedor.APP, Map.of("logs", "/var/log/app", "datos", "/tmp"),
            Contenedor.DB, Map.of("logs", "/tmp", "datos", "/var/lib/postgresql/data"));
    private static final Map<Caso, String> CAMBIOS = Map.of(
            Caso.A1_BD_CAIDA, "a1.txt", Caso.A2_DISCO_LLENO, "a2.txt", Caso.A3_CERTIFICADO, "a3.txt",
            Caso.A4_NGINX, "a4.txt", Caso.F1_FALSA_ALARMA, "f1.txt");

    private final Registro registro;
    private final Ejecutor docker;
    private final Clock reloj;

    Consultas(Registro registro, Ejecutor docker, Clock reloj) {
        this.registro = registro;
        this.docker = docker;
        this.reloj = reloj;
    }

    Map<String, Protocolo.Operacion> operaciones() {
        return Map.ofEntries(
                Map.entry("listar_servicios", operacion(0, (e, a) -> listarServicios(e))),
                Map.entry("leer_logs", operacion(2, this::leerLogs)),
                Map.entry("comprobar_http", operacion(2, this::comprobarHttp)),
                Map.entry("comprobar_tls", operacion(0, (e, a) -> comprobarTls(e))),
                Map.entry("comprobar_puerto", operacion(2, this::comprobarPuerto)),
                Map.entry("ver_uso_disco", operacion(1, this::verUsoDisco)),
                Map.entry("ver_mayores_archivos", operacion(2, this::verMayoresArchivos)),
                Map.entry("ver_config_nginx", operacion(0, (e, a) -> verConfigNginx(e))),
                Map.entry("ver_estado_bd", operacion(0, (e, a) -> verEstadoBd(e))),
                Map.entry("ver_definicion_alerta", operacion(0, (e, a) -> Catalogo.definicionAlerta(e.caso(), e.variante()))),
                Map.entry("ver_cambios_recientes", operacion(0, (e, a) -> verCambiosRecientes(e))));
    }

    /** Todas las consultas: comprueban el número de argumentos y el token, y sanean la respuesta. */
    private Protocolo.Operacion operacion(int argumentos, Consulta consulta) {
        return args -> {
            Protocolo.exigir(args.size() == argumentos + 1);
            Entorno entorno = registro.porToken(args.getFirst()).orElseThrow(() -> new ErrorOperacion("token"));
            return Saneado.limpiar(consulta.responder(entorno, args.subList(1, args.size())), MAX_SALIDA);
        };
    }

    private static Contenedor servicio(String nombre) throws ErrorOperacion {
        Contenedor c = SERVICIOS.get(nombre);
        Protocolo.exigir(c != null);
        return c;
    }

    private Resultado exec(Entorno e, Contenedor c, String... orden) {
        List<String> argv = new ArrayList<>(List.of("docker", "exec", ComandosDocker.nombre(e.id(), c)));
        argv.addAll(List.of(orden));
        return docker.ejecutar(List.copyOf(argv), LIMITE);
    }

    private String dentro(Entorno e, Contenedor c, String... orden) {
        Resultado r = exec(e, c, orden);
        return r.texto().contains("is not running") ? PARADO : r.texto().strip();
    }

    private String listarServicios(Entorno e) {
        return Stream.of(Contenedor.WEB, Contenedor.APP, Contenedor.DB).map(c -> {
            String[] campos = docker.ejecutar(List.of("docker", "inspect", "-f", FORMATO_ESTADO, ComandosDocker.nombre(e.id(), c)), LIMITE)
                    .texto().strip().split("\\s+");
            if (campos.length < 5) {
                return c.alias + ": estado=desconocido";
            }
            return "%s: estado=%s salida=%s reinicios=%s sin_memoria=%s salud=%s".formatted(c.alias, campos[0], campos[1], campos[2], campos[3], campos[4]);
        }).collect(Collectors.joining("\n"));
    }

    private String leerLogs(Entorno e, List<String> args) throws ErrorOperacion {
        Contenedor c = servicio(args.get(0));
        int lineas = entero(args.get(1));
        Protocolo.exigir(lineas >= 1 && lineas <= 200);
        return docker.ejecutar(List.of("docker", "logs", "--tail", String.valueOf(lineas), ComandosDocker.nombre(e.id(), c)), LIMITE).texto();
    }

    private static int entero(String texto) throws ErrorOperacion {
        try {
            return Integer.parseInt(texto);
        } catch (NumberFormatException x) {
            throw new ErrorOperacion("arg");
        }
    }

    private String comprobarHttp(Entorno e, List<String> args) throws ErrorOperacion {
        Protocolo.exigir(RUTAS.contains(args.get(0)) && ESQUEMAS.contains(args.get(1)));
        Resultado r = exec(e, Contenedor.SONDA, "curl", "-sS", "--max-time", "5", "--max-redirs", "0",
                "-w", "\n" + MARCA + "%{http_code} %{time_total} %{redirect_url}", args.get(1) + "://web" + args.get(0));

        int posicion = r.texto().lastIndexOf(MARCA);
        String[] medidas = posicion < 0 ? new String[0] : r.texto().substring(posicion + MARCA.length()).strip().split("\\s+");
        if (medidas.length < 2 || medidas[0].equals("000")) {
            String motivo = posicion < 0 ? r.texto() : r.texto().substring(0, posicion);
            return "sin respuesta HTTP: " + motivo.strip();
        }
        String cuerpo = r.texto().substring(0, posicion).stripTrailing();
        String redireccion = medidas.length > 2 ? medidas[2] : "-";
        return "codigo=%s tiempo=%s redireccion=%s\n%s".formatted(medidas[0], medidas[1], redireccion,
                cuerpo.substring(0, Math.min(cuerpo.length(), MAX_CUERPO)));
    }

    private String comprobarTls(Entorno e) {
        String salida = exec(e, Contenedor.SONDA, "openssl", "s_client", "-connect", "web:443", "-servername", "web").texto();
        Optional<String> sujeto = valorTras(salida, " s:");
        Optional<String> emisor = valorTras(salida, "   i:");
        Optional<String> fechas = valorTras(salida, "v:NotBefore: ");
        Optional<String> verificacion = valorTras(salida, "Verify return code: ");
        if (fechas.isEmpty()) {
            return "sin conexion TLS con web: " + salida.strip();
        }
        String[] partes = fechas.get().split("; NotAfter: ");
        return "sujeto=%s emisor=%s inicio=%s caduca=%s dias_restantes=%s verificacion=%s".formatted(sujeto.orElse("-"), emisor.orElse("-"),
                partes[0], partes.length > 1 ? partes[1] : "-", partes.length > 1 ? diasRestantes(partes[1]) : "-", verificacion.orElse("-"));
    }

    private static Optional<String> valorTras(String salida, String prefijo) {
        return salida.lines().filter(l -> l.contains(prefijo)).findFirst()
                .map(l -> l.substring(l.indexOf(prefijo) + prefijo.length()).strip());
    }

    private String diasRestantes(String fecha) {
        try {
            Instant caduca = ZonedDateTime.parse(fecha.replaceAll("\\s+", " "), FECHA_CERT).toInstant();
            return String.valueOf(Duration.between(reloj.instant(), caduca).toDays());
        } catch (DateTimeParseException x) {
            return "-";
        }
    }

    private String comprobarPuerto(Entorno e, List<String> args) throws ErrorOperacion {
        Contenedor c = servicio(args.get(0));
        Protocolo.exigir(PUERTOS.contains(args.get(1)));
        Resultado r = exec(e, Contenedor.SONDA, "curl", "-sS", "-v", "--connect-timeout", "2", "--max-time", "3", "telnet://" + c.alias + ":" + args.get(1));
        String estado = r.texto().contains("Connected to") ? "abierto" : r.salida() == 7 ? "rechazado" : "sin respuesta";
        return c.alias + ":" + args.get(1) + " " + estado;
    }

    private String verUsoDisco(Entorno e, List<String> args) throws ErrorOperacion {
        Contenedor c = servicio(args.getFirst());
        List<String> orden = new ArrayList<>(List.of("df", "-h"));
        orden.addAll(VOLUMENES.get(c));
        return dentro(e, c, orden.toArray(String[]::new));
    }

    private String verMayoresArchivos(Entorno e, List<String> args) throws ErrorOperacion {
        Contenedor c = servicio(args.get(0));
        String zona = ZONAS.get(c).get(args.get(1));
        Protocolo.exigir(zona != null);
        String salida = dentro(e, c, "du", "-a", "-k", zona);
        if (salida.equals(PARADO)) {
            return salida;
        }
        record Fila(long kb, String ruta) {}
        return salida.lines()
                .map(l -> l.strip().split("\\s+", 2))
                .filter(p -> p.length == 2 && p[0].matches("\\d+") && !p[1].equals(zona))
                .map(p -> new Fila(Long.parseLong(p[0]), p[1]))
                .sorted(Comparator.comparingLong(Fila::kb).reversed())
                .limit(10)
                .map(f -> f.kb() + "K " + f.ruta())
                .collect(Collectors.joining("\n"));
    }

    private String verConfigNginx(Entorno e) {
        String prueba = dentro(e, Contenedor.WEB, "nginx", "-t");
        if (prueba.equals(PARADO)) {
            return prueba;
        }
        return "resultado de nginx -t:\n" + prueba + "\nconfiguracion:\n" + dentro(e, Contenedor.WEB, "cat", "/etc/nginx/conf.d/tienda.conf");
    }

    private String verEstadoBd(Entorno e) {
        Resultado listo = exec(e, Contenedor.SONDA, "pg_isready", "-U", "tienda", "-d", "tienda", "-h", "db");
        if (!listo.correcto()) {
            return "pg_isready: " + listo.texto().strip();
        }
        return "pg_isready: " + listo.texto().strip()
                + "\nconexiones: " + consultaBd(e, "SELECT count(*) FROM pg_stat_activity")
                + "\ntamano: " + consultaBd(e, "SELECT pg_size_pretty(pg_database_size('tienda'))");
    }

    private String consultaBd(Entorno e, String sql) {
        return docker.ejecutar(List.of("docker", "exec", "-e", "PGPASSWORD=tienda-ficticia", ComandosDocker.nombre(e.id(), Contenedor.SONDA),
                "psql", "-h", "db", "-U", "tienda", "-d", "tienda", "-At", "--command=" + sql), LIMITE).texto().strip();
    }

    private String verCambiosRecientes(Entorno e) {
        return dentro(e, Contenedor.APP, "cat", "/opt/triage/cambios/comun.txt", "/opt/triage/cambios/" + CAMBIOS.get(e.caso()));
    }
}
