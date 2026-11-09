package dev.deveps.triage.api;

import dev.deveps.triage.api.agente.Agente;
import dev.deveps.triage.api.agente.Corrector;
import dev.deveps.triage.api.agente.HerramientasMcp;
import dev.deveps.triage.api.bateria.ComandoBateria;
import dev.deveps.triage.api.bateria.ExportadorGrabacion;
import dev.deveps.triage.api.investigacion.ControladorInvestigacion;
import dev.deveps.triage.api.investigacion.Recuperacion;
import dev.deveps.triage.api.investigacion.RepositorioInvestigacion;
import dev.deveps.triage.api.investigacion.ServicioInvestigacion;
import dev.deveps.triage.api.modelo.MistralProveedor;
import dev.deveps.triage.api.modelo.ProveedorModelo;
import dev.deveps.triage.api.topes.Admision;
import dev.deveps.triage.api.topes.ContadorDiario;
import dev.deveps.triage.api.topes.ControlGasto;
import dev.deveps.triage.api.topes.HuecoUnico;
import dev.deveps.triage.api.topes.LimitadorIp;
import dev.deveps.triage.api.topes.Turnstile;
import dev.deveps.triage.comun.ClienteGestor;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootApplication
public class TriageApiApplication {

    private static final URI MISTRAL = URI.create("https://api.mistral.ai/v1/chat/completions");
    private static final URI SITEVERIFY = URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify");

    public static void main(String[] argumentos) throws IOException {
        Optional<String> grabacion = ExportadorGrabacion.solicitada(argumentos);
        if (grabacion.isPresent()) {
            System.out.println("Escrita: " + ExportadorGrabacion.exportar(grabacion.get(), Path.of("")));
        } else if (Arrays.asList(argumentos).contains("--bateria")) {
            // Sin servidor web: la API de las visitas puede seguir en marcha en su puerto.
            System.exit(SpringApplication.exit(new SpringApplicationBuilder(TriageApiApplication.class).web(WebApplicationType.NONE).run(argumentos)));
        } else {
            SpringApplication.run(TriageApiApplication.class, argumentos);
        }
    }

    @Bean
    ComandoBateria comandoBateria(ProveedorModelo proveedor, ServicioInvestigacion.Gestor gestor, JdbcClient db, HuecoUnico hueco, ScheduledExecutorService programador, Clock reloj,
            @Value("${triage.mcp-url}") String mcp, @Value("${triage.mistral-clave}") String clave) {
        return new ComandoBateria(new ComandoBateria.Dependencias(proveedor, gestor, token -> new HerramientasMcp(mcp, token), db, hueco, programador, reloj, !clave.isBlank(), Path.of("")));
    }

    @Bean
    Clock reloj() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient clienteHttp() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService programador() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    @Bean
    ProveedorModelo proveedor(HttpClient http, @Value("${triage.mistral-clave}") String clave) {
        return new MistralProveedor(MISTRAL, clave, http, Duration.ofSeconds(30));
    }

    @Bean
    Agente agente(ProveedorModelo proveedor, @Value("${triage.modelo}") String modelo) {
        return new Agente(proveedor, modelo);
    }

    @Bean
    ControlGasto controlGasto(JdbcClient db, Clock reloj, @Value("${triage.modelo}") String modelo, @Value("${triage.gasto-diario}") BigDecimal tope) {
        return new ControlGasto(db, reloj, modelo, tope);
    }

    @Bean
    HuecoUnico huecoUnico(@Value("${triage.hueco-archivo}") Path archivo) {
        return new HuecoUnico(archivo);
    }

    @Bean
    Admision admision(HttpClient http, Clock reloj, JdbcClient db, ControlGasto gasto, HuecoUnico hueco,
            @Value("${triage.turnstile-secreto}") String secreto, @Value("${triage.mistral-clave}") String clave,
            @Value("${triage.maximo-por-ip}") int porIp, @Value("${triage.maximo-diario}") int diario) {
        return new Admision(new Turnstile(http, SITEVERIFY, secreto), new LimitadorIp(reloj, porIp), new ContadorDiario(db, gasto, diario), gasto, hueco, !clave.isBlank());
    }

    @Bean
    ServicioInvestigacion.Gestor gestor(@Value("${triage.control-sock}") Path socket) {
        return new ClienteGestor(socket)::pedir;
    }

    @Bean
    RepositorioInvestigacion repositorioInvestigacion(JdbcClient db) {
        return new RepositorioInvestigacion(db);
    }

    @Bean
    ServicioInvestigacion servicioInvestigacion(ServicioInvestigacion.Gestor gestor, Agente agente, ControlGasto gasto, HuecoUnico hueco,
            RepositorioInvestigacion repositorio, ScheduledExecutorService programador, Clock reloj, @Value("${triage.mcp-url}") String mcp) {
        ServicioInvestigacion.Tiempos tiempos = new ServicioInvestigacion.Tiempos(Duration.ofSeconds(90), Duration.ofMinutes(5), Duration.ofSeconds(30), Duration.ofSeconds(5));
        return new ServicioInvestigacion(gestor, token -> new HerramientasMcp(mcp, token), agente, new Corrector(), gasto, hueco, repositorio, programador, reloj, tiempos,
                variantes -> ThreadLocalRandom.current().nextInt(1, variantes + 1));
    }

    @Bean
    ControladorInvestigacion controladorInvestigacion(Admision admision, ServicioInvestigacion servicio, RepositorioInvestigacion repositorio,
            @Value("${triage.turnstile-sitekey}") String sitekey) {
        return new ControladorInvestigacion(admision, servicio, repositorio, sitekey);
    }

    @Bean
    Recuperacion recuperacion(RepositorioInvestigacion repositorio, ServicioInvestigacion.Gestor gestor, ControlGasto gasto, Clock reloj) {
        return new Recuperacion(repositorio, gestor, gasto, reloj);
    }
}
