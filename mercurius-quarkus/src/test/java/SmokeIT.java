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
 * <p><b>File/class-name note:</b> the task mandates this file be
 * {@code SmokeIT.java}, but surefire's default class includes ({@code *Test}
 * patterns) never discover a class named {@code SmokeIT}; the two required
 * checks therefore live in package-private {@code @QuarkusTest} classes whose
 * names satisfy discovery, housed in this mandated file.</p>
 *
 * <p><b>KNOWN BLOCKER (pre-existing T1 defect):</b> the pom's
 * {@code quarkus-tests} surefire execution declares a {@code <configuration>}
 * but NO {@code <goals>} binding, so Maven binds nothing to it and it never
 * runs during the {@code mvn test} lifecycle (verified: only
 * {@code default-test} logs an execution header, while
 * {@code mvn surefire:test@quarkus-tests} discovers and runs these classes
 * green). Meanwhile {@code default-test}'s
 * {@code excludedGroups=io.quarkus.test.junit.QuarkusTest} filters these
 * classes out (surefire's FQCN filter is meta-annotation-aware — a composed
 * annotation workaround was tried and rejected). Net effect: until the
 * execution gains {@code <goals><goal>test</goal></goals>}, these smoke tests
 * are skipped by plain {@code mvn test} (43 tests) and must be invoked via
 * {@code mvn surefire:test@quarkus-tests} (45 tests). Pom edits were
 * explicitly out of scope for T2; the verified one-line fix is documented in
 * .omo/evidence/t2/diag-pom-with-goals.log (45/45 green with the binding
 * added temporarily, then reverted).</p>
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
                "SELECT COUNT(u) FROM Users u WHERE u.username = 'admin'", Long.class)
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
