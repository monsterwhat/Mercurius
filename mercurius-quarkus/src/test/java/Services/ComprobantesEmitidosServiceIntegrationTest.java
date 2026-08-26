package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 — integration coverage for {@link ComprobantesEmitidosService} (GService
 * CRUD contract plus its Hacienda document queries) against the local
 * PostgreSQL test database (mercurius_test, no Docker).
 *
 * <p>Every test runs inside a {@link TestTransaction} rolled back at the end.
 * Programmatic fixtures are created INSIDE each test method (never in
 * @BeforeEach: Quarkus runs @BeforeEach outside the test transaction,
 * quarkusio/quarkus discussion #40119, so anything persisted there would
 * commit and leak). Consecutivos/claves are prefixed "IT" and can never
 * collide with import-test.sql rows (none are seeded for this table). The
 * encabezado is cascaded from the comprobante exactly like the production
 * DocumentoStrategy flow does; receptor/emisor stay null because every
 * Encabezado column is nullable.</p>
 *
 * <p>Note: {@code listAllEmitidosBy(Users)} deliberately has no scenario — it
 * binds a Users entity parameter against a plain String column, a pre-existing
 * quirk that is out of scope per plan guardrails (no behavior fixes here).</p>
 *
 * <p>Scenarios (8): createAndReturn→find→update→delete round-trip;
 * findByNumeroConsecutivo; toggle both ways; softDelete keeps row;
 * listByDateRange ordering + windowing; pendientes count/listing consistency;
 * listPage paging edges; findByClave.</p>
 */
@QuarkusTest
@TestTransaction
@Tag("integration-services")
class ComprobantesEmitidosServiceIntegrationTest {

    @Inject
    ComprobantesEmitidosService comprobantesService;

    /**
     * Escenario 1 — ciclo completo crear(createAndReturn)→buscar→actualizar
     * →eliminar sobre un comprobante con su encabezado en cascada.
     */
    @Test
    void createAndReturnFindUpdateDeleteRoundTrip() {
        LocalDateTime baseTime = LocalDateTime.now();
        ComprobantesEmitidos created = comprobantesService.createAndReturn(
                buildComprobante("ITCONSEC-R001", "IT-CLAVE-R001", null, true,
                        baseTime.minusHours(3)));
        assertNotNull(created, "createAndReturn must hand back the managed entity");
        assertNotNull(created.getId(), "flush() inside createAndReturn must assign the id");

        ComprobantesEmitidos found = comprobantesService.find(created.getId());
        assertNotNull(found);
        assertEquals("ITCONSEC-R001", found.getEncabezado().getNumeroConsecutivo());
        assertEquals("4.4", found.getSchemaVersion());
        assertEquals(Boolean.TRUE, found.getStatus());
        assertEquals("it-admin", found.getUser());

        found.setStatus(Boolean.FALSE);
        found.setUser("it-admin-2");
        comprobantesService.update(found);

        ComprobantesEmitidos updated = comprobantesService.find(created.getId());
        assertNotNull(updated);
        assertEquals(Boolean.FALSE, updated.getStatus());
        assertEquals("it-admin-2", updated.getUser());

        comprobantesService.delete(updated);
        assertNull(comprobantesService.find(created.getId()),
                "deleted comprobante must not be findable");
    }

    /**
     * Escenario 2 — findByNumeroConsecutivo detecta solo consecutivos que
     * existen realmente.
     */
    @Test
    void findByNumeroConsecutivoDetectsOnlyExisting() {
        ComprobantesEmitidos creado = comprobantesService.createAndReturn(
                buildComprobante("ITCONSEC-X001", "IT-CLAVE-X001", "ACEPTADO", true,
                        LocalDateTime.now().minusHours(1)));

        assertTrue(comprobantesService.findByNumeroConsecutivo("ITCONSEC-X001"),
                "persisted consecutivo must be found");
        assertFalse(comprobantesService.findByNumeroConsecutivo("IT-NO-EXISTE"),
                "unknown consecutivo must not be reported as existing");
        assertNotNull(creado);
    }

    /**
     * Escenario 3 — toggle alterna el estado activo/inactivo en ambos
     * sentidos sobre la fila persistida.
     */
    @Test
    void toggleFlipsStatusBothWays() {
        ComprobantesEmitidos creado = comprobantesService.createAndReturn(
                buildComprobante("ITCONSEC-T001", "IT-CLAVE-T001", null, true,
                        LocalDateTime.now().minusHours(1)));
        Long id = creado.getId();

        comprobantesService.toggle(creado);
        assertEquals(Boolean.FALSE, comprobantesService.find(id).getStatus(),
                "true must flip to false");

        comprobantesService.toggle(creado);
        assertEquals(Boolean.TRUE, comprobantesService.find(id).getStatus(),
                "false must flip back to true");
    }

    /**
     * Escenario 4 — softDelete marca status=false pero conserva la fila.
     */
    @Test
    void softDeleteMarksInactiveButKeepsRow() {
        ComprobantesEmitidos creado = comprobantesService.createAndReturn(
                buildComprobante("ITCONSEC-S001", "IT-CLAVE-S001", null, true,
                        LocalDateTime.now().minusHours(1)));
        Long id = creado.getId();

        comprobantesService.softDelete(creado);

        ComprobantesEmitidos reloaded = comprobantesService.find(id);
        assertNotNull(reloaded, "soft delete keeps the row");
        assertEquals(Boolean.FALSE, reloaded.getStatus(), "status flag must be false");
    }

    /**
     * Escenario 5 — listByDateRange ordena por fechaEmision ascendente y
     * respeta la ventana consultada.
     */
    @Test
    void listByDateRangeOrdersAscendingWithinWindow() {
        LocalDateTime baseTime = LocalDateTime.now();
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-D001", "IT-CLAVE-D001", null, true, baseTime.minusHours(2)));
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-D002", "IT-CLAVE-D002", null, true, baseTime.minusHours(1)));

        List<ComprobantesEmitidos> ventana = comprobantesService.listByDateRange(
                java.sql.Timestamp.valueOf(baseTime.minusHours(3)),
                java.sql.Timestamp.valueOf(baseTime.plusHours(1)));
        assertEquals(2, ventana.size(), "both fixtures fall inside the wide window");
        assertEquals("ITCONSEC-D001", ventana.get(0).getEncabezado().getNumeroConsecutivo(),
                "older first (ORDER BY fechaEmision ASC)");
        assertEquals("ITCONSEC-D002", ventana.get(1).getEncabezado().getNumeroConsecutivo());

        List<ComprobantesEmitidos> estrecha = comprobantesService.listByDateRange(
                java.sql.Timestamp.valueOf(baseTime.minusMinutes(90)),
                java.sql.Timestamp.valueOf(baseTime.plusHours(1)));
        assertEquals(1, estrecha.size(), "narrow window excludes the older fixture");
        assertEquals("ITCONSEC-D002", estrecha.get(0).getEncabezado().getNumeroConsecutivo());
    }

    /**
     * Escenario 6 — consistencia pendientes: countFacturasPendientes coincide
     * con findFacturasPendientes().size(); estado NULL cuenta como pendiente
     * y los aceptados o inactivos no cuentan.
     */
    @Test
    void pendientesCountMatchesListing() {
        LocalDateTime baseTime = LocalDateTime.now();
        long basePendientes = comprobantesService.countFacturasPendientes();

        // Matriz: (estado NULL | PENDIENTE | ACEPTADO) x (status true|false)
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-P001", "IT-CLAVE-P001", null, true, baseTime.minusHours(4)));
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-P002", "IT-CLAVE-P002", "PENDIENTE", true, baseTime.minusHours(3)));
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-P003", "IT-CLAVE-P003", "ACEPTADO", true, baseTime.minusHours(2)));
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-P004", "IT-CLAVE-P004", "PENDIENTE", false, baseTime.minusHours(1)));

        assertEquals(basePendientes + 2, comprobantesService.countFacturasPendientes(),
                "NULL estado and PENDIENTE with status=true count; others do not");
        List<ComprobantesEmitidos> pendientes = comprobantesService.findFacturasPendientes();
        assertNotNull(pendientes);
        assertEquals(basePendientes + 2, pendientes.size(),
                "count and listing must agree");

        List<ComprobantesEmitidos> aceptadas = comprobantesService.findFacturasAceptadas();
        assertNotNull(aceptadas);
        assertTrue(aceptadas.stream()
                        .anyMatch(f -> "ITCONSEC-P003".equals(f.getEncabezado().getNumeroConsecutivo())),
                "ACEPTADO fixture lands in the aceptadas listing");
        assertTrue(pendientes.stream()
                        .noneMatch(f -> "ITCONSEC-P003".equals(f.getEncabezado().getNumeroConsecutivo())),
                "ACEPTADO fixture never appears among pendientes");
    }

    /**
     * Escenario 7 — bordes de paginación heredados de GService.listPage:
     * offset más allá del último registro devuelve lista vacía, nunca una
     * excepción.
     */
    @Test
    void listPagePagingEdgesReturnEmptyNotException() {
        LocalDateTime baseTime = LocalDateTime.now();
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-G001", "IT-CLAVE-G001", null, true, baseTime.minusHours(3)));
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-G002", "IT-CLAVE-G002", null, true, baseTime.minusHours(2)));
        comprobantesService.createAndReturn(buildComprobante(
                "ITCONSEC-G003", "IT-CLAVE-G003", null, true, baseTime.minusHours(1)));
        int total = comprobantesService.listAll().size();

        assertEquals(Math.min(2, total), comprobantesService.listPage(0, 2).size());
        assertEquals(Math.max(0, total - 2), comprobantesService.listPage(2, 2).size());

        List<ComprobantesEmitidos> beyondLast = comprobantesService.listPage(total + 100, 10);
        assertNotNull(beyondLast, "paging past the end must return a list, not throw");
        assertTrue(beyondLast.isEmpty(), "offset beyond last row yields an empty page");
    }

    /**
     * Escenario 8 — findByClave localiza el comprobante por su clave
     * Hacienda y devuelve vacío (no null) para claves desconocidas.
     */
    @Test
    void findByClaveReturnsMatchingRowsOnly() {
        ComprobantesEmitidos creado = comprobantesService.createAndReturn(
                buildComprobante("ITCONSEC-C001", "IT-CLAVE-C001", null, true,
                        LocalDateTime.now().minusHours(1)));

        List<ComprobantesEmitidos> hits = comprobantesService.findByClave("IT-CLAVE-C001");
        assertNotNull(hits);
        assertEquals(1, hits.size());
        assertEquals(creado.getId(), hits.get(0).getId());

        List<ComprobantesEmitidos> misses = comprobantesService.findByClave("IT-CLAVE-NOPE");
        assertNotNull(misses, "unknown clave yields an empty list, not null");
        assertTrue(misses.isEmpty());
    }

    // ------------------------------------------------------------------
    // Programmatic fixture helper — every Encabezado column is nullable,
    // so clave/consecutivo/fechaEmision/estado are all this fixture needs.
    // numeroConsecutivo column is length=20, haciendaClave length=50.
    // ------------------------------------------------------------------
    private ComprobantesEmitidos buildComprobante(String consecutivo, String clave,
                                                  String estado, boolean status,
                                                  LocalDateTime fechaEmision) {
        Encabezado encabezado = new Encabezado();
        encabezado.setClave(clave);
        encabezado.setNumeroConsecutivo(consecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setCondicionVenta("01"); // Contado
        encabezado.setEstado(estado);
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01"); // FE

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setSchemaVersion("4.4");
        comprobante.setEncabezado(encabezado);
        comprobante.setStatus(status);
        comprobante.setUser("it-admin");
        comprobante.setHaciendaClave(clave); // findByClave queries f.haciendaClave, not encabezado.clave
        return comprobante;
    }
}
