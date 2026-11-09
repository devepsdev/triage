package dev.deveps.triage.api.bateria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.deveps.triage.api.agente.Herramientas;
import dev.deveps.triage.api.bateria.ComandoBateria.Dependencias;
import dev.deveps.triage.api.bateria.ComandoBateria.Parametros;
import dev.deveps.triage.api.investigacion.RepositorioInvestigacion;
import dev.deveps.triage.api.investigacion.ServicioInvestigacion;
import dev.deveps.triage.api.modelo.DefinicionFuncion;
import dev.deveps.triage.api.modelo.LlamadaFuncion;
import dev.deveps.triage.api.modelo.ProveedorModelo;
import dev.deveps.triage.api.modelo.RespuestaModelo;
import dev.deveps.triage.api.topes.BaseDatosPrueba;
import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.api.topes.HuecoUnico;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.simple.JdbcClient;

@Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ComandoBateriaTest {

    private static final String BD_PARADA = """
            {"veredicto":"averia","causa":"bd_parada","pruebas":[{"id_llamada":"c1","cita":"db: estado=exited"}],"descartado":"","remedio":"arrancar_bd","explicacion":"x"}""";
    private static final BigDecimal DE_SOBRA = new BigDecimal("5");

    @TempDir
    Path raiz;

    private final List<String> ordenes = Collections.synchronizedList(new ArrayList<>());
    private final ScheduledExecutorService programador = Executors.newSingleThreadScheduledExecutor();
    private final JdbcClient db = BaseDatosPrueba.conPrecio();
    private final HuecoUnico hueco = new HuecoUnico();

    /** Siempre dice lo mismo: acierta en A1 y falla en el resto. */
    private final ProveedorModelo modelo = peticion -> {
        boolean primeraRonda = peticion.mensajes().size() <= 2;
        LlamadaFuncion llamada = primeraRonda ? new LlamadaFuncion("x", "listar_servicios", "{\"encontrado\":\"miro\"}") : new LlamadaFuncion("x", "emitir_diagnostico", BD_PARADA);
        return new RespuestaModelo(Optional.of(llamada), "", 100, 20, BaseDatosPrueba.VERSION);
    };

    private final ServicioInvestigacion.Gestor gestor = (op, args) -> {
        ordenes.add(args.length > 1 && op.equals("crear") ? op + " " + args[0] + " " + args[1] : op);
        return op.equals("crear") ? "ent1 tok1" : "";
    };

    private final Herramientas herramientas = new Herramientas() {
        @Override
        public List<DefinicionFuncion> definiciones() {
            return List.of(new DefinicionFuncion("listar_servicios", "estado", Map.of("type", "object", "properties", Map.of())));
        }

        @Override
        public String llamar(String nombre, Map<String, Object> args) {
            return "db: estado=exited";
        }
    };

    @AfterEach
    void parar() {
        programador.shutdownNow();
    }

    private InformeBateria lanzar(int repeticiones, BigDecimal confirmado) throws IOException {
        Dependencias dependencias = new Dependencias(modelo, gestor, token -> herramientas, db, hueco, programador, Clock.systemUTC(), true, raiz);
        return new ComandoBateria(dependencias).ejecutar(new Parametros(BaseDatosPrueba.VERSION, repeticiones, confirmado));
    }

    private List<Path> grabaciones() throws IOException {
        try (Stream<Path> archivos = Files.list(raiz.resolve(ExportadorGrabacion.ORIGEN))) {
            return archivos.toList();
        }
    }

    @Test
    void escribe_el_informe_y_una_grabacion_por_repeticion() throws IOException {
        InformeBateria informe = lanzar(2, DE_SOBRA);

        assertThat(grabaciones()).hasSize(10);
        try (Stream<Path> archivos = Files.list(raiz.resolve("bateria/resultados"))) {
            Path resultado = archivos.filter(Files::isRegularFile).findFirst().orElseThrow();
            assertThat(resultado.getFileName().toString()).endsWith("-" + BaseDatosPrueba.VERSION + ".json");
            assertThat(Files.readString(resultado)).contains("\"huellaPrompt\"", "\"A1_BD_CAIDA\"", "\"grabacion\"");
        }
        assertThat(informe.datos()).containsEntry("completa", true);
        Map<?, ?> global = (Map<?, ?>) informe.datos().get("global");
        assertThat(global.get("total")).isEqualTo(10);
        assertThat(global.get("aciertos")).isEqualTo(2);
    }

    @Test
    void las_grabaciones_guardan_los_eventos_con_su_ritmo() throws IOException {
        lanzar(1, DE_SOBRA);

        String grabacion = Files.readString(grabaciones().getFirst());
        assertThat(grabacion).contains("\"ms\"", "\"tipo\" : \"paso\"", "\"tipo\" : \"fin\"", "\"modelo\" : \"" + BaseDatosPrueba.VERSION + "\"");
    }

    @Test
    void recorre_las_variantes_de_cada_caso() throws IOException {
        lanzar(2, DE_SOBRA);

        assertThat(ordenes).contains("crear A1_BD_CAIDA 1", "crear A1_BD_CAIDA 2", "crear F1_FALSA_ALARMA 1", "crear F1_FALSA_ALARMA 2");
    }

    @Test
    void rechaza_todas_las_propuestas_nunca_remedia_y_destruye_cada_entorno() throws IOException {
        lanzar(2, DE_SOBRA);

        assertThat(ordenes).noneMatch(o -> o.startsWith("remediar"));
        assertThat(ordenes.stream().filter("destruir"::equals)).hasSize(10);
    }

    @Test
    void no_suma_en_el_contador_publico_ni_en_el_gasto_diario() throws IOException {
        lanzar(1, DE_SOBRA);

        assertThat(new RepositorioInvestigacion(db).contador()).isEqualTo(new RepositorioInvestigacion.Contador(0, 0));
        assertThat(new ControlGasto(db, Clock.systemUTC(), BaseDatosPrueba.VERSION, BigDecimal.ONE).gastadoHoy()).isEqualByComparingTo("0");
    }

    @Test
    void para_al_llegar_a_lo_confirmado_y_lo_dice() throws IOException {
        BigDecimal poco = new BigDecimal("0.006");

        InformeBateria informe = lanzar(2, poco);

        assertThat(informe.datos()).containsEntry("completa", false);
        assertThat((Integer) ((Map<?, ?>) informe.datos().get("global")).get("total")).isLessThan(10);
        assertThat(new BigDecimal((String) informe.datos().get("coste"))).isLessThanOrEqualTo(poco);
    }

    @Test
    void mientras_dura_el_hueco_comun_esta_ocupado_y_luego_se_libera() throws IOException {
        ProveedorModelo original = modelo;
        List<Boolean> libreDurante = Collections.synchronizedList(new ArrayList<>());
        ProveedorModelo mirando = peticion -> {
            libreDurante.add(hueco.libre());
            return original.completar(peticion);
        };
        Dependencias dependencias = new Dependencias(mirando, gestor, token -> herramientas, db, hueco, programador, Clock.systemUTC(), true, raiz);

        new ComandoBateria(dependencias).ejecutar(new Parametros(BaseDatosPrueba.VERSION, 1, DE_SOBRA));

        assertThat(libreDurante).isNotEmpty().containsOnly(false);
        assertThat(hueco.libre()).isTrue();
    }

    @Test
    void con_el_hueco_ocupado_no_arranca_ni_llama_al_modelo() {
        hueco.adquirir();

        assertThatThrownBy(() -> lanzar(1, DE_SOBRA)).isInstanceOf(IllegalStateException.class);

        assertThat(ordenes).isEmpty();
    }

    @Test
    void un_modelo_sin_precio_no_arranca() {
        Dependencias dependencias = new Dependencias(modelo, gestor, token -> herramientas, db, hueco, programador, Clock.systemUTC(), true, raiz);

        assertThatThrownBy(() -> new ComandoBateria(dependencias).ejecutar(new Parametros("otro-modelo", 1, DE_SOBRA))).isInstanceOf(IllegalStateException.class);

        assertThat(hueco.libre()).isTrue();
    }

    @Test
    void exportar_copia_la_grabacion_a_la_web() throws IOException {
        lanzar(1, DE_SOBRA);
        String nombre = grabaciones().getFirst().getFileName().toString();
        String id = nombre.substring(0, nombre.length() - ".json".length());

        Path destino = ExportadorGrabacion.exportar(id, raiz);

        assertThat(destino).isEqualTo(raiz.resolve("web/public/grabaciones").resolve(nombre));
        assertThat(destino).hasSameTextualContentAs(grabaciones().getFirst());
    }

    @Test
    void exportar_no_sale_de_su_carpeta() {
        assertThatThrownBy(() -> ExportadorGrabacion.exportar("../../secreto", raiz)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExportadorGrabacion.exportar("no-existe", raiz)).isInstanceOf(IllegalArgumentException.class);
    }
}
