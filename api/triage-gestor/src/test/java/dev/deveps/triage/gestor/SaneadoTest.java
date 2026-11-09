package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SaneadoTest {

    @Test
    void quita_ansi_y_control_pero_conserva_saltos_y_tabuladores() {
        assertThat(Saneado.limpiar("\u001B[31mERROR\u001B[0m\tfallo\r\n\u0007fin\u0000", 4096)).isEqualTo("ERROR\tfallo\nfin");
    }

    @Test
    void recorta_a_4096_bytes_conservando_el_final() {
        String texto = "a".repeat(5000) + "FINAL";

        String limpio = Saneado.limpiar(texto, 4096);

        assertThat(limpio.getBytes(StandardCharsets.UTF_8).length).isEqualTo(4096);
        assertThat(limpio).endsWith("FINAL");
    }

    @Test
    void el_recorte_no_parte_un_caracter_multibyte() {
        String limpio = Saneado.limpiar("ñ".repeat(10), 5);

        assertThat(limpio).isEqualTo("ññ");
    }

    @Test
    void no_deja_marcas_del_host() {
        String id = "a".repeat(64);

        String limpio = Saneado.limpiar("ip 51.77.158.216 ruta /var/lib/docker/overlay2/xyz/merged id " + id + " fin", 4096);

        assertThat(limpio).doesNotContain("51.77.158.216", "/var/lib/docker", id).endsWith("fin");
    }
}
