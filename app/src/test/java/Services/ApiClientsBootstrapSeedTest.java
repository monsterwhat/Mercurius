package Services;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Boot must NOT provision an API client with a secret known to the repository.
 *
 * <p>Behavioral pin for the removal of {@code ApiClientsService.init()}, which
 * used to run {@code @PostConstruct} in EVERY profile and create the client
 * {@code mercurius-frontend} with the hardcoded secret
 * {@code dev-secret-do-not-use-in-production} and the scopes
 * {@code ["mercatus","accounting"]}. That combination let anyone who had read
 * the source mint first-party API tokens against a fresh deployment.</p>
 *
 * <p>The database is seeded fresh per test run by
 * {@code %test.quarkus.hibernate-orm.schema-management.strategy=drop-and-create}
 * plus {@code import-test.sql}, so "nothing provisioned it" is exactly the
 * post-boot state this test asserts on.</p>
 */
@QuarkusTest
@DisplayName("ApiClientsService: arranque sin cliente sembrado")
class ApiClientsBootstrapSeedTest {

    private static final String CLIENT_ID_ANTES = "mercurius-frontend";
    private static final String SECRETO_ANTES = "dev-secret-do-not-use-in-production";

    @Inject
    ApiClientsService apiClientsService;

    @Test
    @DisplayName("el cliente mercurius-frontend ya no se crea en el arranque")
    void noSeSiembraElClienteDeDesarrollo() {
        assertThat(apiClientsService.findByClientId(CLIENT_ID_ANTES))
                .as("el arranque no debe crear el cliente %s", CLIENT_ID_ANTES)
                .isNull();
    }

    @Test
    @DisplayName("ningun cliente activo acepta el secreto de desarrollo retirado")
    void ningunClienteActivoAceptaElSecretoRetirado() {
        assertThat(apiClientsService.findActive())
                .as("no debe quedar ningun cliente activo con el secreto retirado")
                .allSatisfy(cliente -> assertThat(
                        apiClientsService.verifySecret(SECRETO_ANTES, cliente.getClientSecret()))
                        .as("el cliente %s no debe validar el secreto retirado", cliente.getClientId())
                        .isFalse());
    }
}
