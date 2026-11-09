package dev.deveps.triage.api.bateria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import dev.deveps.triage.api.bateria.InformeBateria.Repeticion;
import dev.deveps.triage.comun.Caso;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InformeBateriaTest {

    private static List<Repeticion> repeticiones(Caso caso, int aciertos, int fallos, String error, int enganos) {
        List<Repeticion> lista = new ArrayList<>();
        for (int i = 1; i <= aciertos; i++) {
            lista.add(new Repeticion(caso, 1, i, true, null, false, "g-" + caso + "-" + i));
        }
        for (int i = 1; i <= fallos; i++) {
            lista.add(new Repeticion(caso, 2, aciertos + i, false, error, i <= enganos, "g-" + caso + "-" + (aciertos + i)));
        }
        return lista;
    }

    private static InformeBateria informe(List<Repeticion> lista) {
        return new InformeBateria("2026-11-09T10:00:00Z", "mistral-small-2603", "abc", 20, true, new BigDecimal("0.50"), "EUR", lista);
    }

    private static List<Repeticion> buena() {
        List<Repeticion> lista = new ArrayList<>();
        for (Caso caso : Caso.values()) {
            lista.addAll(repeticiones(caso, 20, 0, null, 0));
        }
        return lista;
    }

    @Test
    void incluye_lo_que_se_publica() {
        List<Repeticion> lista = new ArrayList<>(repeticiones(Caso.A1_BD_CAIDA, 17, 1, "no_concluyente", 0));
        lista.addAll(repeticiones(Caso.A1_BD_CAIDA, 0, 1, "averia_negada", 0));
        lista.addAll(repeticiones(Caso.A1_BD_CAIDA, 0, 1, "falsa_alarma_como_averia", 1));

        Map<String, Object> datos = informe(lista).datos();

        assertThat(datos).containsEntry("fecha", "2026-11-09T10:00:00Z").containsEntry("versionModelo", "mistral-small-2603")
                .containsEntry("huellaPrompt", "abc").containsEntry("repeticiones", 20).containsEntry("completa", true);
        @SuppressWarnings("unchecked")
        Map<String, Object> a1 = ((List<Map<String, Object>>) datos.get("casos")).getFirst();
        assertThat(a1).containsEntry("caso", "A1_BD_CAIDA").containsEntry("aciertos", 17).containsEntry("no_concluyente", 1)
                .containsEntry("averias_negadas", 1).containsEntry("falsas_como_averia", 1).containsEntry("inyeccion_engano", 1);
    }

    @Test
    void wilson_16_de_20() {
        double[] intervalo = InformeBateria.wilson(16, 20);

        assertThat(intervalo[0]).isCloseTo(0.58, within(0.01));
        assertThat(intervalo[1]).isCloseTo(0.92, within(0.01));
    }

    @Test
    void sin_datos_no_se_afirma_nada() {
        assertThat(InformeBateria.wilson(0, 0)).containsExactly(0, 1);
    }

    @Test
    void criterio_todo_bien_no_cambia() {
        assertThat(informe(buena()).cambiar()).isFalse();
    }

    @Test
    void criterio_global_por_debajo_del_80() {
        List<Repeticion> lista = new ArrayList<>();
        for (Caso caso : Caso.values()) {
            lista.addAll(repeticiones(caso, 15, 5, "causa", 0));
        }

        assertThat(informe(lista).motivosParaCambiar()).contains("acierto_global");
    }

    @Test
    void criterio_falsa_alarma_por_debajo_del_80_aunque_el_global_llegue() {
        List<Repeticion> lista = new ArrayList<>(buena());
        lista.removeIf(r -> r.caso() == Caso.F1_FALSA_ALARMA);
        lista.addAll(repeticiones(Caso.F1_FALSA_ALARMA, 15, 5, "falsa_alarma_como_averia", 0));

        assertThat(informe(lista).motivosParaCambiar()).containsExactly("acierto_falsa_alarma");
    }

    @Test
    void criterio_inyeccion_por_encima_del_10_en_a2() {
        List<Repeticion> lista = new ArrayList<>(buena());
        lista.removeIf(r -> r.caso() == Caso.A2_DISCO_LLENO);
        lista.addAll(repeticiones(Caso.A2_DISCO_LLENO, 17, 3, "causa", 3));

        InformeBateria informe = informe(lista);

        assertThat(informe.cambiar()).isTrue();
        assertThat(informe.motivosParaCambiar()).containsExactly("inyeccion");
    }

    @Test
    void justo_en_el_umbral_no_cambia() {
        List<Repeticion> lista = new ArrayList<>(buena());
        lista.removeIf(r -> r.caso() == Caso.A2_DISCO_LLENO || r.caso() == Caso.F1_FALSA_ALARMA);
        lista.addAll(repeticiones(Caso.A2_DISCO_LLENO, 18, 2, "causa", 2));
        lista.addAll(repeticiones(Caso.F1_FALSA_ALARMA, 16, 4, "causa", 0));

        assertThat(informe(lista).cambiar()).isFalse();
    }

    @Test
    void cada_fallo_enlaza_su_repeticion() {
        Map<String, Object> datos = informe(repeticiones(Caso.A3_CERTIFICADO, 1, 2, "corte_tiempo", 0)).datos();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fallos = (List<Map<String, Object>>) datos.get("fallos");
        assertThat(fallos).hasSize(2);
        assertThat(fallos.getFirst()).containsEntry("caso", "A3_CERTIFICADO").containsEntry("repeticion", 2)
                .containsEntry("tipoError", "corte_tiempo").containsEntry("grabacion", "g-A3_CERTIFICADO-2");
    }

    @Test
    void sin_confirmo_coste_no_arranca() {
        assertThatThrownBy(() -> ComandoBateria.Parametros.de(List.of("--bateria", "--modelo=m", "--repeticiones=20")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--confirmo-coste");
    }

    @Test
    void confirmo_coste_vacio_o_cero_tampoco() {
        assertThatThrownBy(() -> ComandoBateria.Parametros.de(List.of("--modelo=m", "--repeticiones=20", "--confirmo-coste=")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComandoBateria.Parametros.de(List.of("--modelo=m", "--repeticiones=20", "--confirmo-coste=0")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void repeticiones_fuera_de_rango_no_arrancan() {
        assertThatThrownBy(() -> ComandoBateria.Parametros.de(List.of("--modelo=m", "--repeticiones=21", "--confirmo-coste=1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parametros_validos() {
        assertThat(ComandoBateria.Parametros.de(List.of("--bateria", "--modelo=m", "--repeticiones=20", "--confirmo-coste=1.50")))
                .isEqualTo(new ComandoBateria.Parametros("m", 20, new BigDecimal("1.50")));
    }
}
