package dev.deveps.triage.api.topes;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Tope por IP y día. Solo guarda una huella con sal diaria, en memoria: al cambiar de día o reiniciar, no queda nada. */
public final class LimitadorIp {

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    private final Clock reloj;
    private final int maximo;
    private final SecureRandom azar = new SecureRandom();
    private final Map<String, Integer> usos = new HashMap<>();
    private LocalDate dia;
    private byte[] sal;

    public LimitadorIp(Clock reloj, int maximo) {
        this.reloj = reloj;
        this.maximo = maximo;
    }

    public synchronized boolean permite(String ip) {
        renovarSiCambiaElDia();
        return usos.getOrDefault(huella(ip), 0) < maximo;
    }

    public synchronized void registrar(String ip) {
        renovarSiCambiaElDia();
        usos.merge(huella(ip), 1, Integer::sum);
    }

    synchronized Set<String> huellas() {
        return Set.copyOf(usos.keySet());
    }

    private void renovarSiCambiaElDia() {
        LocalDate actual = LocalDate.now(reloj.withZone(MADRID));
        if (!actual.equals(dia)) {
            dia = actual;
            sal = new byte[32];
            azar.nextBytes(sal);
            usos.clear();
        }
    }

    private String huella(String ip) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(sal, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(ip.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
