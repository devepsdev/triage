package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.comun.Caso;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RegistroTest {

    private final AtomicReference<Instant> ahora = new AtomicReference<>(Instant.parse("2026-11-09T10:00:00Z"));
    private final Clock reloj = new Clock() {
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora.get(); }
    };
    private final Registro registro = new Registro(reloj, Duration.ofMinutes(6));

    @Test
    void el_token_localiza_su_entorno() {
        Entorno e = registro.registrar(Caso.A1_BD_CAIDA, 1);

        assertThat(registro.porToken(e.token())).contains(e);
        assertThat(registro.porToken("otro")).isEmpty();
    }

    @Test
    void el_token_caduca_con_el_entorno() {
        Entorno e = registro.registrar(Caso.A1_BD_CAIDA, 1);

        ahora.set(ahora.get().plus(Duration.ofMinutes(6)).plusSeconds(1));

        assertThat(registro.porToken(e.token())).isEmpty();
    }

    @Test
    void solo_estan_vencidos_los_de_mas_de_6_minutos() {
        Entorno e = registro.registrar(Caso.A2_DISCO_LLENO, 2);

        ahora.set(ahora.get().plus(Duration.ofMinutes(5)).plusSeconds(59));
        assertThat(registro.vencidos()).isEmpty();

        ahora.set(ahora.get().plusSeconds(2));
        assertThat(registro.vencidos()).containsExactly(e);
    }

    @Test
    void cada_entorno_tiene_id_y_token_propios_e_imposibles_de_adivinar() {
        Entorno a = registro.registrar(Caso.A3_CERTIFICADO, 1);
        Entorno b = registro.registrar(Caso.A3_CERTIFICADO, 1);

        assertThat(a.id()).isNotEqualTo(b.id()).matches("[0-9a-f]{12}");
        assertThat(a.token()).isNotEqualTo(b.token()).matches("[0-9a-f]{64}");
    }

    @Test
    void quitar_invalida_el_token() {
        Entorno e = registro.registrar(Caso.A4_NGINX, 1);

        registro.quitar(e.id());

        assertThat(registro.porToken(e.token())).isEmpty();
        assertThat(registro.porId(e.id())).isEmpty();
    }
}
