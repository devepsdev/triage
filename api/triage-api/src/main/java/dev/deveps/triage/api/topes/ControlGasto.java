package dev.deveps.triage.api.topes;

import dev.deveps.triage.api.agente.Presupuesto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Tope de gasto diario. Antes de cada llamada al modelo se reserva el peor caso en una sola sentencia atómica,
 * así que ni con varias a la vez se puede pasar del tope. Después se cambia la reserva por el coste real.
 */
public final class ControlGasto {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final BigDecimal MILLON = BigDecimal.valueOf(1_000_000);
    private static final int ESCALA = 8;
    /** Lo mínimo que tiene sentido empezar: unas pocas rondas de una investigación. */
    private static final int TOKENS_PARA_EMPEZAR = 15_000;

    private record Precio(BigDecimal entrada, BigDecimal salida) {
        BigDecimal peor() {
            return entrada.max(salida);
        }
    }

    private final JdbcClient db;
    private final Clock reloj;
    private final String version;
    private final BigDecimal tope;
    private final Precio precioBase;

    public ControlGasto(JdbcClient db, Clock reloj, String version, BigDecimal tope) {
        this.db = db;
        this.reloj = reloj;
        this.version = version;
        this.tope = tope;
        this.precioBase = precio(version).orElseThrow(() -> new IllegalStateException("No hay precio para " + version));
    }

    private Optional<Precio> precio(String deVersion) {
        return db.sql("SELECT entrada_millon, salida_millon FROM precio_modelo WHERE version = ?")
                .param(deVersion)
                .query((fila, n) -> new Precio(fila.getBigDecimal(1), fila.getBigDecimal(2)))
                .optional();
    }

    public LocalDate hoy() {
        return LocalDate.now(reloj.withZone(MADRID));
    }

    void asegurarDia(LocalDate dia) {
        try {
            db.sql("INSERT INTO gasto_dia (dia) VALUES (?)").param(dia).update();
        } catch (DuplicateKeyException yaExiste) {
            // Lo normal a partir de la primera llamada del día.
        }
    }

    private static BigDecimal porTokens(int tokens, BigDecimal precioMillon) {
        return precioMillon.multiply(BigDecimal.valueOf(tokens)).divide(MILLON, ESCALA, RoundingMode.UP);
    }

    public boolean puedeEmpezar() {
        LocalDate dia = hoy();
        asegurarDia(dia);
        BigDecimal necesario = porTokens(TOKENS_PARA_EMPEZAR, precioBase.peor());
        return db.sql("SELECT COUNT(*) FROM gasto_dia WHERE dia = ? AND confirmado + reservado + ? <= ?")
                .param(dia).param(necesario).param(tope).query(Long.class).single() == 1;
    }

    public BigDecimal gastadoHoy() {
        LocalDate dia = hoy();
        asegurarDia(dia);
        return db.sql("SELECT confirmado FROM gasto_dia WHERE dia = ?").param(dia).query(BigDecimal.class).single();
    }

    /** Coste real de una respuesta. Si el proveedor devuelve otra versión sin precio, se usa el precio de la configurada. */
    public BigDecimal coste(int entrada, int salida, String deVersion) {
        Precio p = precio(deVersion).orElse(precioBase);
        return porTokens(entrada, p.entrada()).add(porTokens(salida, p.salida()));
    }

    /** Al arrancar no hay nada en marcha: lo reservado de un proceso anterior ya no corresponde a ninguna llamada. */
    public void limpiarReservas() {
        db.sql("UPDATE gasto_dia SET reservado = 0 WHERE reservado <> 0").update();
    }

    /** El presupuesto de una investigación. Las llamadas al modelo se hacen de una en una. */
    public Presupuesto presupuesto(String idInvestigacion) {
        return new Presupuesto() {
            private BigDecimal pendiente = BigDecimal.ZERO;
            private LocalDate diaReserva;

            @Override
            public boolean reservar(int tokensMax) {
                LocalDate dia = hoy();
                asegurarDia(dia);
                BigDecimal cantidad = porTokens(tokensMax, precioBase.peor());
                int cambiadas = db.sql("UPDATE gasto_dia SET reservado = reservado + ? WHERE dia = ? AND confirmado + reservado + ? <= ?")
                        .param(cantidad).param(dia).param(cantidad).param(tope).update();
                if (cambiadas == 0) {
                    return false;
                }
                pendiente = cantidad;
                diaReserva = dia;
                return true;
            }

            @Override
            public void liquidar(int entrada, int salida, String deVersion) {
                BigDecimal real = coste(entrada, salida, deVersion);
                db.sql("UPDATE gasto_dia SET reservado = GREATEST(reservado - ?, 0), confirmado = confirmado + ? WHERE dia = ?")
                        .param(pendiente).param(real).param(diaReserva).update();
                pendiente = BigDecimal.ZERO;
                if (entrada + salida > 0) {
                    db.sql("INSERT INTO llamada_modelo (investigacion_id, version, tokens_entrada, tokens_salida, coste, instante) VALUES (?, ?, ?, ?, ?, ?)")
                            .param(idInvestigacion).param(deVersion.isBlank() ? version : deVersion).param(entrada).param(salida).param(real)
                            .param(Timestamp.from(reloj.instant())).update();
                }
            }
        };
    }
}
