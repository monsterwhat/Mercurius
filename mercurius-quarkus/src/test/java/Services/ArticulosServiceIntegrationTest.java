package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Familia;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 — integration coverage for {@link ArticulosService} (GService CRUD
 * contract plus its article-specific queries) against the local PostgreSQL
 * test database (mercurius_test, no Docker).
 *
 * <p>Every test runs inside a {@link TestTransaction} rolled back at the end.
 * Programmatic fixtures are created INSIDE each test method (never in
 * @BeforeEach: Quarkus runs @BeforeEach outside the test transaction,
 * quarkusio/quarkus discussion #40119, so anything persisted there would
 * commit and leak). Fixture names/barcodes are prefixed "IT " / "IT-BAR" to
 * avoid colliding with import-test.sql rows; 'Departamento General' and
 * 'Familia General' are reused as category links, never modified.</p>
 *
 * <p>{@link Articulos#fecha} is nullable=false but populated by @PrePersist on
 * persist — the service path therefore needs no manual timestamp.</p>
 *
 * <p>Scenarios (7): create→find→update→delete round-trip; softDelete moving
 * active/inactive counts; barcode/name-search visibility rules;
 * listing-bucket partition over listAll; listPage paging edges; status
 * partition count consistency; findArticulosAfterDate.</p>
 */
@QuarkusTest
@TestTransaction
@Tag("integration-services")
class ArticulosServiceIntegrationTest {

    private static final String PAGING_FRAGMENT = "Pagina";

    @Inject
    ArticulosService articulosService;

    @Inject
    EntityManager em;

    /**
     * Escenario 1 — ciclo completo crear→buscar→actualizar→eliminar.
     */
    @Test
    void createFindUpdateDeleteRoundTrip() {
        Articulos created = createArticulo("IT Articulo Roundtrip", "IT-BAR-R001");
        Long codigo = created.getCodigo();
        assertTrue(codigo > 0, "IDENTITY insert must assign the codigo immediately");

        Articulos found = articulosService.find(codigo);
        assertNotNull(found);
        assertEquals("IT Articulo Roundtrip", found.getNombre());
        assertEquals("IT-BAR-R001", found.getCodigoBarra());
        assertEquals("Unidad", found.getUnidadMedida());
        assertTrue(found.isStatus(), "primitive status must survive persistence");
        assertTrue(found.isProcessed(), "primitive processed must survive persistence");
        assertNotNull(found.getFecha(), "@PrePersist must stamp the NOT NULL fecha column");
        assertNotNull(found.getDepartamento(), "seeded Departamento link must round-trip");
        assertEquals("Departamento General", found.getDepartamento().getNombre());
        assertNotNull(found.getFamilia(), "seeded Familia link must round-trip");
        assertEquals("Familia General", found.getFamilia().getNombre());

        found.setNombre("IT Articulo Roundtrip Editado");
        found.setDescripcion("Descripcion actualizada por IT");
        articulosService.update(found);

        Articulos updated = articulosService.find(codigo);
        assertNotNull(updated);
        assertEquals("IT Articulo Roundtrip Editado", updated.getNombre());

        articulosService.delete(updated);
        assertNull(articulosService.find(codigo), "deleted articulo must not be findable");
    }

    /**
     * Escenario 2 — softDelete desactiva sin borrar: countActivos baja en uno
     * y countInactivos sube en uno respecto a la línea base del test.
     */
    @Test
    void softDeleteMovesCountsBetweenActiveAndInactive() {
        long activosBase = articulosService.countActivos();
        long inactivosBase = articulosService.countInactivos();

        Articulos victima = createArticulo("IT Articulo SoftDelete", "IT-BAR-S001");
        assertEquals(activosBase + 1, articulosService.countActivos());

        articulosService.softDelete(victima);

        assertEquals(activosBase, articulosService.countActivos(),
                "soft-deleted articulo leaves the active bucket");
        assertEquals(inactivosBase + 1, articulosService.countInactivos(),
                "soft-deleted articulo joins the inactive bucket");

        Articulos reloaded = articulosService.find(victima.getCodigo());
        assertNotNull(reloaded, "soft delete keeps the row");
        assertFalse(reloaded.isStatus(), "status flag must be flipped to false");
    }

    /**
     * Escenario 3 — visibilidad: findByNameContaining solo ve artículos
     * processed+activos; findByBarCode no filtra por estado (comportamiento
     * actual, se caracteriza tal cual).
     */
    @Test
    void nameSearchRespectsVisibilityWhileBarcodeLookupDoesNot() {
        Long codeA = createArticulo("IT Articulo Pagina A", "IT-BAR-000A").getCodigo();
        Long codeB = createArticulo("IT Articulo Pagina B", "IT-BAR-000B").getCodigo();
        createArticulo("IT Articulo Pagina C", "IT-BAR-000C");

        assertEquals(3, articulosService.findByNameContaining(PAGING_FRAGMENT).size());

        Articulos oculto = articulosService.find(codeB);
        oculto.setProcessed(false);
        articulosService.update(oculto);

        List<Articulos> visible = articulosService.findByNameContaining(PAGING_FRAGMENT);
        assertEquals(2, visible.size(), "unprocessed fixture drops out of the search");
        assertTrue(visible.stream().noneMatch(a -> a.getCodigo().equals(codeB)));
        assertTrue(visible.stream().anyMatch(a -> a.getCodigo().equals(codeA)));

        assertNotNull(articulosService.findByBarCode("IT-BAR-000B"),
                "barcode lookup has no visibility filter (current behavior)");
        assertNull(articulosService.findByBarCode("IT-BAR-NOPE"),
                "unknown barcode yields null");
    }

    /**
     * Escenario 4 — los tres listados por cubeta particionan listAll():
     * activos-y-procesados (T∧T), sin-procesar (T∧F) e inactivos (F∧*).
     *
     * <p>Nota: {@code updateAndDisable} NO tiene escenario propio porque no
     * tiene llamadores en producción y su semántica para una versión nueva
     * (id nulo → {@code em.find(class, null)}) no está definida; caracterizarla
     * probaría detalles de Hibernate, no intención del servicio.</p>
     */
    @Test
    void listingBucketsPartitionListAll() {
        int procesadosBase = articulosService.listAllActivosYProcesados().size();
        int sinProcesarBase = articulosService.listAllSinProcesar().size();
        int inactivosBase = articulosService.listAllInactivos().size();

        Articulos pendiente = createArticulo("IT Articulo Pendiente", "IT-BAR-PG001");
        pendiente.setProcessed(false);
        articulosService.update(pendiente);

        Articulos inactivo = createArticulo("IT Articulo Inactivo", "IT-BAR-IN001");
        inactivo.setStatus(false);
        articulosService.update(inactivo);

        List<Articulos> sinProcesar = articulosService.listAllSinProcesar();
        assertEquals(sinProcesarBase + 1, sinProcesar.size());
        assertTrue(sinProcesar.stream().anyMatch(a -> a.getCodigo().equals(pendiente.getCodigo())),
                "unprocessed active fixture lands in sin-procesar");

        List<Articulos> procesados = articulosService.listAllActivosYProcesados();
        assertEquals(procesadosBase, procesados.size(),
                "pendiente fixture must not appear among activos-y-procesados");
        assertTrue(procesados.stream().noneMatch(a -> a.getCodigo().equals(inactivo.getCodigo())));

        List<Articulos> inactivos = articulosService.listAllInactivos();
        assertEquals(inactivosBase + 1, inactivos.size());
        assertTrue(inactivos.stream().anyMatch(a -> a.getCodigo().equals(inactivo.getCodigo())));

        assertEquals(articulosService.listAll().size(),
                procesados.size() + sinProcesar.size() + inactivos.size(),
                "the three listings must partition listAll()");
    }

    /**
     * Escenario 5 — bordes de paginación: offset más allá del último
     * registro devuelve lista vacía, nunca una excepción.
     */
    @Test
    void listPagePagingEdgesReturnEmptyNotException() {
        createArticulo("IT Articulo Pagina A", "IT-BAR-000A");
        createArticulo("IT Articulo Pagina B", "IT-BAR-000B");
        createArticulo("IT Articulo Pagina C", "IT-BAR-000C");
        int total = articulosService.listAll().size();

        assertEquals(Math.min(2, total), articulosService.listPage(0, 2).size());
        assertEquals(Math.max(0, total - 2), articulosService.listPage(2, 2).size());

        List<Articulos> beyondLast = articulosService.listPage(total + 100, 10);
        assertNotNull(beyondLast, "paging past the end must return a list, not throw");
        assertTrue(beyondLast.isEmpty(), "offset beyond last row yields an empty page");
    }

    /**
     * Escenario 6 — partición de conteos: todo artículo es activo o
     * inactivo (status primitivo), y los pendientes son el subconjunto
     * activo-no-procesado.
     */
    @Test
    void countPartitionStaysConsistentWithListings() {
        long activosBase = articulosService.countActivos();
        long inactivosBase = articulosService.countInactivos();
        long pendientesBase = articulosService.countPendientes();

        Articulos inactivo = createArticulo("IT Articulo Inactivo", "IT-BAR-I001");
        inactivo.setStatus(false);
        articulosService.update(inactivo);

        Articulos pendiente = createArticulo("IT Articulo Pendiente", "IT-BAR-P001");
        pendiente.setProcessed(false);
        articulosService.update(pendiente);

        assertEquals(activosBase + 1, articulosService.countActivos(),
                "pendiente is still status=true so it counts as activo");
        assertEquals(inactivosBase + 1, articulosService.countInactivos());
        assertEquals(pendientesBase + 1, articulosService.countPendientes());
        assertEquals(articulosService.listAll().size(),
                articulosService.countActivos() + articulosService.countInactivos(),
                "status is a primitive boolean: both buckets must partition listAll()");
    }

    /**
     * Escenario 7 — findArticulosAfterDate incluye los fixtures recién
     * creados (fecha sellada por @PrePersist) al consultar desde época.
     */
    @Test
    void findAfterDateIncludesFreshFixtures() {
        Long codeA = createArticulo("IT Articulo Pagina A", "IT-BAR-000A").getCodigo();
        createArticulo("IT Articulo Pagina B", "IT-BAR-000B");
        Long codeC = createArticulo("IT Articulo Pagina C", "IT-BAR-000C").getCodigo();

        List<Articulos> recent = articulosService.findArticulosAfterDate(new Date(0L));
        assertTrue(recent.stream().anyMatch(a -> a.getCodigo().equals(codeA)),
                "fixture created now must be after epoch");
        assertTrue(recent.stream().anyMatch(a -> a.getCodigo().equals(codeC)));
    }

    // ------------------------------------------------------------------
    // Programmatic fixture helper — Articulos.fecha (nullable=false) is
    // stamped by @PrePersist; primitives status/processed are set
    // explicitly. Category links point at the seeded rows.
    // ------------------------------------------------------------------
    private Articulos createArticulo(String nombre, String codigoBarra) {
        Articulos articulo = new Articulos();
        articulo.setNombre(nombre);
        articulo.setCodigoBarra(codigoBarra);
        articulo.setDescripcion("Fixture IT para " + nombre);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulo.setPrecios(new ArrayList<>());
        articulo.setDepartamento(seededDepartamentoGeneral());
        articulo.setFamilia(seededFamiliaGeneral());
        articulosService.create(articulo);
        return articulo;
    }

    private Departamento seededDepartamentoGeneral() {
        return em.createQuery(
                "SELECT d FROM Departamento d WHERE d.nombre = 'Departamento General'",
                Departamento.class).getSingleResult();
    }

    private Familia seededFamiliaGeneral() {
        return em.createQuery(
                "SELECT f FROM Familia f WHERE f.nombre = 'Familia General'",
                Familia.class).getSingleResult();
    }
}
