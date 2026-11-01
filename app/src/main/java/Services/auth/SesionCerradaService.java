package Services.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Server-side memory of logged-out form-auth sessions.
 *
 * <p><b>Why this exists.</b> With {@code quarkus.http.auth.session.encryption-key}
 * the form session lives inside the signed cookie, so
 * {@link io.quarkus.security.spi.runtime.AuthenticationFailure} logout only
 * clears the browser's copy: replaying the old cookie still authenticates.
 * This service records a hash of the revoked cookie so the request filter can
 * treat it as anonymous.</p>
 */
@ApplicationScoped
public class SesionCerradaService {

    private static final Logger LOG = Logger.getLogger(SesionCerradaService.class);

    /** Cookie minted by quarkus.http.auth.form; shared with the request filter. */
    public static final String COOKIE_NAME = "quarkus-credential";

    @Inject
    EntityManager em;

    /**
     * How long a tombstone is kept. Must be at least the form session timeout:
     * once the cookie itself expires the tombstone has no work left to do.
     */
    @Inject
    @ConfigProperty(name = "mercurius.auth.logout.tombstone-ttl", defaultValue = "PT12H")
    Duration tombstoneTtl;

    /** Records the cookie value presented to log-out. Null/blank is a no-op. */
    @Transactional
    public void registrar(String valorCookie) {
        String hash = hash(valorCookie);
        if (hash == null) {
            return;
        }
        try {
            em.merge(new Models.SesionCerrada(hash, Instant.now().plus(tombstoneTtl)));
            em.createQuery("delete from SesionCerrada s where s.expiraEn < :ahora")
                    .setParameter("ahora", Instant.now())
                    .executeUpdate();
        } catch (RuntimeException e) {
            // Never fail the log-out itself: the cookie is still cleared, and a
            // failed tombstone is a narrower blast radius than a 500 on logout.
            LOG.error("No se pudo registrar la sesión cerrada: " + e.getMessage()
                    + " | source=SesionCerradaService.registrar()", e);
        }
    }

    /** True when this exact cookie value was logged out and is still fresh. */
    @Transactional
    public boolean estaCerrada(String valorCookie) {
        String hash = hash(valorCookie);
        if (hash == null) {
            return false;
        }
        try {
            return em.find(Models.SesionCerrada.class, hash) != null;
        } catch (RuntimeException e) {
            LOG.error("No se pudo consultar la lista de sesiones cerradas: " + e.getMessage()
                    + " | source=SesionCerradaService.estaCerrada()", e);
            return false;
        }
    }

    private static String hash(String valorCookie) {
        if (valorCookie == null || valorCookie.isBlank()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(valorCookie.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            LOG.error("SHA-256 no disponible; el log-out no podrá invalidar la sesión", e);
            return null;
        }
    }
}
