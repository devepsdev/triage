package dev.deveps.triage.api.topes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.deveps.triage.api.agente.Presupuesto;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class TopesTest {

    private static final class RelojMovil extends Clock {
        Instant ahora = Instant.parse("2026-11-09T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return Clock.fixed(ahora, zona);
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private final RelojMovil reloj = new RelojMovil();
    private final JdbcClient db = BaseDatosPrueba.conPrecio();
    private final ControlGasto gasto = new ControlGasto(db, reloj, BaseDatosPrueba.VERSION, new BigDecimal("1.00"));
    private HttpServer servidor;
    private final AtomicInteger consultasCaptcha = new AtomicInteger();

    @AfterEach
    void parar() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private BigDecimal reservadoHoy() {
        return db.sql("SELECT reservado FROM gasto_dia WHERE dia = ?").param(gasto.hoy()).query(BigDecimal.class).single();
    }

    private Turnstile captcha(boolean exito) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            consultasCaptcha.incrementAndGet();
            byte[] cuerpo = ("{\"success\":" + exito + "}").getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(200, cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
        return new Turnstile(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/"), "secreto");
    }

    @Test
    void sin_precio_no_arranca() {
        assertThatThrownBy(() -> new ControlGasto(BaseDatosPrueba.nueva(), reloj, BaseDatosPrueba.VERSION, BigDecimal.ONE))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reserva_y_libera_la_diferencia() {
        Presupuesto p = gasto.presupuesto("inv1");

        assertThat(p.reservar(100_000)).isTrue();
        assertThat(reservadoHoy()).isEqualByComparingTo("0.30");

        p.liquidar(1_000, 100, BaseDatosPrueba.VERSION);

        assertThat(reservadoHoy()).isEqualByComparingTo("0");
        assertThat(gasto.gastadoHoy()).isEqualByComparingTo("0.0013");
        assertThat(db.sql("SELECT COUNT(*) FROM llamada_modelo").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void una_llamada_fallida_devuelve_la_reserva_sin_gastar() {
        Presupuesto p = gasto.presupuesto("inv1");
        p.reservar(10_000);

        p.liquidar(0, 0, BaseDatosPrueba.VERSION);

        assertThat(reservadoHoy()).isEqualByComparingTo("0");
        assertThat(gasto.gastadoHoy()).isEqualByComparingTo("0");
        assertThat(db.sql("SELECT COUNT(*) FROM llamada_modelo").query(Long.class).single()).isZero();
    }

    @Test
    void superar_el_tope_se_deniega() {
        Presupuesto p = gasto.presupuesto("inv1");

        assertThat(p.reservar(300_000)).isTrue();
        assertThat(p.reservar(50_000)).isFalse();
        assertThat(reservadoHoy()).isEqualByComparingTo("0.90");
    }

    @Test
    void el_tope_no_se_supera_ni_con_varias_reservas_a_la_vez() throws Exception {
        int hilos = 20;
        CountDownLatch salida = new CountDownLatch(1);
        List<Future<Boolean>> resultados = new ArrayList<>();
        try (var ejecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < hilos; i++) {
                resultados.add(ejecutor.submit(() -> {
                    salida.await();
                    return gasto.presupuesto("x").reservar(100_000);
                }));
            }
            salida.countDown();
        }
        long concedidas = 0;
        for (Future<Boolean> r : resultados) {
            concedidas += r.get() ? 1 : 0;
        }

        assertThat(concedidas).isEqualTo(3);
        assertThat(reservadoHoy()).isEqualByComparingTo("0.90");
    }

    @Test
    void el_gasto_sobrevive_a_un_reinicio_y_las_reservas_no() {
        Presupuesto p = gasto.presupuesto("inv1");
        p.reservar(1_000);
        p.liquidar(1_000, 0, BaseDatosPrueba.VERSION);
        gasto.presupuesto("inv2").reservar(50_000);

        ControlGasto trasReiniciar = new ControlGasto(db, reloj, BaseDatosPrueba.VERSION, new BigDecimal("1.00"));
        trasReiniciar.limpiarReservas();

        assertThat(trasReiniciar.gastadoHoy()).isEqualByComparingTo("0.001");
        assertThat(reservadoHoy()).isEqualByComparingTo("0");
    }

    @Test
    void cambia_de_dia_a_medianoche_de_madrid() {
        reloj.ahora = Instant.parse("2026-11-09T22:59:00Z");
        var antes = gasto.hoy();
        reloj.ahora = Instant.parse("2026-11-09T23:01:00Z");

        assertThat(antes).hasToString("2026-11-09");
        assertThat(gasto.hoy()).hasToString("2026-11-10");
    }

    @Test
    void la_cuarta_de_una_ip_se_rechaza_y_al_dia_siguiente_vuelve() {
        LimitadorIp limitador = new LimitadorIp(reloj, 3);
        for (int i = 0; i < 3; i++) {
            assertThat(limitador.permite("1.2.3.4")).isTrue();
            limitador.registrar("1.2.3.4");
        }

        assertThat(limitador.permite("1.2.3.4")).isFalse();
        assertThat(limitador.permite("5.6.7.8")).isTrue();

        reloj.ahora = reloj.ahora.plus(Duration.ofDays(1));
        assertThat(limitador.permite("1.2.3.4")).isTrue();
    }

    @Test
    void no_guarda_la_ip_en_claro() {
        LimitadorIp limitador = new LimitadorIp(reloj, 3);
        limitador.registrar("1.2.3.4");

        assertThat(limitador.huellas()).singleElement().satisfies(h -> assertThat(h).doesNotContain("1.2.3.4").hasSize(64));
    }

    @Test
    void la_31_del_dia_se_rechaza() {
        ContadorDiario contador = new ContadorDiario(db, gasto, 30);
        for (int i = 0; i < 30; i++) {
            assertThat(contador.quedan()).isTrue();
            contador.registrar();
        }

        assertThat(contador.quedan()).isFalse();
    }

    @Test
    void dos_hilos_a_la_vez_solo_uno_adquiere() throws Exception {
        for (int vuelta = 0; vuelta < 200; vuelta++) {
            HuecoUnico hueco = new HuecoUnico();
            CountDownLatch salida = new CountDownLatch(1);
            Future<Boolean> a;
            Future<Boolean> b;
            try (var ejecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                a = ejecutor.submit(() -> { salida.await(); return hueco.adquirir(); });
                b = ejecutor.submit(() -> { salida.await(); return hueco.adquirir(); });
                salida.countDown();
            }
            assertThat(a.get() ^ b.get()).isTrue();
        }
    }

    private Admision admision(Turnstile captcha, LimitadorIp ip, HuecoUnico hueco, boolean clave) {
        return new Admision(captcha, ip, new ContadorDiario(db, gasto, 30), gasto, hueco, clave);
    }

    private int usosHoy() {
        return db.sql("SELECT investigaciones FROM gasto_dia WHERE dia = ?").param(gasto.hoy()).query(Integer.class).optional().orElse(0);
    }

    @Test
    void admitida_adquiere_el_hueco_y_gasta_cupo() throws IOException {
        HuecoUnico hueco = new HuecoUnico();

        assertThat(admision(captcha(true), new LimitadorIp(reloj, 3), hueco, true).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).isEmpty();

        assertThat(hueco.libre()).isFalse();
        assertThat(usosHoy()).isEqualTo(1);
    }

    @Test
    void captcha_malo_no_toca_contadores() throws IOException {
        LimitadorIp ip = new LimitadorIp(reloj, 3);

        assertThat(admision(captcha(false), ip, new HuecoUnico(), true).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).contains(Rechazo.CAPTCHA);

        assertThat(ip.huellas()).isEmpty();
        assertThat(usosHoy()).isZero();
    }

    @Test
    void estar_ocupado_no_gasta_cupo() throws IOException {
        HuecoUnico hueco = new HuecoUnico();
        hueco.adquirir();
        LimitadorIp ip = new LimitadorIp(reloj, 3);

        assertThat(admision(captcha(true), ip, hueco, true).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).contains(Rechazo.OCUPADO);

        assertThat(ip.huellas()).isEmpty();
        assertThat(usosHoy()).isZero();
    }

    @Test
    void orden_de_lo_barato_a_lo_caro() throws IOException {
        Turnstile captcha = captcha(true);
        Admision admision = admision(captcha, new LimitadorIp(reloj, 3), new HuecoUnico(), true);

        assertThat(admision.comprobar("INVENTADO", "tok", "1.2.3.4")).contains(Rechazo.CASO_INVALIDO);
        assertThat(consultasCaptcha).hasValue(0);

        LimitadorIp agotada = new LimitadorIp(reloj, 0);
        assertThat(admision(captcha, agotada, new HuecoUnico(), true).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).contains(Rechazo.LIMITE_IP);
        assertThat(consultasCaptcha).hasValue(1);
    }

    @Test
    void sin_gasto_disponible_se_rechaza_antes_del_hueco() throws IOException {
        gasto.presupuesto("x").reservar(330_000);
        HuecoUnico hueco = new HuecoUnico();

        assertThat(admision(captcha(true), new LimitadorIp(reloj, 3), hueco, true).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).contains(Rechazo.GASTO);
        assertThat(hueco.libre()).isTrue();
    }

    @Test
    void sin_secreto_de_captcha_o_sin_clave_no_esta_disponible_y_no_llama_a_nadie() throws IOException {
        Turnstile sinSecreto = new Turnstile(HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/"), "");

        assertThat(admision(sinSecreto, new LimitadorIp(reloj, 3), new HuecoUnico(), true).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).contains(Rechazo.NO_DISPONIBLE);
        assertThat(admision(sinSecreto, new LimitadorIp(reloj, 3), new HuecoUnico(), true).motivoNoDisponible()).contains(Rechazo.NO_DISPONIBLE);
        assertThat(admision(captcha(true), new LimitadorIp(reloj, 3), new HuecoUnico(), false).comprobar("A1_BD_CAIDA", "tok", "1.2.3.4")).contains(Rechazo.NO_DISPONIBLE);
        assertThat(consultasCaptcha).hasValue(0);
    }

    @Test
    void motivo_no_disponible_no_gasta_nada() throws IOException {
        Optional<Rechazo> motivo = admision(captcha(true), new LimitadorIp(reloj, 3), new HuecoUnico(), true).motivoNoDisponible();

        assertThat(motivo).isEmpty();
        assertThat(consultasCaptcha).hasValue(0);
        assertThat(usosHoy()).isZero();
    }
}
