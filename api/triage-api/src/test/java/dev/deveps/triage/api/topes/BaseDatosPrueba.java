package dev.deveps.triage.api.topes;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Una base H2 nueva por prueba, en modo MySQL, con las migraciones reales. */
public final class BaseDatosPrueba {

    public static final String VERSION = "mistral-small-2603";

    private BaseDatosPrueba() {
    }

    public static JdbcClient nueva() {
        JdbcDataSource datos = new JdbcDataSource();
        datos.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        Flyway.configure().dataSource(datos).load().migrate();
        return JdbcClient.create(datos);
    }

    /** Precio de prueba: no es el real, solo facilita las cuentas. */
    public static JdbcClient conPrecio() {
        JdbcClient db = nueva();
        db.sql("INSERT INTO precio_modelo VALUES (?, ?, ?, 'EUR', ?, 'prueba')")
                .param(VERSION).param(new BigDecimal("1.000000")).param(new BigDecimal("3.000000")).param(Date.valueOf(LocalDate.of(2026, 11, 9))).update();
        return db;
    }
}
