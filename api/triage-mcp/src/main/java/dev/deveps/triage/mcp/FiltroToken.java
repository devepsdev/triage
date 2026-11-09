package dev.deveps.triage.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Sin un token de investigación con el formato correcto, ninguna petición llega a MCP. Si es válido lo decide el gestor. */
final class FiltroToken implements Filter {

    static final String CLAVE = "token";

    private static final Pattern FORMATO = Pattern.compile("Bearer ([0-9a-f]{64})");

    static Optional<String> token(HttpServletRequest peticion) {
        String cabecera = peticion.getHeader("Authorization");
        if (cabecera == null) {
            return Optional.empty();
        }
        var coincidencia = FORMATO.matcher(cabecera);
        return coincidencia.matches() ? Optional.of(coincidencia.group(1)) : Optional.empty();
    }

    @Override
    public void doFilter(ServletRequest peticion, ServletResponse respuesta, FilterChain cadena) throws IOException, ServletException {
        if (token((HttpServletRequest) peticion).isEmpty()) {
            ((HttpServletResponse) respuesta).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        cadena.doFilter(peticion, respuesta);
    }
}
