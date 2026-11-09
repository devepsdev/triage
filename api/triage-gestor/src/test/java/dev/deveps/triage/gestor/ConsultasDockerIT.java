package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.comun.Caso;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Contra Docker de verdad, con un entorno sano. Se ejecuta con -Pdocker. */
class ConsultasDockerIT {

    private static final Ejecutor DOCKER = new EjecutorProceso();
    private static final Registro REGISTRO = new Registro(Clock.systemUTC(), Duration.ofMinutes(6));
    private static final Entornos ENTORNOS = new Entornos(REGISTRO, DOCKER, Duration.ofSeconds(1));
    private static final Protocolo LECTURA = Protocolo.lectura(new Consultas(REGISTRO, DOCKER, Clock.systemUTC()));
    private static Entorno entorno;

    @BeforeAll
    static void crear() throws Exception {
        entorno = ENTORNOS.crear(Caso.A1_BD_CAIDA, 1);
    }

    @AfterAll
    static void destruir() throws Exception {
        ENTORNOS.destruir(entorno.id());
    }

    private static String pedir(String resto) {
        return LECTURA.atender(resto.replace("TOK", entorno.token()));
    }

    @Test
    void listar_servicios() {
        assertThat(pedir("listar_servicios TOK")).contains("web: estado=running", "app: estado=running", "db: estado=running");
    }

    @Test
    void leer_logs() {
        assertThat(pedir("leer_logs TOK app 50")).contains("GET /api/pedidos 200");
    }

    @Test
    void comprobar_http() {
        assertThat(pedir("comprobar_http TOK /api/pedidos https")).contains("codigo=200", "producto");
    }

    @Test
    void comprobar_tls_con_certificado_valido() {
        assertThat(pedir("comprobar_tls TOK")).contains("sujeto=CN=tienda.interna", "verificacion=0 (ok)").containsPattern("dias_restantes=3\\d{3}");
    }

    @Test
    void comprobar_puerto_distingue_abierto_y_rechazado() {
        assertThat(pedir("comprobar_puerto TOK app 8080")).endsWith("abierto");
        assertThat(pedir("comprobar_puerto TOK app 8081")).endsWith("rechazado");
    }

    @Test
    void ver_uso_disco() {
        assertThat(pedir("ver_uso_disco TOK app")).contains("/var/log/app", "16.0M");
    }

    @Test
    void ver_mayores_archivos() {
        assertThat(pedir("ver_mayores_archivos TOK app logs")).contains("/var/log/app/app.log");
    }

    @Test
    void ver_config_nginx() {
        assertThat(pedir("ver_config_nginx TOK")).contains("syntax is ok", "proxy_pass http://app:8080");
    }

    @Test
    void ver_estado_bd() {
        assertThat(pedir("ver_estado_bd TOK")).contains("accepting connections", "conexiones: ", "tamano: ");
    }

    @Test
    void ver_cambios_recientes() {
        assertThat(pedir("ver_cambios_recientes TOK")).contains("despliegue app 1.8.2", "paginación");
    }
}
