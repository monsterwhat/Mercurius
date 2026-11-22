package Services.config;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Validacion de arranque para secretos y CORS de produccion.
 *
 * <p>Los valores por defecto existen para que dev/test arranquen sin
 * configuracion. En produccion, sin embargo, un JWT firmado con la clave de
 * ejemplo o una cookie de sesion cifrada con la clave de ejemplo son una
 * vulnerabilidad, no una comodidad. El arranque falla con un mensaje que dice
 * QUE propiedad falta, nunca con el valor del secreto.</p>
 *
 * <p>La validacion de CORS rechaza {@code *} en produccion y exige origenes
 * absolutos http/https sin ruta. Un origen invalido no debe degradarse a
 * "permite todo".</p>
 */
@ApplicationScoped
public class ValidacionConfiguracionProduccion {

    private static final Logger LOG = Logger.getLogger(ValidacionConfiguracionProduccion.class);

    /** Valor de ejemplo del JWT en application.properties. */
    static final String JWT_DEV = "changeme-this-is-not-a-secure-key-in-production-256bits!!";

    /** Valor de ejemplo de la clave de sesion. */
    static final String SESION_DEV = "dev-only-32bytes-minimum-key-change-me!";

    private static final int LONGITUD_MINIMA = 32;

    @ConfigProperty(name = "mercatus.jwt.secret")
    String jwtSecret;

    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    String sessionKey;

    @ConfigProperty(name = "quarkus.http.cors.origins", defaultValue = "")
    String corsOrigens;

    void onStart(@Observes StartupEvent evento) {
        boolean produccion = LaunchMode.current().isProduction();
        List<String> errores = validar(produccion, jwtSecret, sessionKey, corsOrigens);
        if (!errores.isEmpty()) {
            for (String error : errores) {
                LOG.error("Configuracion de produccion invalida: " + error);
            }
            throw new IllegalStateException(
                    "Arranque abortado: " + errores.size()
                            + " problema(s) de configuracion de produccion. Revise los logs.");
        }
        if (produccion) {
            LOG.info("Configuracion de produccion validada: secretos y CORS presentes.");
        }
    }

    /**
     * Reglas puras para poder probarlas sin arrancar Quarkus.
     *
     * @param produccion true solo en el perfil prod
     * @return lista de errores; vacia si la configuracion es aceptable
     */
    @Nonnull
    public static List<String> validar(boolean produccion,
                                       String jwtSecret,
                                       String sessionKey,
                                       String corsOrigens) {
        if (!produccion) {
            return List.of();
        }
        List<String> errores = new ArrayList<>();

        if (jwtSecret == null || jwtSecret.isBlank()) {
            errores.add("mercatus.jwt.secret es obligatorio en produccion.");
        } else if (JWT_DEV.equals(jwtSecret.trim())
                || jwtSecret.toLowerCase(Locale.ROOT).contains("changeme")) {
            errores.add("mercatus.jwt.secret usa el valor de ejemplo; configure uno propio.");
        } else if (jwtSecret.trim().length() < LONGITUD_MINIMA) {
            errores.add("mercatus.jwt.secret debe tener al menos 32 caracteres.");
        }

        if (sessionKey == null || sessionKey.isBlank()) {
            errores.add("quarkus.http.auth.session.encryption-key es obligatorio en produccion.");
        } else if (SESION_DEV.equals(sessionKey.trim())
                || sessionKey.toLowerCase(Locale.ROOT).contains("change-me")) {
            errores.add("quarkus.http.auth.session.encryption-key usa el valor de ejemplo.");
        } else if (sessionKey.trim().length() < LONGITUD_MINIMA) {
            errores.add("quarkus.http.auth.session.encryption-key debe tener al menos 32 caracteres.");
        }

        if (corsOrigens == null || corsOrigens.isBlank()) {
            errores.add("quarkus.http.cors.origins es obligatorio en produccion.");
        } else {
            for (String origen : corsOrigens.split(",")) {
                String valor = origen.trim();
                if (valor.isEmpty()) {
                    continue;
                }
                if ("*".equals(valor)) {
                    errores.add("quarkus.http.cors.origins no puede ser '*' en produccion.");
                    continue;
                }
                if (!origenValido(valor)) {
                    errores.add("Origen CORS invalido: '" + valor
                            + "'. Use esquema http/https, host y puerto opcional, sin ruta.");
                }
            }
        }
        return List.copyOf(errores);
    }

    private static boolean origenValido(String origen) {
        try {
            URI uri = new URI(origen);
            String esquema = uri.getScheme();
            if (esquema == null || !(esquema.equals("http") || esquema.equals("https"))) {
                return false;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return false;
            }
            return uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath());
        } catch (URISyntaxException e) {
            LOG.debugf("Origen CORS no parseable: %s (%s)", origen, e.getMessage());
            return false;
        }
    }
}
