package dev.deveps.triage.api.topes;

import java.time.LocalDate;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Investigaciones del día, en base de datos: un reinicio no regala cupo. */
public final class ContadorDiario {

    private final JdbcClient db;
    private final ControlGasto calendario;
    private final int maximo;

    public ContadorDiario(JdbcClient db, ControlGasto calendario, int maximo) {
        this.db = db;
        this.calendario = calendario;
        this.maximo = maximo;
    }

    public boolean quedan() {
        LocalDate dia = calendario.hoy();
        calendario.asegurarDia(dia);
        return db.sql("SELECT investigaciones FROM gasto_dia WHERE dia = ?").param(dia).query(Integer.class).single() < maximo;
    }

    public void registrar() {
        LocalDate dia = calendario.hoy();
        calendario.asegurarDia(dia);
        db.sql("UPDATE gasto_dia SET investigaciones = investigaciones + 1 WHERE dia = ?").param(dia).update();
    }
}
