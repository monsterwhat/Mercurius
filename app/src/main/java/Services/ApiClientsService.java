package Services;

import Models.ClientesApi;
import at.favre.lib.crypto.bcrypt.BCrypt;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.Collections;
import java.util.List;

/**
 * Service for OAuth2 API clients (client_credentials grant type).
 *
 * <p><b>No bootstrap seed.</b> This service previously ran a
 * {@code @PostConstruct} that created a {@code mercurius-frontend} client whose
 * secret was the literal {@code dev-secret-do-not-use-in-production}, carrying
 * the {@code mercatus} and {@code accounting} scopes. That ran in every
 * profile, so any deployment that never provisioned its own client shipped with
 * a publicly known credential able to mint first-party API tokens.</p>
 *
 * <p>Provisioning is now explicit: an administrator creates the first client
 * through the normal user-management path, which BCrypt-hashes the secret on
 * the way in. Boot deliberately leaves {@code clientes_api} empty, and an
 * installation with no client correctly answers 401 on {@code /oauth/token} and
 * on {@code /api/v1/**} instead of accepting a guessable secret.</p>
 *
 * @author Al
 */
@Named
@ApplicationScoped
public class ApiClientsService extends GService<ClientesApi> {

    private static final Logger LOG = Logger.getLogger(ApiClientsService.class);

    @Override
    protected @Nonnull Class<ClientesApi> getEntityClass() {
        return ClientesApi.class;
    }

    /**
     * Find API client by client_id (the public identifier, not the DB id).
     */
    public ClientesApi findByClientId(String clientId) {
        try {
            TypedQuery<ClientesApi> query = em.createQuery(
                "SELECT a FROM ClientesApi a WHERE a.clientId = :clientId AND a.status = true",
                ClientesApi.class);
            query.setParameter("clientId", clientId);
            List<ClientesApi> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (RuntimeException e) {
                        LOG.warn("Error finding ApiClient by clientId: " + e.getMessage() + " | source=" + "ApiClientsService.findByClientId()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Find all active API clients.
     */
    public List<ClientesApi> findActive() {
        try {
            TypedQuery<ClientesApi> query = em.createQuery(
                "SELECT a FROM ClientesApi a WHERE a.status = true",
                ClientesApi.class);
            return query.getResultList();
        } catch (RuntimeException e) {
                        LOG.warn("Error listing active ClientesApi: " + e.getMessage() + " | source=" + "ApiClientsService.findActive()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    /**
     * Verify a BCrypt-hashed client secret against a plain-text input.
     */
    public boolean verifySecret(String plainSecret, String hashedSecret) {
        try {
            BCrypt.Result result = BCrypt.verifyer().verify(plainSecret.toCharArray(), hashedSecret);
            return result.verified;
        } catch (RuntimeException e) {
                        LOG.warn("Client secret verification error: " + e.getMessage() + " | source=" + "ApiClientsService.verifySecret()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }
}
