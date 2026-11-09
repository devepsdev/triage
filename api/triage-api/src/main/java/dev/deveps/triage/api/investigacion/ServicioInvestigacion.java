package dev.deveps.triage.api.investigacion;

import dev.deveps.triage.api.agente.Agente;
import dev.deveps.triage.api.agente.Correccion;
import dev.deveps.triage.api.agente.Corrector;
import dev.deveps.triage.api.agente.Diagnostico;
import dev.deveps.triage.api.agente.EventoAgente;
import dev.deveps.triage.api.agente.Herramientas;
import dev.deveps.triage.api.agente.Presupuesto;
import dev.deveps.triage.api.agente.ResultadoAgente;
import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.api.topes.HuecoUnico;
import dev.deveps.triage.comun.Caso;
import dev.deveps.triage.comun.Catalogo;
import dev.deveps.triage.comun.FichaCaso;
import dev.deveps.triage.comun.GestorException;
import dev.deveps.triage.comun.Remedio;
import dev.deveps.triage.comun.Veredicto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

/** Lleva cada investigación por su máquina de estados. Pase lo que pase, el entorno se destruye y el hueco se libera. */
public final class ServicioInvestigacion {

    @FunctionalInterface
    public interface Gestor {
        String pedir(String operacion, String... argumentos) throws GestorException;
    }

    @FunctionalInterface
    public interface FabricaHerramientas {
        Herramientas abrir(String token);
    }

    public record Tiempos(Duration aprobar, Duration maximo, Duration desconexion, Duration latido) {}

    public record Creada(String id, String tokenVer, String tokenAprobar) {}

    private enum Decision { APROBADA, RECHAZADA, CADUCADA }

    /** Lo único que un visitante puede hacer que se aplique. recrear_bd no está y nunca estará aquí. */
    private static final Set<Remedio> APROBABLES = Set.of(Remedio.ARRANCAR_BD, Remedio.LIBERAR_LOGS, Remedio.RENOVAR_CERTIFICADO, Remedio.RESTAURAR_NGINX);
    private static final Duration CONSERVAR_TRAS_FIN = Duration.ofMinutes(2);

    private final Map<String, Activa> activas = new ConcurrentHashMap<>();
    private final SecureRandom azar = new SecureRandom();
    private final Gestor gestor;
    private final FabricaHerramientas fabrica;
    private final Agente agente;
    private final Corrector corrector;
    private final ControlGasto gasto;
    private final HuecoUnico hueco;
    private final RepositorioInvestigacion repositorio;
    private final ScheduledExecutorService programador;
    private final Clock reloj;
    private final Tiempos tiempos;
    private final IntUnaryOperator variantes;
    private final Function<String, Presupuesto> presupuestos;

    public ServicioInvestigacion(Gestor gestor, FabricaHerramientas fabrica, Agente agente, Corrector corrector, ControlGasto gasto, HuecoUnico hueco,
            RepositorioInvestigacion repositorio, ScheduledExecutorService programador, Clock reloj, Tiempos tiempos, IntUnaryOperator variantes) {
        this(gestor, fabrica, agente, corrector, gasto, hueco, repositorio, programador, reloj, tiempos, variantes, gasto::presupuesto);
    }

    /** La batería trae su propio presupuesto: no gasta del tope diario de las visitas. */
    public ServicioInvestigacion(Gestor gestor, FabricaHerramientas fabrica, Agente agente, Corrector corrector, ControlGasto gasto, HuecoUnico hueco,
            RepositorioInvestigacion repositorio, ScheduledExecutorService programador, Clock reloj, Tiempos tiempos, IntUnaryOperator variantes,
            Function<String, Presupuesto> presupuestos) {
        this.presupuestos = presupuestos;
        this.gestor = gestor;
        this.fabrica = fabrica;
        this.agente = agente;
        this.corrector = corrector;
        this.gasto = gasto;
        this.hueco = hueco;
        this.repositorio = repositorio;
        this.programador = programador;
        this.reloj = reloj;
        this.tiempos = tiempos;
        this.variantes = variantes;
    }

    /** El hueco ya lo ha adquirido la admisión. */
    public Creada iniciar(Caso caso) {
        return iniciar(caso, variantes.applyAsInt(Catalogo.ficha(caso).variantes()));
    }

    public Creada iniciar(Caso caso, int variante) {
        Activa a;
        try {
            a = new Activa(caso, variante);
            repositorio.crear(a.id, caso, a.variante, reloj.instant());
        } catch (RuntimeException e) {
            hueco.liberar();
            throw e;
        }
        activas.put(a.id, a);
        a.canal.vigilar();
        a.hilo = Thread.startVirtualThread(a::ejecutar);
        programador.schedule(() -> a.cortar("tiempo"), tiempos.maximo().toMillis(), TimeUnit.MILLISECONDS);
        return new Creada(a.id, a.tokenVer, a.tokenAprobar);
    }

    public Optional<CanalEventos> canal(String id, String tokenVer) {
        Activa a = activas.get(id);
        return a != null && iguales(a.tokenVer, tokenVer) ? Optional.of(a.canal) : Optional.empty();
    }

    /** false significa 409: token malo, fuera de plazo, ya decidida o sin propuesta. */
    public boolean aprobar(String id, String token) {
        return decidir(id, token, Decision.APROBADA);
    }

    public boolean rechazar(String id, String token) {
        return decidir(id, token, Decision.RECHAZADA);
    }

    private boolean decidir(String id, String token, Decision decision) {
        Activa a = activas.get(id);
        return a != null && iguales(a.tokenAprobar, token) && a.estado == EstadoInvestigacion.PROPUESTA && a.decision.complete(decision);
    }

    private static boolean iguales(String esperado, String recibido) {
        return recibido != null && MessageDigest.isEqual(esperado.getBytes(StandardCharsets.UTF_8), recibido.getBytes(StandardCharsets.UTF_8));
    }

    private String aleatorio() {
        byte[] bytes = new byte[16];
        azar.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String minusculas(Enum<?> valor) {
        return valor == null ? null : valor.name().toLowerCase(Locale.ROOT);
    }

    private final class Activa {
        final String id = aleatorio();
        final String tokenVer = aleatorio();
        final String tokenAprobar = aleatorio();
        final FichaCaso ficha;
        final int variante;
        final CompletableFuture<Decision> decision = new CompletableFuture<>();
        final CanalEventos canal;
        volatile EstadoInvestigacion estado = EstadoInvestigacion.PREPARANDO;
        volatile Thread hilo;
        volatile String corte;
        String idEntorno;
        int tokensEntrada;
        int tokensSalida;
        BigDecimal coste = BigDecimal.ZERO;

        Activa(Caso caso, int variante) {
            this.ficha = Catalogo.ficha(caso);
            this.variante = variante;
            this.canal = new CanalEventos(programador, tiempos.desconexion(), tiempos.latido(), () -> cortar("desconexion"));
        }

        void cortar(String motivo) {
            if (estado.terminal() || corte != null) {
                return;
            }
            corte = motivo;
            decision.complete(Decision.CADUCADA);
            Thread h = hilo;
            if (h != null) {
                h.interrupt();
            }
        }

        void pasarA(EstadoInvestigacion nuevo) {
            estado = nuevo;
            repositorio.estado(id, nuevo);
            canal.emitir("estado", Map.of("estado", nuevo.name()));
        }

        void ejecutar() {
            EstadoInvestigacion fin = EstadoInvestigacion.FALLIDA;
            String motivo = "interno";
            try {
                pasarA(EstadoInvestigacion.PREPARANDO);
                String[] entorno = gestor.pedir("crear", ficha.caso().name(), String.valueOf(variante)).split(" ");
                idEntorno = entorno[0];
                repositorio.entorno(id, idEntorno);
                pasarA(EstadoInvestigacion.PROVOCANDO);
                gestor.pedir("provocar", idEntorno);
                pasarA(EstadoInvestigacion.INVESTIGANDO);

                ResultadoAgente resultado = investigar(entorno[1]);
                corregir(resultado);
                switch (resultado.motivoFin()) {
                    case "diagnostico" -> {
                        fin = EstadoInvestigacion.DESTRUIDA;
                        motivo = proponer(resultado.diagnostico().orElseThrow());
                    }
                    case "proveedor" -> motivo = "proveedor";
                    default -> {
                        fin = EstadoInvestigacion.AGOTADA;
                        motivo = resultado.motivoFin();
                    }
                }
            } catch (GestorException | RuntimeException e) {
                // El detalle no se enseña: un error interno no es asunto del visitante.
            } finally {
                if (corte != null) {
                    fin = EstadoInvestigacion.AGOTADA;
                    motivo = corte;
                }
                terminar(fin, motivo);
            }
        }

        private ResultadoAgente investigar(String token) {
            canal.emitir("alerta", Map.of("texto", Catalogo.alerta(ficha.caso(), variante)));
            Herramientas herramientas = fabrica.abrir(token);
            try {
                return agente.investigar(Catalogo.alerta(ficha.caso(), variante), herramientas, presupuesto(), this::publicar);
            } finally {
                if (herramientas instanceof AutoCloseable cerrable) {
                    cerrarSinRuido(cerrable);
                }
            }
        }

        /** Tras un corte, ninguna llamada más al modelo aunque la interrupción se haya perdido por el camino. */
        private Presupuesto presupuesto() {
            Presupuesto real = presupuestos.apply(id);
            return new Presupuesto() {
                @Override
                public boolean reservar(int tokensMax) {
                    return corte == null && real.reservar(tokensMax);
                }

                @Override
                public void liquidar(int entrada, int salida, String version) {
                    real.liquidar(entrada, salida, version);
                }
            };
        }

        private void publicar(EventoAgente evento) {
            switch (evento) {
                case EventoAgente.Paso p -> {
                    Map<String, Object> datos = new LinkedHashMap<>();
                    datos.put("numero", p.numero());
                    datos.put("id", p.id());
                    datos.put("herramienta", p.herramienta());
                    datos.put("args", p.args());
                    datos.put("salida", p.salida());
                    datos.put("encontrado", p.encontrado());
                    datos.put("descartado", p.descartado());
                    canal.emitir("paso", datos);
                    // La marca sale del catálogo y va solo a la web: el modelo únicamente ve el log, como en la vida real.
                    ficha.lineaTrampa().filter(p.salida()::contains).ifPresent(linea -> canal.emitir("trampa", Map.of("id", p.id(), "linea", linea)));
                }
                case EventoAgente.Consumo c -> {
                    tokensEntrada += c.tokensEntrada();
                    tokensSalida += c.tokensSalida();
                    coste = coste.add(gasto.coste(c.tokensEntrada(), c.tokensSalida(), c.version()));
                    canal.emitir("consumo", Map.of("tokensEntrada", tokensEntrada, "tokensSalida", tokensSalida, "coste", coste.toPlainString(), "moneda", gasto.moneda()));
                }
                case EventoAgente.Fin f -> { }
            }
        }

        private void corregir(ResultadoAgente r) {
            r.diagnostico().ifPresent(d -> canal.emitir("diagnostico", datos(d)));
            if (r.motivoFin().equals("diagnostico") || r.motivoFin().equals("rondas")) {
                Correccion c = corrector.corregir(r.diagnostico(), ficha, r.llamadas());
                repositorio.corregida(id, c);
                Map<String, Object> datos = new LinkedHashMap<>();
                datos.put("acierto", c.acierto());
                datos.put("pruebasVerificadas", c.pruebasVerificadas());
                datos.put("enganadoPorInyeccion", c.enganadoPorInyeccion());
                datos.put("tipoError", c.tipoError());
                canal.emitir("correccion", datos);
            }
        }

        private Map<String, Object> datos(Diagnostico d) {
            Map<String, Object> datos = new LinkedHashMap<>();
            datos.put("veredicto", minusculas(d.veredicto()));
            datos.put("causa", minusculas(d.causa()));
            datos.put("pruebas", d.pruebas().stream().map(p -> Map.of("idLlamada", p.idLlamada(), "cita", p.cita())).toList());
            datos.put("descartado", d.descartado());
            datos.put("remedio", minusculas(d.remedio()));
            datos.put("explicacion", d.explicacion());
            datos.put("aprobable", aprobable(d));
            return datos;
        }

        private boolean aprobable(Diagnostico d) {
            return d.veredicto() == Veredicto.AVERIA && APROBABLES.contains(d.remedio());
        }

        /** Devuelve el motivo de fin. Sin remedio aplicable no hay nada que aprobar. */
        private String proponer(Diagnostico d) throws GestorException {
            if (!aprobable(d)) {
                return "sin_remedio";
            }
            decision.completeOnTimeout(Decision.CADUCADA, tiempos.aprobar().toMillis(), TimeUnit.MILLISECONDS);
            pasarA(EstadoInvestigacion.PROPUESTA);
            canal.emitir("propuesta", Map.of("remedio", minusculas(d.remedio()), "plazoSegundos", tiempos.aprobar().toSeconds()));
            Decision tomada;
            try {
                tomada = decision.get();
            } catch (InterruptedException | ExecutionException e) {
                Thread.currentThread().interrupt();
                return "plazo";
            }
            return switch (tomada) {
                case CADUCADA -> "plazo";
                case RECHAZADA -> {
                    pasarA(EstadoInvestigacion.RECHAZADA);
                    yield "rechazada";
                }
                case APROBADA -> aplicar(d.remedio());
            };
        }

        private String aplicar(Remedio remedio) throws GestorException {
            pasarA(EstadoInvestigacion.APROBADA);
            pasarA(EstadoInvestigacion.APLICANDO);
            try {
                gestor.pedir("remediar", idEntorno, remedio.name());
            } catch (GestorException e) {
                emitirResultado(remedio, false, "No se ha podido aplicar el remedio.");
                return "remedio_fallido";
            }
            pasarA(EstadoInvestigacion.VERIFICANDO);
            String[] verificacion = gestor.pedir("verificar", idEntorno).split(" ", 2);
            boolean verificado = verificacion[0].equals("true");
            emitirResultado(remedio, verificado, verificacion.length > 1 ? verificacion[1] : "");
            return verificado ? "remedio_verificado" : "remedio_no_verificado";
        }

        private void emitirResultado(Remedio remedio, boolean verificado, String detalle) {
            canal.emitir("remedio_resultado", Map.of("remedio", minusculas(remedio), "verificado", verificado, "detalle", detalle));
        }

        private void terminar(EstadoInvestigacion fin, String motivo) {
            // Una interrupción pendiente cerraría el socket en cuanto se usara, y el entorno no se destruiría.
            Thread.interrupted();
            if (idEntorno != null) {
                try {
                    gestor.pedir("destruir", idEntorno);
                } catch (GestorException e) {
                    // El propio gestor lo borrará por caducidad.
                }
            }
            estado = fin;
            try {
                repositorio.terminar(id, fin, motivo, reloj.instant());
            } catch (RuntimeException e) {
                // Sin base de datos también hay que liberar el hueco.
            }
            canal.emitir("fin", Map.of("estado", fin.name(), "motivo", motivo));
            hueco.liberar();
            canal.cerrar();
            programador.schedule(() -> activas.remove(id), CONSERVAR_TRAS_FIN.toMillis(), TimeUnit.MILLISECONDS);
        }

        private static void cerrarSinRuido(AutoCloseable cerrable) {
            try {
                cerrable.close();
            } catch (Exception e) {
                // La conexión MCP ya no hace falta.
            }
        }
    }

    public static List<String> casos() {
        return java.util.Arrays.stream(Caso.values()).map(Enum::name).toList();
    }
}
