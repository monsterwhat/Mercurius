package Services;

import Models.ClientesApi;
import at.favre.lib.crypto.bcrypt.BCrypt;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Service for OAuth2 API clients (client_credentials grant type).
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

    @PostConstruct
    @Transactional
    public void init() {
        try {
            if (count() == 0) {
                ClientesApi defaultClient = new ClientesApi();
                defaultClient.setClientId("mercurius-frontend");
                String plainSecret = "dev-secret-do-not-use-in-production";
                defaultClient.setClientSecret(
                    BCrypt.withDefaults().hashToString(12, plainSecret.toCharArray()));
                defaultClient.setScopes("[\"mercatus\",\"accounting\"]");
                defaultClient.setRateLimitPerMin(60);
                defaultClient.setRateLimitPerHour(1000);
                defaultClient.setStatus(true);
                defaultClient.setCreatedAt(new Date());
                defaultClient.setName("Default Dev Client");
                create(defaultClient);
                                LOG.info("Default API client 'mercurius-frontend' created" + " | source=" + "ApiClientsService.init()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (RuntimeException e) {
                        LOG.warn("Error seeding default API client: " + e.getMessage() + " | source=" + "ApiClientsService.init()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
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
