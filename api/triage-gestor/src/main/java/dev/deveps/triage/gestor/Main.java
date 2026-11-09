package dev.deveps.triage.gestor;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Servicio con dos sockets. Variables: TRIAGE_CONTROL_SOCK, TRIAGE_LECTURA_SOCK y, en el servidor,
 * TRIAGE_GRUPO_CONTROL y TRIAGE_GRUPO_LECTURA. Con el argumento --barrer solo borra lo caducado y termina.
 */
public final class Main {

    private static final Duration VIDA = Duration.ofMinutes(6);

    private Main() {
    }

    public static void main(String[] argumentos) throws Exception {
        Ejecutor docker = new EjecutorProceso();
        Barrendero barrendero = new Barrendero(docker);
        Clock reloj = Clock.systemUTC();

        if (argumentos.length == 1 && argumentos[0].equals("--barrer")) {
            barrendero.barrerCaducados(reloj.instant());
            return;
        }

        barrendero.barrerTodo();
        Registro registro = new Registro(reloj, VIDA);
        Entornos entornos = new Entornos(registro, docker, Duration.ofSeconds(1));

        Casos casos = new Casos(docker, Duration.ofSeconds(1));

        try (ServidorSocket control = servidor("TRIAGE_CONTROL_SOCK", "TRIAGE_GRUPO_CONTROL", Protocolo.control(entornos, casos));
             ServidorSocket lectura = servidor("TRIAGE_LECTURA_SOCK", "TRIAGE_GRUPO_LECTURA", Protocolo.lectura(new Consultas(registro, docker, reloj)))) {
            control.arrancar();
            lectura.arrancar();
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(() -> barrer(entornos, barrendero, reloj), 15, 15, TimeUnit.SECONDS);
            new CountDownLatch(1).await();
        }
    }

    /** Una excepción sin capturar cancelaría la tarea periódica y los entornos dejarían de caducar. */
    private static void barrer(Entornos entornos, Barrendero barrendero, Clock reloj) {
        try {
            entornos.destruirVencidos();
            barrendero.barrerCaducados(reloj.instant());
        } catch (RuntimeException e) {
            System.err.println("Fallo al barrer: " + e.getClass().getSimpleName());
        }
    }

    private static ServidorSocket servidor(String ruta, String grupo, Protocolo protocolo) {
        return new ServidorSocket(Path.of(System.getenv(ruta)), protocolo, Optional.ofNullable(System.getenv(grupo)));
    }
}
