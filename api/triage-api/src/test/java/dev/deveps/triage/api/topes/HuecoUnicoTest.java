package dev.deveps.triage.api.topes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HuecoUnicoTest {

    @TempDir
    Path carpeta;

    @Test
    void en_memoria_solo_entra_uno() {
        HuecoUnico hueco = new HuecoUnico();

        assertThat(hueco.adquirir()).isTrue();
        assertThat(hueco.adquirir()).isFalse();
        assertThat(hueco.libre()).isFalse();

        hueco.liberar();
        assertThat(hueco.libre()).isTrue();
    }

    @Test
    void con_archivo_dos_huecos_comparten_el_mismo_sitio() {
        HuecoUnico visitas = new HuecoUnico(carpeta.resolve("hueco.lock"));
        HuecoUnico bateria = new HuecoUnico(carpeta.resolve("hueco.lock"));

        assertThat(bateria.adquirir()).isTrue();
        assertThat(visitas.libre()).isFalse();
        assertThat(visitas.adquirir()).isFalse();

        bateria.liberar();
        assertThat(visitas.libre()).isTrue();
        assertThat(visitas.adquirir()).isTrue();
        assertThat(bateria.adquirir()).isFalse();
        visitas.liberar();
    }

    @Test
    void mirar_si_esta_libre_no_lo_ocupa() {
        HuecoUnico hueco = new HuecoUnico(carpeta.resolve("hueco.lock"));

        assertThat(hueco.libre()).isTrue();
        assertThat(hueco.libre()).isTrue();
        assertThat(hueco.adquirir()).isTrue();
        hueco.liberar();
    }

    @Test
    void si_no_se_puede_abrir_el_archivo_no_se_admite_a_nadie() {
        HuecoUnico hueco = new HuecoUnico(carpeta.resolve("no/existe/hueco.lock"));

        assertThat(hueco.adquirir()).isFalse();
        assertThat(hueco.libre()).isFalse();
    }
}
