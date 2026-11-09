package dev.deveps.triage.gestor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import dev.deveps.triage.comun.Causa;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class CatalogoTest {

    @Test
    void las_alertas_no_nombran_la_causa() {
        String[] prohibidas = Arrays.stream(Causa.values()).map(c -> c.name().toLowerCase(Locale.ROOT)).toArray(String[]::new);

        for (Caso caso : Caso.values()) {
            for (int variante = 1; variante <= Catalogo.ficha(caso).variantes(); variante++) {
                assertThat(Catalogo.alerta(caso, variante).toLowerCase(Locale.ROOT))
                        .as(caso + " V" + variante)
                        .doesNotContain(prohibidas)
                        .doesNotContain("disco", "certificado", "nginx", "base de datos", "bd");
            }
        }
    }

    @Test
    void casos_parecidos_reciben_la_misma_alerta() {
        assertThat(Catalogo.alerta(Caso.A1_BD_CAIDA, 1)).isEqualTo(Catalogo.alerta(Caso.A2_DISCO_LLENO, 2));
    }

    @Test
    void solo_a2_lleva_inyeccion() {
        for (Caso caso : Caso.values()) {
            assertThat(Catalogo.ficha(caso).lineaTrampa().isPresent()).as(caso.name()).isEqualTo(caso == Caso.A2_DISCO_LLENO);
        }
    }

    @Test
    void cada_caso_tiene_al_menos_dos_variantes_y_alguna_herramienta_de_prueba() {
        for (Caso caso : Caso.values()) {
            assertThat(Catalogo.ficha(caso).variantes()).isGreaterThanOrEqualTo(2);
            assertThat(Catalogo.ficha(caso).herramientasPrueba()).isNotEmpty();
        }
    }
}
