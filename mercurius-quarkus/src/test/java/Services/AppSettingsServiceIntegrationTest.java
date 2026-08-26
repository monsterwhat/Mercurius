package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import Models.AppSettings;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T8 — integration coverage for {@link AppSettingsService} against the local
 * PostgreSQL test database (mercurius_test, no Docker).
 *
 * <p>Every test runs inside a {@link TestTransaction} rolled back at the end.
 * Programmatic fixtures are created INSIDE each test method (never in
 * @BeforeEach: Quarkus runs @BeforeEach outside the test transaction,
 * quarkusio/quarkus discussion #40119, so anything persisted there would
 * commit and leak). import-test.sql seeds NO appsettings row and the schema
 * is dropped-and-created on every boot, so each scenario starts from an empty
 * table inside its own transaction.</p>
 *
 * <p><b>Characterized quirks (behavior preserved, NOT fixed — plan
 * guardrail):</b></p>
 * <ul>
 *   <li>{@code disable()} calls {@code em.find(class, entity)} passing the
 *       ENTITY as id — only safe on a managed instance (em.contains==true).
 *       Tests therefore always call it right after create(), within the same
 *       persistence context.</li>
 *   <li>Services swallow PersistenceException into alertasService, so a
 *       duplicate NombrePerfil insert through the service does NOT propagate.
 *       After such a failure PostgreSQL aborts the current transaction, which
 *       is why those scenarios perform NO further DB access.</li>
 * </ul>
 *
 * <p>Scenarios (7): create→find→update→delete round-trip; returnCurrent
 * tracking; disable keeps row flagged inactive; findOrCreateCurrent creation
 * + idempotency; listPage paging edges + count consistency; duplicate
 * NombrePerfil surfaces PersistenceException through the raw EntityManager;
 * duplicate NombrePerfil swallowed by the service contract.</p>
 */
@QuarkusTest
@TestTransaction
@Tag("integration-services")
class AppSettingsServiceIntegrationTest {

    @Inject
    AppSettingsService appSettingsService;

    @Inject
    EntityManager em;

    /**
     * Escenario 1 — ciclo completo crear→buscar→actualizar→eliminar de un
     * perfil de configuración.
     */
    @Test
    void createFindUpdateDeleteRoundTrip() {
        AppSettings created = buildSettings("IT Perfil Roundtrip");
        appSettingsService.create(created);
        int id = created.getId();
        assertTrue(id > 0, "IDENTITY insert must assign the Id immediately");

        AppSettings found = appSettingsService.find(id);
        assertNotNull(found);
        assertEquals("IT Perfil Roundtrip", found.getNombrePerfil());
        assertEquals("it.perfil.roundtrip@mercurius.local", found.getCorreoElectronico());
        assertEquals(2, found.getCompletedSteps(),
                "primitive completedSteps must survive persistence");
        assertEquals(0, new BigDecimal("5.00").compareTo(found.getCashbackPercentage()),
                "cashback must round-trip (compareTo: DB numeric scale may differ)");

        found.setCorreoElectronico("it-updated@mercurius.local");
        found.setNombreNegocio("Negocio IT Editado");
        appSettingsService.update(found);

        AppSettings updated = appSettingsService.find(id);
        assertNotNull(updated);
        assertEquals("it-updated@mercurius.local", updated.getCorreoElectronico());
        assertEquals("Negocio IT Editado", updated.getNombreNegocio());

        appSettingsService.delete(updated);
        assertNull(appSettingsService.find(id), "deleted settings row must not be findable");
    }

    /**
     * Escenario 2 — returnCurrent devuelve la fila activa y deja de
     * devolverla cuando se desactiva (camino NoResultException → null).
     */
    @Test
    void returnCurrentTracksActiveSettingsOnly() {
        assertNull(appSettingsService.returnCurrent(),
                "table starts empty inside this rolled-back transaction");

        AppSettings creada = buildSettings("IT Perfil Current");
        appSettingsService.create(creada);

        AppSettings current = appSettingsService.returnCurrent();
        assertNotNull(current);
        assertEquals(creada.getId(), current.getId());

        appSettingsService.disable(current);
        assertNull(appSettingsService.returnCurrent(),
                "no active row remains after disable()");
    }

    /**
     * Escenario 3 — disable desactiva sin borrar: la fila sigue listada con
     * estatus=false.
     */
    @Test
    void disableKeepsRowButFlagsInactive() {
        AppSettings creada = buildSettings("IT Perfil Disable");
        appSettingsService.create(creada);

        appSettingsService.disable(creada); // managed instance: safe disable() path

        AppSettings reloaded = appSettingsService.find(creada.getId());
        assertNotNull(reloaded, "disable keeps the row");
        assertEquals(Boolean.FALSE, reloaded.getEstatus(), "estatus flag must flip");

        List<AppSettings> all = appSettingsService.listAll();
        assertTrue(all.stream().anyMatch(s -> s.getId() == creada.getId()),
                "disabled row still appears in listAll()");
    }

    /**
     * Escenario 4 — findOrCreateCurrent crea la primera fila cuando la tabla
     * está vacía y es idempotente en la segunda llamada (misma fila).
     */
    @Test
    void findOrCreateCurrentCreatesOnceWhenTableEmpty() {
        assertTrue(appSettingsService.listAll().isEmpty(),
                "precondition: empty table inside this transaction");

        AppSettings primera = appSettingsService.findOrCreateCurrent();
        assertNotNull(primera);
        assertTrue(primera.getId() > 0);
        assertEquals(Boolean.TRUE, primera.getEstatus());

        AppSettings segunda = appSettingsService.findOrCreateCurrent();
        assertNotNull(segunda);
        assertEquals(primera.getId(), segunda.getId(),
                "second call must reuse the existing active row");
    }

    /**
     * Escenario 5 — bordes de paginación (offset más allá del último
     * registro → lista vacía, nunca excepción) y consistencia count/listAll.
     */
    @Test
    void listPagePagingEdgesAndCountConsistency() {
        appSettingsService.create(buildSettings("IT Perfil Pagina A"));
        appSettingsService.create(buildSettings("IT Perfil Pagina B"));
        appSettingsService.create(buildSettings("IT Perfil Pagina C"));

        int total = appSettingsService.count().intValue();
        assertEquals(3, total, "exactly the three fixtures exist in this transaction");

        assertEquals(2, appSettingsService.listPage(0, 2).size());
        assertEquals(1, appSettingsService.listPage(2, 2).size());

        List<AppSettings> beyondLast = appSettingsService.listPage(total + 100, 10);
        assertNotNull(beyondLast, "paging past the end must return a list, not throw");
        assertTrue(beyondLast.isEmpty(), "offset beyond last row yields an empty page");

        assertEquals(appSettingsService.listAll().size(), appSettingsService.count(),
                "count() and listAll().size() must agree");
    }

    /**
     * Escenario 6 — la restricción UNIQUE sobre NombrePerfil está realmente
     * aplicada por PostgreSQL: un INSERT duplicado directo por el
     * EntityManager levanta PersistenceException. Este escenario NO hace más
     * acceso a datos después del fallo porque PostgreSQL aborta la
     * transacción actual tras una violación de constraint.
     */
    @Test
    void duplicateNombrePerfilSurfacesPersistenceExceptionViaEntityManager() {
        AppSettings original = buildSettings("IT Perfil Duplicable");
        appSettingsService.create(original);
        assertNotNull(original);

        AppSettings duplicado = buildSettings("IT Perfil Duplicable"); // mismo NombrePerfil
        try {
            em.persist(duplicado);
            em.flush();
            fail("duplicate NombrePerfil insert must violate the unique constraint");
        } catch (PersistenceException expected) {
            // Intentionally the LAST DB interaction of this test: PostgreSQL
            // rejects every further statement until the transaction ends.
        }
    }

    /**
     * Escenario 7 — contrato actual del servicio: create() traga la
     * PersistenceException del duplicado (la registra en Alertas) y NO
     * propaga la excepción al llamador. Sin más acceso a datos después del
     * intento (transacción abortada a nivel PostgreSQL).
     */
    @Test
    void serviceCreateDuplicateNombrePerfilSwallowsException() {
        AppSettings original = buildSettings("IT Perfil Tragable");
        appSettingsService.create(original);
        assertNotNull(original);

        AppSettings duplicado = buildSettings("IT Perfil Tragable"); // mismo NombrePerfil
        assertDoesNotThrow(() -> appSettingsService.create(duplicado),
                "service contract swallows PersistenceException (alert recorded instead)");
        // Deliberately no further DB access here — see class javadoc.
    }

    // ------------------------------------------------------------------
    // Programmatic fixture helper — satisfies primitive completedSteps and
    // leaves estatus TRUE like the production wizard does on first setup.
    // ------------------------------------------------------------------
    private AppSettings buildSettings(String nombrePerfil) {
        AppSettings settings = new AppSettings();
        settings.setNombrePerfil(nombrePerfil);
        settings.setEstatus(Boolean.TRUE);
        settings.setCompletedSteps(2);
        settings.setNombre("Mercurius IT");
        settings.setNombreNegocio("Negocio IT " + nombrePerfil);
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("3101111111");
        settings.setProvincia("1");
        settings.setCorreoElectronico(nombrePerfil.toLowerCase().replace(' ', '.')
                + "@mercurius.local");
        settings.setTelefono("8888-0000");
        settings.setCodigoPais("506");
        settings.setCodigoActividad("620101");
        settings.setCashbackPercentage(new BigDecimal("5.00"));
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("001");
        settings.setTipoDocumento("04"); // TE default
        return settings;
    }
}
