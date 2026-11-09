package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.comun.Caso;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsultasTest {

    private final EjecutorFalso docker = new EjecutorFalso();
    private final Clock reloj = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC);
    private final Registro registro = new Registro(reloj, Duration.ofMinutes(6));
    private final Protocolo lectura = Protocolo.lectura(new Consultas(registro, docker, reloj));
    private final Entorno e = registro.registrar(Caso.A1_BD_CAIDA, 1);

    private String pedir(String resto) {
        return lectura.atender(resto.replace("TOK", e.token()));
    }

    @Test
    void un_token_desconocido_no_llega_a_docker() {
        assertThat(lectura.atender("leer_logs inventado db 5")).isEqualTo("ERR token\n");
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void servicio_parado_en_lo_que_se_ejecuta_dentro() {
        docker.respuesta = argv -> new Resultado(1, "Error response from daemon: container abc is not running");

        assertThat(pedir("ver_uso_disco TOK db")).endsWith("servicio parado");
    }

    @Test
    void leer_logs_usa_docker_logs_y_funciona_con_el_servicio_parado() {
        docker.respuesta = argv -> new Resultado(0, "linea de log");

        assertThat(pedir("leer_logs TOK db 5")).endsWith("linea de log");
        assertThat(docker.ordenes.getFirst()).isEqualTo(List.of("docker", "logs", "--tail", "5", "t" + e.id() + "-db"));
    }

    @Test
    void lineas_fuera_de_rango_no_llegan_a_docker() {
        assertThat(pedir("leer_logs TOK db 0")).isEqualTo("ERR arg\n");
        assertThat(pedir("leer_logs TOK db 201")).isEqualTo("ERR arg\n");
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void valores_fuera_de_enumeracion_no_llegan_a_docker() {
        for (String linea : List.of("comprobar_http TOK /etc/passwd https", "comprobar_http TOK / ftp", "leer_logs TOK sonda 5",
                "comprobar_puerto TOK db 22", "ver_mayores_archivos TOK db raiz")) {
            assertThat(pedir(linea)).as(linea).isEqualTo("ERR arg\n");
        }
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void el_puerto_8081_es_valido_y_rechazado_se_distingue() {
        docker.respuesta = argv -> new Resultado(7, "* connect to 172.19.0.2 port 8081 failed: Connection refused");

        assertThat(pedir("comprobar_puerto TOK app 8081")).endsWith("rechazado");
    }

    @Test
    void puerto_abierto_si_llega_a_conectar() {
        docker.respuesta = argv -> new Resultado(0, "* Connected to app (172.19.0.3) port 8080");

        assertThat(pedir("comprobar_puerto TOK app 8080")).endsWith("abierto");
    }

    @Test
    void comprobar_http_devuelve_codigo_y_recorta_el_cuerpo_a_2_kb() {
        docker.respuesta = argv -> new Resultado(0, "b".repeat(3000) + "\n__TRIAGE__200 0.012 ");

        String respuesta = pedir("comprobar_http TOK /demo/ https");

        assertThat(respuesta).contains("codigo=200", "tiempo=0.012", "redireccion=-");
        assertThat(respuesta.chars().filter(c -> c == 'b').count()).isEqualTo(2048);
    }

    @Test
    void comprobar_http_informa_del_error_de_tls_sin_codigo() {
        docker.respuesta = argv -> new Resultado(60, "curl: (60) SSL certificate problem: certificate has expired");

        assertThat(pedir("comprobar_http TOK / https")).contains("sin respuesta HTTP", "certificate has expired");
    }

    @Test
    void comprobar_tls_calcula_los_dias_restantes() {
        docker.respuesta = argv -> new Resultado(0, """
                Certificate chain
                 0 s:CN=tienda.interna
                   i:CN=CA de la tienda
                   a:PKEY: id-ecPublicKey, 256 (bit); sigalg: ecdsa-with-SHA256
                   v:NotBefore: Aug  1 10:00:00 2026 GMT; NotAfter: Nov  6 10:00:00 2026 GMT
                Verify return code: 10 (certificate has expired)
                """);

        assertThat(pedir("comprobar_tls TOK")).contains("sujeto=CN=tienda.interna", "emisor=CN=CA de la tienda",
                "caduca=Nov  6 10:00:00 2026 GMT", "dias_restantes=-3", "verificacion=10 (certificate has expired)");
    }

    @Test
    void ver_mayores_archivos_ordena_y_se_queda_con_diez() {
        StringBuilder salida = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            salida.append(i).append("\t/var/log/app/f").append(i).append('\n');
        }
        docker.respuesta = argv -> new Resultado(0, salida.toString());

        String respuesta = pedir("ver_mayores_archivos TOK app logs");

        assertThat(respuesta).contains("12K /var/log/app/f12", "3K /var/log/app/f3").doesNotContain("/var/log/app/f2\n");
        assertThat(respuesta.indexOf("f12")).isLessThan(respuesta.indexOf("f3"));
    }

    @Test
    void las_salidas_pasan_por_el_saneado() {
        docker.respuesta = argv -> new Resultado(0, "\u001B[31mrojo\u001B[0m 203.0.113.7");

        assertThat(pedir("leer_logs TOK web 5")).doesNotContain("\u001B", "203.0.113.7");
    }

    @Test
    void listar_servicios_solo_da_los_campos_elegidos() {
        docker.respuesta = argv -> new Resultado(0, "exited 137 0 false -");

        assertThat(pedir("listar_servicios TOK"))
                .contains("web: estado=exited salida=137 reinicios=0 sin_memoria=false salud=-")
                .contains("app:", "db:")
                .doesNotContain("sonda");
    }

    @Test
    void la_definicion_de_alerta_sale_del_catalogo_sin_tocar_docker() {
        assertThat(pedir("ver_definicion_alerta TOK")).contains("https://web/api/pedidos", "esperado=200");
        assertThat(docker.ordenes).isEmpty();
    }

    @Test
    void cambios_recientes_lee_el_comun_y_el_del_caso() {
        docker.respuesta = argv -> new Resultado(0, "historial");

        assertThat(pedir("ver_cambios_recientes TOK")).endsWith("historial");
        assertThat(docker.ordenes.getFirst()).contains("/opt/triage/cambios/comun.txt", "/opt/triage/cambios/a1.txt");
    }

    @Test
    void crear_por_el_socket_de_lectura_real_no_existe() {
        assertThat(lectura.atender("crear A1_BD_CAIDA 1")).isEqualTo("ERR op\n");
        assertThat(lectura.atender("remediar abc ARRANCAR_BD")).isEqualTo("ERR op\n");
        assertThat(docker.ordenes).isEmpty();
    }
}
