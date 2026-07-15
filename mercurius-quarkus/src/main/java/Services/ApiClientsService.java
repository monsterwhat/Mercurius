package Services;

import Models.ApiClients;
import at.favre.lib.crypto.bcrypt.BCrypt;
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
public class ApiClientsService extends GService<ApiClients> {

    @Override
    protected @Nonnull Class<ApiClients> getEntityClass() {
        return ApiClients.class;
    }

    @PostConstruct
    @Transactional
    public void init() {
        try {
            if (count() == 0) {
                ApiClients defaultClient = new ApiClients();
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
                alertasService.registrarAlerta("Info", "Default API client 'mercurius-frontend' created", null, 0, "ApiClientsService.init()", null, null);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error seeding default API client: " + e.getMessage(), null, 0, "ApiClientsService.init()", null, e.getMessage());
        }
    }

    /**
     * Find API client by client_id (the public identifier, not the DB id).
     */
    public ApiClients findByClientId(String clientId) {
        try {
            TypedQuery<ApiClients> query = em.createQuery(
                "SELECT a FROM ApiClients a WHERE a.clientId = :clientId AND a.status = true",
                ApiClients.class);
            query.setParameter("clientId", clientId);
            List<ApiClients> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error finding ApiClient by clientId: " + e.getMessage(), null, 0, "ApiClientsService.findByClientId()", null, e.getMessage());
            return null;
        }
    }

    /**
     * Find all active API clients.
     */
    public List<ApiClients> findActive() {
        try {
            TypedQuery<ApiClients> query = em.createQuery(
                "SELECT a FROM ApiClients a WHERE a.status = true",
                ApiClients.class);
            return query.getResultList();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error listing active ApiClients: " + e.getMessage(), null, 0, "ApiClientsService.findActive()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Client secret verification error: " + e.getMessage(), null, 0, "ApiClientsService.verifySecret()", null, e.getMessage());
            return false;
        }
    }
}
