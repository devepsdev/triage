package dev.deveps.triage.api.topes;

import dev.deveps.triage.comun.Caso;
import java.util.Arrays;
import java.util.Optional;

/**
 * Decide si una visita puede empezar, de lo más barato a lo más caro. El cupo solo se gasta si consigue el hueco:
 * quien ocupa el hueco es además el único que escribe, así que los contadores no pueden pisarse.
 */
public final class Admision {

    private final Turnstile captcha;
    private final LimitadorIp ip;
    private final ContadorDiario diario;
    private final ControlGasto gasto;
    private final HuecoUnico hueco;
    private final boolean hayClaveModelo;

    public Admision(Turnstile captcha, LimitadorIp ip, ContadorDiario diario, ControlGasto gasto, HuecoUnico hueco, boolean hayClaveModelo) {
        this.captcha = captcha;
        this.ip = ip;
        this.diario = diario;
        this.gasto = gasto;
        this.hueco = hueco;
        this.hayClaveModelo = hayClaveModelo;
    }

    public boolean configurada() {
        return hayClaveModelo && captcha.configurado();
    }

    /** Lo que se le dice a quien abre la página, sin gastar nada. */
    public Optional<Rechazo> motivoNoDisponible() {
        if (!configurada()) {
            return Optional.of(Rechazo.NO_DISPONIBLE);
        }
        if (!diario.quedan()) {
            return Optional.of(Rechazo.LIMITE_DIARIO);
        }
        if (!gasto.puedeEmpezar()) {
            return Optional.of(Rechazo.GASTO);
        }
        return hueco.libre() ? Optional.empty() : Optional.of(Rechazo.OCUPADO);
    }

    /** Vacío significa admitida, y entonces el hueco queda adquirido: hay que liberarlo al terminar. */
    public Optional<Rechazo> comprobar(String caso, String tokenCaptcha, String direccion) {
        if (!configurada()) {
            return Optional.of(Rechazo.NO_DISPONIBLE);
        }
        if (Arrays.stream(Caso.values()).noneMatch(c -> c.name().equals(caso))) {
            return Optional.of(Rechazo.CASO_INVALIDO);
        }
        if (!captcha.valido(tokenCaptcha)) {
            return Optional.of(Rechazo.CAPTCHA);
        }
        if (!ip.permite(direccion)) {
            return Optional.of(Rechazo.LIMITE_IP);
        }
        if (!diario.quedan()) {
            return Optional.of(Rechazo.LIMITE_DIARIO);
        }
        if (!gasto.puedeEmpezar()) {
            return Optional.of(Rechazo.GASTO);
        }
        if (!hueco.adquirir()) {
            return Optional.of(Rechazo.OCUPADO);
        }
        ip.registrar(direccion);
        diario.registrar();
        return Optional.empty();
    }
}
