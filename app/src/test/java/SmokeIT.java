import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * T2 smoke verification against the local PostgreSQL test database
 * (localhost:5433/mercurius_test, Dev Services disabled — no Docker).
 *
 * <p><b>File/class-name note (corrected 2026-09-28):</b> an earlier comment
 * claimed surefire's default includes never discover these classes because the
 * file is named {@code SmokeIT.java}, and documented a "KNOWN BLOCKER" about a
 * pom execution with no goals. Both claims were wrong: surefire scans compiled
 * classes, so {@code SmokeSeedTest.class} and {@code SmokePgConnectivityTest.class}
 * match the default {@code **}/{@code *Test} include regardless of the source
 * file name, and the pom contains no such execution and no
 * {@code excludedGroups}. Both classes run and pass in a plain
 * {@code mvn test} (verified in the surefire reports). The note is kept in this
 * corrected form so nobody "fixes" the file name and churns the history.</p>
 *
 * <p>Both classes share a single Quarkus boot (identical configuration, no
 * {@code @TestProfile}).</p>
 */
@QuarkusTest
class SmokeSeedTest {

    @Inject
    EntityManager em;

    /**
     * Test A — schema + seed proof: exactly one seeded 'admin' user exists
     * after Hibernate drop-and-create plus import-test.sql.
     */
    @Test
    @Transactional
    void seededAdminUserCountIsOne() {
        Long count = em.createQuery(
                "SELECT COUNT(u) FROM Usuarios u WHERE u.username = 'admin'", Long.class)
                .getSingleResult();
        assertEquals(1L, count, "import-test.sql must seed exactly one 'admin' user");
    }
}

@QuarkusTest
class SmokePgConnectivityTest {

    @Inject
    EntityManager em;

    /**
     * Test B — live PostgreSQL round-trip proof: a native SELECT 1 executed
     * through the datasource returns 1.
     */
    @Test
    @Transactional
    void nativeSelectOneRoundTrips() {
        Object result = em.createNativeQuery("SELECT 1").getSingleResult();
        assertNotNull(result);
        assertEquals(1, ((Number) result).intValue(), "SELECT 1 must return 1 from PostgreSQL");
    }
}
