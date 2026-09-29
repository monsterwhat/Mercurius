package Utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Throttles repeated credential verification attempts, per account AND per
 * source address.
 *
 * <p>Distinct from {@link RateLimiter}, which budgets an <em>API client's</em>
 * request volume from its own {@code ClientesApi} limits. This one bounds how
 * often a single account's password can be <em>guessed</em>, which is what a
 * BCrypt verification endpoint needs and what a volume budget does not provide:
 * a caller with a generous per-minute limit can still make millions of guesses
 * across the hour.</p>
 *
 * <p>Both keys count toward the same budget. The account key stops one target
 * from being ground down; the address key stops one caller from spraying many
 * accounts (credential stuffing), which the account key alone would not catch
 * because each victim would stay under its own threshold.</p>
 *
 * <p>Backed by Caffeine, so state is per instance and self-evicting. Behind more
 * than one node the effective limit is per node, not global — a deliberate
 * trade-off documented on the class, not an oversight: a shared store would add
 * a dependency this limiter does not otherwise need.</p>
 */
@ApplicationScoped
public class IntentosDeCredencial {

    private static final Logger LOG = Logger.getLogger(IntentosDeCredencial.class);

    /** Failed attempts per key inside the window before the key is locked. */
    @ConfigProperty(name = "mercurius.auth.intentos.max", defaultValue = "5")
    int maxIntentos;

    /** How long a key stays locked once the threshold is reached. */
    @ConfigProperty(name = "mercurius.auth.intentos.bloqueo-minutos", defaultValue = "15")
    long bloqueoMinutos;

    /** Window over which failures are counted. */
    @ConfigProperty(name = "mercurius.auth.intentos.ventana-minutos", defaultValue = "15")
    long ventanaMinutos;

    private Cache<String, Integer> fallosPorClave;

    @PostConstruct
    void init() {
        fallosPorClave = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(Math.max(1, ventanaMinutos), TimeUnit.MINUTES)
                .build();
    }

    /** Account-scoped key. */
    public static String claveCuenta(String username) {
        return "cuenta:" + (username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Address-scoped key, so one caller cannot spray many accounts. */
    public static String claveDireccion(String direccion) {
        return "dir:" + (direccion == null ? "desconocida" : direccion.trim());
    }

    /**
     * Records a failed attempt against both keys.
     *
     * @return {@code true} when this failure tipped the key over the threshold.
     */
    public boolean registrarFallo(String username, String direccion) {
        boolean bloqueo = incrementar(claveCuenta(username)) | incrementar(claveDireccion(direccion));
        if (bloqueo) {
            LOG.warn("Bloqueo temporal por intentos de credenciales"
                    + " | source=IntentosDeCredencial.registrarFallo()"
                    + " | usuario=" + username
                    + " | limite=" + maxIntentos);
        }
        return bloqueo;
    }

    private boolean incrementar(String clave) {
        // Atomic read-modify-write: two concurrent requests for the same key must
        // not both read n and both store n+1, or attempts are silently lost and
        // the limit is higher than configured.
        java.util.concurrent.atomic.AtomicInteger total =
                new java.util.concurrent.atomic.AtomicInteger();
        fallosPorClave.asMap().compute(clave, (k, previo) -> {
            int ahora = (previo == null ? 0 : previo) + 1;
            total.set(ahora);
            return ahora;
        });
        return total.get() > maxIntentos;
    }

    /**
     * Whether the key is currently allowed to attempt a verification.
     *
     * @return {@code null} when allowed, otherwise the number of seconds until
     *         the counter ages out.
     */
    public java.lang.Long restanteBloqueo(String username, String direccion) {
        Integer cuenta = fallosPorClave.getIfPresent(claveCuenta(username));
        if (cuenta != null && cuenta > maxIntentos) {
            return bloqueoMinutos * 60;
        }
        Integer dir = fallosPorClave.getIfPresent(claveDireccion(direccion));
        if (dir != null && dir > maxIntentos) {
            return bloqueoMinutos * 60;
        }
        return null;
    }

    /**
     * Clears both counters after a SUCCESSFUL verification, so a legitimate user
     * who mistyped twice is not left one mistake away from a lockout.
     */
    public void registrarExito(String username, String direccion) {
        fallosPorClave.invalidate(claveCuenta(username));
        fallosPorClave.invalidate(claveDireccion(direccion));
    }

    /** Test seam: drops all counters. */
    public void limpiar() {
        fallosPorClave.invalidateAll();
    }
}
