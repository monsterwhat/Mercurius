package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import Models.Articulos.Articulos;
import Models.Inventario;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 — integration coverage for {@link InventarioService} (GService CRUD
 * contract plus stock side-effects) against the local PostgreSQL test
 * database (mercurius_test, no Docker).
 *
 * <p>Every test runs inside a {@link TestTransaction} rolled back at the end.
 * Programmatic fixtures are created INSIDE each test method (never in
 * @BeforeEach: Quarkus runs @BeforeEach outside the test transaction,
 * quarkusio/quarkus discussion #40119, so anything persisted there would
 * commit and leak). Movements and the {@link Models.Articulos.ArticuloStock}
 * rows written by updateStock() therefore never leak between tests — the
 * stock table starts empty for every scenario because import-test.sql does
 * not seed it.</p>
 *
 * <p>{@link Inventario#fechaMovimiento} is nullable=false with NO @PrePersist,
 * so every fixture sets it explicitly. The backing article is created through
 * {@link ArticulosService#create} — the same production path that feeds real
 * inventory adjustments.</p>
 *
 * <p>Scenarios (6): create→find→update→delete round-trip; createWithStock
 * accumulation in ArticuloStock; markAsProcessed flagging + stock update;
 * softDelete excluding from enabled listing; listPage paging edges; count
 * bucket consistency.</p>
 */
@QuarkusTest
@TestTransaction
@Tag("integration-services")
class InventarioServiceIntegrationTest {

    private static final String BACKING_BARCODE = "IT-BAR-INV-1";

    @Inject
    InventarioService inventarioService;

    @Inject
    ArticulosService articulosService;

    /**
     * Escenario 1 — ciclo completo crear→buscar→actualizar→eliminar de un
     * movimiento de inventario (fechaMovimiento NOT NULL se establece
     * explícitamente porque la entidad no tiene @PrePersist).
     */
    @Test
    void createFindUpdateDeleteRoundTrip() {
        Articulos backingArticulo = createBackingArticulo();
        Inventario created = createMovimiento(backingArticulo, BigDecimal.valueOf(5),
                "Entrada IT", true, false);
        int codigo = created.getCodigo();
        assertTrue(codigo > 0, "IDENTITY insert must assign the codigo immediately");

        Inventario found = inventarioService.find(codigo);
        assertNotNull(found);
        assertEquals(0, found.getCantidad().compareTo(BigDecimal.valueOf(5)),
                "cantidad must round-trip (compareTo: DB numeric scale may differ)");
        assertEquals("Entrada IT", found.getTipoMovimiento());
        assertNotNull(found.getFechaMovimiento(), "NOT NULL fechaMovimiento must be set");
        assertNotNull(found.getArticulo(), "movement must reference its articulo");
        assertEquals(BACKING_BARCODE, found.getArticulo().getCodigoBarra());

        found.setCantidad(BigDecimal.valueOf(7));
        found.setNotas("IT ajuste modificado");
        inventarioService.update(found);

        Inventario updated = inventarioService.find(codigo);
        assertNotNull(updated);
        assertEquals(0, updated.getCantidad().compareTo(BigDecimal.valueOf(7)));
        assertEquals("IT ajuste modificado", updated.getNotas());

        inventarioService.delete(updated);
        assertNull(inventarioService.find(codigo), "deleted movement must not be findable");
    }

    /**
     * Escenario 2 — createWithStock alimenta la tabla ArticuloStock:
     * entradas y salidas se acumulan sobre el mismo código de barra y el
     * total por movimientos coincide con el stock acumulado.
     */
    @Test
    void createWithStockAccumulatesArticuloStock() {
        Articulos backingArticulo = createBackingArticulo();

        assertEquals(0.0, inventarioService.getStock(BACKING_BARCODE), 0.0001,
                "stock starts empty for a fresh barcode");

        createWithStock(backingArticulo, BigDecimal.TEN);
        assertEquals(10.0, inventarioService.getStock(BACKING_BARCODE), 0.0001);

        createWithStock(backingArticulo, BigDecimal.valueOf(-3));
        assertEquals(7.0, inventarioService.getStock(BACKING_BARCODE), 0.0001,
                "negative movement must subtract from accumulated stock");

        assertEquals(7.0,
                inventarioService.calculateTotalStockForItemByBarcode(BACKING_BARCODE),
                0.0001,
                "sum of movements must agree with the denormalized stock row");
    }

    /**
     * Escenario 3 — markAsProcessed marca procesado/activo, copia los datos
     * del movimiento recibido y actualiza el stock acumulado.
     */
    @Test
    void markAsProcessedFlagsMovementAndUpdatesStock() {
        Articulos backingArticulo = createBackingArticulo();
        Inventario pendiente = createMovimiento(backingArticulo, BigDecimal.valueOf(4),
                "Entrada IT", null, null);

        Inventario cambios = new Inventario();
        cambios.setCodigo(pendiente.getCodigo());
        cambios.setCantidad(BigDecimal.valueOf(4));
        cambios.setUnidadesRecomendadasFactura(BigDecimal.valueOf(4));
        cambios.setTipoMovimiento("Entrada IT");
        cambios.setFechaMovimiento(new Date());
        cambios.setNotas("IT procesado por ajuste");
        inventarioService.markAsProcessed(cambios);

        Inventario procesado = inventarioService.find(pendiente.getCodigo());
        assertNotNull(procesado);
        assertEquals(Boolean.TRUE, procesado.getProcessed(), "movement must be flagged processed");
        assertEquals(Boolean.TRUE, procesado.getStatus(), "movement must be flagged active");
        assertEquals(0, procesado.getCantidad().compareTo(BigDecimal.valueOf(4)));
        assertEquals("IT procesado por ajuste", procesado.getNotas());
        assertEquals(4.0, inventarioService.getStock(BACKING_BARCODE), 0.0001,
                "markAsProcessed must feed updateStock");
    }

    /**
     * Escenario 4 — softDelete desactiva el movimiento: sale del listado de
     * habilitados y entra al de inactivos, sin borrarse físicamente.
     */
    @Test
    void softDeleteExcludesFromEnabledListing() {
        Articulos backingArticulo = createBackingArticulo();
        Inventario uno = createMovimiento(backingArticulo, BigDecimal.ONE, "Ajuste IT", true, true);
        Inventario dos = createMovimiento(backingArticulo, BigDecimal.TWO, "Ajuste IT", true, true);

        List<Inventario> habilitados = inventarioService.ListAllEnabled();
        assertTrue(habilitados.stream().anyMatch(i -> i.getCodigo() == uno.getCodigo()));
        assertTrue(habilitados.stream().anyMatch(i -> i.getCodigo() == dos.getCodigo()));

        inventarioService.softDelete(uno);

        List<Inventario> trasBorrado = inventarioService.ListAllEnabled();
        assertTrue(trasBorrado.stream().noneMatch(i -> i.getCodigo() == uno.getCodigo()),
                "soft-deleted movement leaves the enabled listing");
        assertTrue(trasBorrado.stream().anyMatch(i -> i.getCodigo() == dos.getCodigo()));

        assertTrue(inventarioService.listAllInactivos()
                        .stream().anyMatch(i -> i.getCodigo() == uno.getCodigo()),
                "soft-deleted movement appears among inactivos");
        assertNotNull(inventarioService.find(uno.getCodigo()), "row is kept, only disabled");
    }

    /**
     * Escenario 5 — bordes de paginación: offset más allá del último
     * registro devuelve lista vacía, nunca una excepción.
     */
    @Test
    void listPagePagingEdgesReturnEmptyNotException() {
        Articulos backingArticulo = createBackingArticulo();
        createMovimiento(backingArticulo, BigDecimal.ONE, "Ajuste IT", true, true);
        createMovimiento(backingArticulo, BigDecimal.TWO, "Ajuste IT", true, true);
        createMovimiento(backingArticulo, BigDecimal.valueOf(3), "Ajuste IT", true, true);
        int total = inventarioService.listAll().size();

        assertEquals(Math.min(2, total), inventarioService.listPage(0, 2).size());
        assertEquals(Math.max(0, total - 2), inventarioService.listPage(2, 2).size());

        List<Inventario> beyondLast = inventarioService.listPage(total + 100, 10);
        assertNotNull(beyondLast, "paging past the end must return a list, not throw");
        assertTrue(beyondLast.isEmpty(), "offset beyond last row yields an empty page");
    }

    /**
     * Escenario 6 — consistencia de conteos por cubeta: activos (status),
     * pendientes (status ∧ ¬processed) e inactivos particionan listAll().
     */
    @Test
    void countBucketsStayConsistentWithListings() {
        Articulos backingArticulo = createBackingArticulo();
        long activosBase = inventarioService.countActivos();
        long inactivosBase = inventarioService.countInactivos();
        long pendientesBase = inventarioService.countPendientes();

        createMovimiento(backingArticulo, BigDecimal.ONE, "Ajuste IT", true, true);   // activo procesado
        createMovimiento(backingArticulo, BigDecimal.TWO, "Ajuste IT", true, false);  // activo pendiente
        createMovimiento(backingArticulo, BigDecimal.valueOf(3), "Ajuste IT", false, true); // inactivo

        assertEquals(activosBase + 2, inventarioService.countActivos(),
                "both status=true movements count as activos");
        assertEquals(pendientesBase + 1, inventarioService.countPendientes(),
                "only the unprocessed movement counts as pendiente");
        assertEquals(inactivosBase + 1, inventarioService.countInactivos());
        assertEquals(inventarioService.listAll().size(),
                inventarioService.countActivos() + inventarioService.countInactivos(),
                "buckets must partition the full listing");
    }

    // ------------------------------------------------------------------
    // Programmatic fixture helpers — fechaMovimiento is nullable=false and
    // has no @PrePersist, so it is ALWAYS set here explicitly.
    // ------------------------------------------------------------------
    private Articulos createBackingArticulo() {
        Articulos articulo = new Articulos();
        articulo.setNombre("IT Articulo Inventario Base");
        articulo.setCodigoBarra(BACKING_BARCODE);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulo.setPrecios(new ArrayList<>());
        articulosService.create(articulo);
        return articulo;
    }

    private Inventario buildMovimiento(Articulos articulo, BigDecimal cantidad, String tipo,
                                       Boolean status, Boolean processed) {
        Inventario movimiento = new Inventario();
        movimiento.setArticulo(articulo);
        movimiento.setCantidad(cantidad);
        movimiento.setUnidadesRecomendadasFactura(cantidad);
        movimiento.setTipoMovimiento(tipo);
        movimiento.setFechaMovimiento(new Date());
        movimiento.setNotas("Fixture IT de inventario");
        movimiento.setStatus(status);
        movimiento.setProcessed(processed);
        return movimiento;
    }

    private Inventario createMovimiento(Articulos articulo, BigDecimal cantidad, String tipo,
                                        Boolean status, Boolean processed) {
        Inventario movimiento = buildMovimiento(articulo, cantidad, tipo, status, processed);
        inventarioService.create(movimiento);
        return movimiento;
    }

    private Inventario createWithStock(Articulos articulo, BigDecimal cantidad) {
        Inventario movimiento = buildMovimiento(articulo, cantidad, "Entrada IT", true, true);
        inventarioService.createWithStock(movimiento);
        return movimiento;
    }
}
