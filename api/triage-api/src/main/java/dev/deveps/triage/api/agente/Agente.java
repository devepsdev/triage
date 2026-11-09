package dev.deveps.triage.api.agente;

import dev.deveps.triage.api.modelo.DefinicionFuncion;
import dev.deveps.triage.api.modelo.LlamadaFuncion;
import dev.deveps.triage.api.modelo.Mensaje;
import dev.deveps.triage.api.modelo.PeticionModelo;
import dev.deveps.triage.api.modelo.ProveedorException;
import dev.deveps.triage.api.modelo.ProveedorModelo;
import dev.deveps.triage.api.modelo.RespuestaModelo;
import dev.deveps.triage.comun.Causa;
import dev.deveps.triage.comun.Remedio;
import dev.deveps.triage.comun.Veredicto;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * El bucle: pregunta al modelo, ejecuta la consulta que pide y le devuelve el resultado.
 * Lo que venga del modelo o de los sistemas investigados se trata siempre como dato no fiable.
 */
public final class Agente {

    static final int MAX_RONDAS = 12;
    static final int MAX_TOKENS = 60_000;
    static final int MAX_SALIDA_MODELO = 1_500;

    private static final String EMITIR = "emitir_diagnostico";
    private static final int MAX_FRASE = 200;
    private static final int MAX_TEXTO = 1_000;
    private static final int TURNOS_PARA_CONCLUIR = 2;
    /** Sobreestima a propósito: reservar de más solo retrasa, reservar de menos dejaría superar el tope. */
    private static final int CARACTERES_POR_TOKEN = 2;

    private final JsonMapper json = JsonMapper.builder().build();
    private final ProveedorModelo proveedor;
    private final String version;
    private final String sistema;

    public Agente(ProveedorModelo proveedor, String version) {
        this.proveedor = proveedor;
        this.version = version;
        this.sistema = promptSistema();
    }

    public static String promptSistema() {
        try (InputStream entrada = Agente.class.getResourceAsStream("/prompts/sistema.txt")) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ResultadoAgente investigar(String alerta, Herramientas herramientas, Presupuesto presupuesto, Consumer<EventoAgente> salida) {
        return new Investigacion(alerta, herramientas, presupuesto, salida).ejecutar();
    }

    /** El estado de una investigación concreta. */
    private final class Investigacion {
        private final Herramientas herramientas;
        private final Presupuesto presupuesto;
        private final Consumer<EventoAgente> salida;
        private final List<DefinicionFuncion> funciones;
        private final Set<String> consultas;
        private final List<Mensaje> mensajes = new ArrayList<>();
        private final List<LlamadaRegistrada> llamadas = new ArrayList<>();
        private int consumidos;

        Investigacion(String alerta, Herramientas herramientas, Presupuesto presupuesto, Consumer<EventoAgente> salida) {
            this.herramientas = herramientas;
            this.presupuesto = presupuesto;
            this.salida = salida;
            List<DefinicionFuncion> originales = herramientas.definiciones();
            this.consultas = originales.stream().map(DefinicionFuncion::nombre).collect(Collectors.toSet());
            this.funciones = new ArrayList<>(originales.stream().map(Agente::conFrases).toList());
            this.funciones.add(definicionDiagnostico());
            mensajes.add(texto("system", sistema));
            mensajes.add(texto("user", "Alerta recibida: " + alerta));
        }

        ResultadoAgente ejecutar() {
            for (int ronda = 1; ronda <= MAX_RONDAS; ronda++) {
                if (Thread.currentThread().isInterrupted()) {
                    return fin(Optional.empty(), "cancelada");
                }
                int estimados = estimar();
                if (consumidos + estimados > MAX_TOKENS || !presupuesto.reservar(estimados)) {
                    return fin(Optional.empty(), "tope");
                }
                RespuestaModelo respuesta;
                try {
                    respuesta = completar(estimados, debeConcluir(ronda, estimados) ? List.of(funciones.getLast()) : funciones);
                } catch (ProveedorException e) {
                    return fin(Optional.empty(), "proveedor");
                } catch (TopeAlcanzado e) {
                    return fin(Optional.empty(), "tope");
                }
                presupuesto.liquidar(respuesta.tokensEntrada(), respuesta.tokensSalida(), respuesta.versionDevuelta());
                consumidos += respuesta.tokensEntrada() + respuesta.tokensSalida();
                salida.accept(new EventoAgente.Consumo(respuesta.tokensEntrada(), respuesta.tokensSalida(), respuesta.versionDevuelta()));

                Optional<Diagnostico> diagnostico = atender(respuesta, ronda);
                if (diagnostico.isPresent()) {
                    return fin(diagnostico, "diagnostico");
                }
            }
            return fin(Optional.empty(), "rondas");
        }

        /** Sin esto, una investigación larga se queda sin turnos o sin tokens y no llega a decir nada. */
        private boolean debeConcluir(int ronda, int estimados) {
            return ronda > MAX_RONDAS - TURNOS_PARA_CONCLUIR || consumidos + 2 * estimados > MAX_TOKENS;
        }

        private RespuestaModelo completar(int estimados, List<DefinicionFuncion> disponibles) throws ProveedorException, TopeAlcanzado {
            PeticionModelo peticion = new PeticionModelo(version, List.copyOf(mensajes), List.copyOf(disponibles), MAX_SALIDA_MODELO);
            try {
                return proveedor.completar(peticion);
            } catch (ProveedorException primero) {
                presupuesto.liquidar(0, 0, version);
                if (!primero.recuperable()) {
                    throw primero;
                }
            }
            if (!presupuesto.reservar(estimados)) {
                throw new TopeAlcanzado();
            }
            try {
                return proveedor.completar(peticion);
            } catch (ProveedorException segundo) {
                presupuesto.liquidar(0, 0, version);
                throw segundo;
            }
        }

        private int estimar() {
            int caracteres = mensajes.stream().mapToInt(m -> m.contenido().length() + m.llamada().map(l -> l.argumentosJson().length()).orElse(0)).sum()
                    + json.writeValueAsString(funciones).length();
            return caracteres / CARACTERES_POR_TOKEN + MAX_SALIDA_MODELO;
        }

        private Optional<Diagnostico> atender(RespuestaModelo respuesta, int ronda) {
            if (respuesta.llamada().isEmpty()) {
                mensajes.add(texto("assistant", respuesta.texto()));
                mensajes.add(texto("user", "Debes responder llamando a una de las funciones."));
                return Optional.empty();
            }
            LlamadaFuncion llamada = respuesta.llamada().get();
            mensajes.add(new Mensaje("assistant", respuesta.texto(), Optional.of(llamada), Optional.empty()));
            String id = "c" + ronda;

            Map<String, Object> argumentos;
            try {
                argumentos = new HashMap<>(json.readValue(llamada.argumentosJson(), new TypeReference<Map<String, Object>>() {}));
            } catch (JacksonException e) {
                devolverError(llamada, id, ronda, "Los argumentos no son un JSON válido.");
                return Optional.empty();
            }
            if (llamada.nombre().equals(EMITIR)) {
                return interpretarDiagnostico(llamada, id, ronda, argumentos);
            }
            if (!consultas.contains(llamada.nombre())) {
                devolverError(llamada, id, ronda, "No existe esa función.");
                return Optional.empty();
            }
            consultar(llamada, id, ronda, argumentos);
            return Optional.empty();
        }

        private void consultar(LlamadaFuncion llamada, String id, int ronda, Map<String, Object> argumentos) {
            // Estos dos campos son para quien observa: el servidor MCP no los conoce.
            String encontrado = frase(argumentos.remove("encontrado"));
            String descartado = frase(argumentos.remove("descartado"));
            try {
                String resultado = herramientas.llamar(llamada.nombre(), Map.copyOf(argumentos));
                llamadas.add(new LlamadaRegistrada(id, llamada.nombre(), Map.copyOf(argumentos), resultado));
                salida.accept(new EventoAgente.Paso(ronda, id, llamada.nombre(), Map.copyOf(argumentos), resultado, encontrado, descartado));
                mensajes.add(respuestaHerramienta(llamada, sobre(id, ronda, Map.of("datos_no_fiables", resultado))));
            } catch (HerramientaException e) {
                salida.accept(new EventoAgente.Paso(ronda, id, llamada.nombre(), Map.copyOf(argumentos), "Error: " + e.getMessage(), encontrado, descartado));
                mensajes.add(respuestaHerramienta(llamada, sobre(id, ronda, Map.of("error", String.valueOf(e.getMessage())))));
            }
        }

        private Optional<Diagnostico> interpretarDiagnostico(LlamadaFuncion llamada, String id, int ronda, Map<String, Object> a) {
            Diagnostico diagnostico;
            try {
                Veredicto veredicto = valor(Veredicto.class, "veredicto", a.get("veredicto"));
                Causa causa = veredicto == Veredicto.NO_CONCLUYENTE && a.get("causa") == null ? null : valor(Causa.class, "causa", a.get("causa"));
                Remedio remedio = valor(Remedio.class, "remedio", a.get("remedio"));
                diagnostico = new Diagnostico(veredicto, causa, pruebas(a.get("pruebas")), recortar(a.get("descartado"), MAX_TEXTO), remedio, recortar(a.get("explicacion"), MAX_TEXTO));
            } catch (IllegalArgumentException e) {
                devolverError(llamada, id, ronda, "Diagnóstico rechazado. " + e.getMessage());
                return Optional.empty();
            }
            List<String> sinRespaldo = citasSinRespaldo(diagnostico);
            // En el último turno ya no podría rectificar: se acepta y el corrector lo contará como fallo.
            if (!sinRespaldo.isEmpty() && ronda < MAX_RONDAS) {
                devolverError(llamada, id, ronda, "Diagnóstico rechazado. Estas citas no aparecen literalmente en el resultado indicado: " + sinRespaldo
                        + ". Copia un fragmento exacto de sus datos_no_fiables o retira esa prueba.");
                return Optional.empty();
            }
            return Optional.of(diagnostico);
        }

        private List<String> citasSinRespaldo(Diagnostico diagnostico) {
            Map<String, LlamadaRegistrada> porId = llamadas.stream().collect(Collectors.toMap(LlamadaRegistrada::id, l -> l));
            return diagnostico.pruebas().stream().filter(p -> !Corrector.verificada(p, porId)).map(Prueba::idLlamada).toList();
        }

        private void devolverError(LlamadaFuncion llamada, String id, int ronda, String mensaje) {
            salida.accept(new EventoAgente.Paso(ronda, id, llamada.nombre(), Map.of(), "Error: " + mensaje, "", ""));
            mensajes.add(respuestaHerramienta(llamada, sobre(id, ronda, Map.of("error", mensaje))));
        }

        private String sobre(String id, int ronda, Map<String, String> contenido) {
            Map<String, Object> sobre = new LinkedHashMap<>();
            sobre.put("id_llamada", id);
            sobre.put("turnos_restantes", MAX_RONDAS - ronda);
            sobre.putAll(contenido);
            return json.writeValueAsString(sobre);
        }

        private ResultadoAgente fin(Optional<Diagnostico> diagnostico, String motivo) {
            salida.accept(new EventoAgente.Fin(motivo));
            return new ResultadoAgente(diagnostico, List.copyOf(llamadas), motivo);
        }
    }

    private static final class TopeAlcanzado extends Exception {
    }

    private static Mensaje texto(String rol, String contenido) {
        return new Mensaje(rol, contenido, Optional.empty(), Optional.empty());
    }

    private static Mensaje respuestaHerramienta(LlamadaFuncion llamada, String contenido) {
        return new Mensaje("tool", contenido, Optional.empty(), Optional.of(llamada.id()));
    }

    /** El mensaje dice qué falta y qué vale, pero nunca repite lo que envió el modelo. */
    private static <E extends Enum<E>> E valor(Class<E> tipo, String campo, Object recibido) {
        String permitidos = "El campo \"" + campo + "\" es obligatorio y debe ser uno de: " + nombres(tipo) + ".";
        if (!(recibido instanceof String texto)) {
            throw new IllegalArgumentException(permitidos);
        }
        try {
            return Enum.valueOf(tipo, texto.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(permitidos, e);
        }
    }

    private static List<Prueba> pruebas(Object recibido) {
        String formato = "El campo \"pruebas\" es obligatorio: una lista de objetos con \"id_llamada\" y \"cita\".";
        if (!(recibido instanceof List<?> lista)) {
            throw new IllegalArgumentException(formato);
        }
        return lista.stream().map(elemento -> {
            if (elemento instanceof Map<?, ?> m && m.get("id_llamada") instanceof String id && m.get("cita") instanceof String cita) {
                return new Prueba(id, cita);
            }
            throw new IllegalArgumentException(formato);
        }).toList();
    }

    private static String frase(Object recibido) {
        return recortar(recibido, MAX_FRASE);
    }

    private static String recortar(Object recibido, int maximo) {
        if (!(recibido instanceof String texto)) {
            return "";
        }
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

    @SuppressWarnings("unchecked")
    private static DefinicionFuncion conFrases(DefinicionFuncion original) {
        Map<String, Object> esquema = new LinkedHashMap<>(original.esquema());
        Map<String, Object> propiedades = new LinkedHashMap<>((Map<String, Object>) esquema.getOrDefault("properties", Map.of()));
        propiedades.put("encontrado", Map.of("type", "string", "maxLength", MAX_FRASE, "description", "Qué has sacado en claro hasta ahora y qué buscas con esta consulta, en una frase llana."));
        propiedades.put("descartado", Map.of("type", "string", "maxLength", MAX_FRASE, "description", "Qué posibilidad acabas de descartar, si la hay, en una frase llana."));
        esquema.put("properties", propiedades);
        List<String> obligatorios = new ArrayList<>((List<String>) esquema.getOrDefault("required", List.of()));
        obligatorios.add("encontrado");
        esquema.put("required", obligatorios);
        return new DefinicionFuncion(original.nombre(), original.descripcion(), esquema);
    }

    private static List<String> nombres(Class<? extends Enum<?>> tipo) {
        return Arrays.stream(tipo.getEnumConstants()).map(e -> e.name().toLowerCase(Locale.ROOT)).toList();
    }

    private static DefinicionFuncion definicionDiagnostico() {
        Map<String, Object> prueba = Map.of("type", "object", "additionalProperties", false, "required", List.of("id_llamada", "cita"),
                "properties", Map.of("id_llamada", Map.of("type", "string"), "cita", Map.of("type", "string", "maxLength", 300)));
        Map<String, Object> propiedades = new LinkedHashMap<>();
        propiedades.put("veredicto", Map.of("type", "string", "enum", nombres(Veredicto.class), "description",
                "averia: algo falla de verdad y afecta al servicio. falsa_alarma: el servicio funciona y lo que está mal es la comprobación que avisó; "
                        + "su causa es comprobacion_desfasada y su remedio, ninguno. no_concluyente: las pruebas no bastan para decidir."));
        propiedades.put("causa", Map.of("type", "string", "enum", nombres(Causa.class), "description", "Puede omitirse solo si el veredicto es no_concluyente."));
        propiedades.put("pruebas", Map.of("type", "array", "items", prueba));
        propiedades.put("descartado", Map.of("type", "string", "description", "Qué otras causas has descartado y por qué."));
        propiedades.put("remedio", Map.of("type", "string", "enum", nombres(Remedio.class)));
        propiedades.put("explicacion", Map.of("type", "string", "description", "Qué ocurre, en lenguaje llano."));
        return new DefinicionFuncion(EMITIR, "Termina la investigación con el diagnóstico. No modifica nada.",
                Map.of("type", "object", "additionalProperties", false, "properties", propiedades,
                        "required", List.of("veredicto", "pruebas", "descartado", "remedio", "explicacion")));
    }
}
