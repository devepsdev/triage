package dev.deveps.triage.api.investigacion;

import dev.deveps.triage.api.agente.Correccion;
import dev.deveps.triage.comun.Caso;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Solo lo que debe sobrevivir a un reinicio. Los eventos viven en memoria. */
public final class RepositorioInvestigacion {

    public record Activa(String id, String idEntorno) {}

    public record Contador(long total, long aciertos) {}

    private final JdbcClient db;

    public RepositorioInvestigacion(JdbcClient db) {
        this.db = db;
    }

    void crear(String id, Caso caso, int variante, Instant instante) {
        db.sql("INSERT INTO investigacion (id, caso, variante, estado, creada) VALUES (?, ?, ?, ?, ?)")
                .param(id).param(caso.name()).param(variante).param(EstadoInvestigacion.PREPARANDO.name()).param(Timestamp.from(instante)).update();
    }

    void estado(String id, EstadoInvestigacion estado) {
        db.sql("UPDATE investigacion SET estado = ? WHERE id = ?").param(estado.name()).param(id).update();
    }

    void entorno(String id, String idEntorno) {
        db.sql("UPDATE investigacion SET id_entorno = ? WHERE id = ?").param(idEntorno).param(id).update();
    }

    void corregida(String id, Correccion correccion) {
        db.sql("UPDATE investigacion SET acierto = ?, tipo_error = ?, enganado = ? WHERE id = ?")
                .param(correccion.acierto()).param(correccion.tipoError()).param(correccion.enganadoPorInyeccion()).param(id).update();
    }

    void terminar(String id, EstadoInvestigacion estado, String motivo, Instant instante) {
        db.sql("UPDATE investigacion SET estado = ?, motivo_fin = ?, terminada = ? WHERE id = ?")
                .param(estado.name()).param(motivo).param(Timestamp.from(instante)).param(id).update();
    }

    public List<Activa> activas() {
        String terminales = Arrays.stream(EstadoInvestigacion.values()).filter(EstadoInvestigacion::terminal)
                .map(e -> "'" + e.name() + "'").collect(Collectors.joining(","));
        return db.sql("SELECT id, id_entorno FROM investigacion WHERE estado NOT IN (" + terminales + ")")
                .query((fila, n) -> new Activa(fila.getString(1), fila.getString(2))).list();
    }

    /** Solo cuentan las que llegaron a corregirse: un corte de red o de presupuesto no dice nada del agente. */
    public Contador contador() {
        return db.sql("SELECT COUNT(*), COALESCE(SUM(CASE WHEN acierto THEN 1 ELSE 0 END), 0) FROM investigacion WHERE acierto IS NOT NULL")
                .query((fila, n) -> new Contador(fila.getLong(1), fila.getLong(2))).single();
    }

    Optional<EstadoInvestigacion> estadoDe(String id) {
        return db.sql("SELECT estado FROM investigacion WHERE id = ?").param(id).query(String.class).optional().map(EstadoInvestigacion::valueOf);
    }
}
