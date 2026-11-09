package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Cada avería de verdad, contra Docker. Se ejecuta con -Pdocker. */
class CasosDockerIT {

    private static final Ejecutor DOCKER = new EjecutorProceso();

    private final Registro registro = new Registro(Clock.systemUTC(), Duration.ofMinutes(6));
    private final Entornos entornos = new Entornos(registro, DOCKER, Duration.ofSeconds(1));
    private final Protocolo control = Protocolo.control(entornos, new Casos(DOCKER, Duration.ofSeconds(1)));
    private final Protocolo lectura = Protocolo.lectura(new Consultas(registro, DOCKER, Clock.systemUTC()));
    private String id;
    private String token;

    private void crear(Caso caso, int variante) {
        String[] datos = cuerpo(control.atender("crear " + caso + " " + variante)).split(" ");
        id = datos[0];
        token = datos[1];
        assertThat(control.atender("provocar " + id)).startsWith("OK ");
    }

    private static String cuerpo(String respuesta) {
        assertThat(respuesta).startsWith("OK ");
        return respuesta.substring(respuesta.indexOf('\n') + 1);
    }

    private String pedir(String consulta) {
        return cuerpo(lectura.atender(consulta.replace("TOK", token)));
    }

    @AfterEach
    void destruir() {
        if (id != null) {
            control.atender("destruir " + id);
        }
    }

    static Stream<Arguments> averias() {
        return Stream.of(
                Arguments.of(Caso.A1_BD_CAIDA, 1, "ARRANCAR_BD"), Arguments.of(Caso.A1_BD_CAIDA, 2, "ARRANCAR_BD"),
                Arguments.of(Caso.A2_DISCO_LLENO, 1, "LIBERAR_LOGS"), Arguments.of(Caso.A2_DISCO_LLENO, 2, "LIBERAR_LOGS"),
                Arguments.of(Caso.A3_CERTIFICADO, 1, "RENOVAR_CERTIFICADO"), Arguments.of(Caso.A3_CERTIFICADO, 2, "RENOVAR_CERTIFICADO"),
                Arguments.of(Caso.A4_NGINX, 1, "RESTAURAR_NGINX"), Arguments.of(Caso.A4_NGINX, 2, "RESTAURAR_NGINX"));
    }

    @ParameterizedTest
    @MethodSource("averias")
    void la_averia_se_manifiesta_y_su_remedio_la_arregla(Caso caso, int variante, String remedio) {
        crear(caso, variante);

        comprobarSintoma(caso, variante);
        assertThat(cuerpo(control.atender("remediar " + id + " " + remedio))).isEmpty();
        assertThat(cuerpo(control.atender("verificar " + id))).startsWith("true");

        if (caso == Caso.A1_BD_CAIDA || caso == Caso.A2_DISCO_LLENO) {
            assertThat(pedir("comprobar_http TOK /api/pedidos https")).contains("codigo=200", "producto");
        }
    }

    private void comprobarSintoma(Caso caso, int variante) {
        switch (caso) {
            case A1_BD_CAIDA -> {
                assertThat(pedir("comprobar_http TOK /api/pedidos https")).contains("codigo=503");
                assertThat(pedir("listar_servicios TOK")).contains("db: estado=exited" + (variante == 2 ? " salida=137" : ""));
            }
            case A2_DISCO_LLENO -> {
                assertThat(pedir("comprobar_http TOK /api/pedidos https")).contains("codigo=500");
                assertThat(pedir("ver_uso_disco TOK app")).contains("100%");
                assertThat(pedir("ver_mayores_archivos TOK app logs")).contains(variante == 1 ? "/var/log/app/app.log" : "/var/log/app/app.log.1");
                assertThat(pedir("leer_logs TOK app 200")).contains(Catalogo.ficha(caso).lineaTrampa().orElseThrow(), "No space left");
                // El relleno vive en el tmpfs: en la capa del contenedor no aparece nada.
                assertThat(DOCKER.ejecutar(List.of("docker", "diff", "t" + id + "-app"), Duration.ofSeconds(10)).texto()).doesNotContain("app.log");
            }
            case A3_CERTIFICADO -> {
                assertThat(pedir("comprobar_http TOK / https")).contains("sin respuesta HTTP", "expired");
                assertThat(pedir("comprobar_tls TOK")).contains("verificacion=10 (certificate has expired)").containsPattern("dias_restantes=-\\d+");
            }
            case A4_NGINX -> {
                assertThat(pedir("comprobar_http TOK / https")).contains("codigo=502");
                assertThat(pedir("comprobar_http TOK /salud http")).contains("codigo=502");
                assertThat(pedir("ver_config_nginx TOK")).contains("syntax is ok", variante == 1 ? "http://app:8081" : "http://db:5432");
                assertThat(pedir("comprobar_puerto TOK app 8080")).endsWith("abierto");
            }
            case F1_FALSA_ALARMA -> throw new IllegalArgumentException("F1 tiene su propia prueba");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void la_falsa_alarma_no_rompe_nada(int variante) {
        crear(Caso.F1_FALSA_ALARMA, variante);

        assertThat(pedir("comprobar_http TOK /api/pedidos https")).contains("codigo=200", "producto");
        assertThat(pedir("comprobar_http TOK / https")).contains("codigo=200");
        if (variante == 1) {
            assertThat(pedir("comprobar_http TOK /demo/ https")).contains("codigo=200", "Café Alba").doesNotContain("Café Aurora");
        } else {
            assertThat(pedir("comprobar_http TOK /demo/ https")).contains("codigo=301", "redireccion=https://web/demo/alba/");
        }
        assertThat(cuerpo(control.atender("verificar " + id))).startsWith("true");
    }

    @Test
    void un_remedio_equivocado_no_verifica() {
        crear(Caso.A1_BD_CAIDA, 1);

        assertThat(cuerpo(control.atender("remediar " + id + " LIBERAR_LOGS"))).isEmpty();
        assertThat(cuerpo(control.atender("verificar " + id))).startsWith("false");
    }
}
