package dev.deveps.triage.gestor;

import dev.deveps.triage.comun.Caso;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Entornos vivos. Es el único sitio donde un token se traduce a un entorno. */
final class Registro {

    private final SecureRandom azar = new SecureRandom();
    private final Map<String, Entorno> entornos = new ConcurrentHashMap<>();
    private final Clock reloj;
    private final Duration vida;

    Registro(Clock reloj, Duration vida) {
        this.reloj = reloj;
        this.vida = vida;
    }

    Entorno registrar(Caso caso, int variante) {
        Entorno entorno = new Entorno(aleatorio(6), aleatorio(32), caso, variante, reloj.instant().plus(vida));
        entornos.put(entorno.id(), entorno);
        return entorno;
    }

    Optional<Entorno> porId(String id) {
        return Optional.ofNullable(entornos.get(id));
    }

    Optional<Entorno> porToken(String token) {
        return entornos.values().stream()
                .filter(e -> e.token().equals(token) && !vencido(e))
                .findFirst();
    }

    void quitar(String id) {
        entornos.remove(id);
    }

    List<Entorno> vencidos() {
        return entornos.values().stream().filter(this::vencido).toList();
    }

    private boolean vencido(Entorno entorno) {
        return reloj.instant().isAfter(entorno.caduca());
    }

    private String aleatorio(int bytes) {
        byte[] datos = new byte[bytes];
        azar.nextBytes(datos);
        return HexFormat.of().formatHex(datos);
    }
}
