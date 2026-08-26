package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import Models.Clients;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T8 — integration coverage for {@link ClientService} (GService CRUD contract)
 * against the local PostgreSQL test database (mercurius_test, no Docker).
 *
 * <p>Every test runs inside a {@link TestTransaction} that is rolled back at
 * the end of the method. Programmatic fixtures are therefore created INSIDE
 * each test method (never in @BeforeEach): Quarkus runs @BeforeEach outside
 * the test transaction (quarkusio/quarkus discussion #40119), so anything
 * persisted there would commit and leak across tests. Fixture names/codes are
 * prefixed with "IT " / "IT-" so they can never collide with the rows seeded
 * by import-test.sql ('Cliente Contado').</p>
 *
 * <p>Scenarios (5): create→find→update→delete round-trip; case-insensitive
 * LIKE search; client-name/idNumber guard checks; listPage paging edges
 * (offset beyond last page → empty list, not an exception); count/listAll
 * consistency.</p>
 */
@QuarkusTest
@TestTransaction
@Tag("integration-services")
class ClientServiceIntegrationTest {

    @Inject
    ClientService clientService;

    /**
     * Escenario 1 — ciclo completo crear→buscar→actualizar→eliminar sobre un
     * cliente nuevo, verificando campos NOT NULL y primitivos en cada paso.
     */
    @Test
    void createFindUpdateDeleteRoundTrip() {
        Clients created = createClient("IT Cliente Roundtrip", "IT-201110100");
        int code = created.getCode();
        assertTrue(code > 0, "IDENTITY insert must assign the code immediately");

        Clients found = clientService.find(code);
        assertNotNull(found);
        assertEquals("IT Cliente Roundtrip", found.getName());
        assertEquals("IT-201110100", found.getIdNumber());
        assertEquals("it.cliente.roundtrip@mercurius.local", found.getEmail());
        assertTrue(found.isTaxpayer(), "primitive boolean must survive the round-trip");
        assertEquals(1, found.getZoneCode(), "primitive int must survive the round-trip");
        assertEquals(Boolean.TRUE, found.getStatus());

        found.setEmail("it-roundtrip-updated@mercurius.local");
        found.setPhoneNumber("7000-7000");
        clientService.update(found);

        Clients updated = clientService.find(code);
        assertNotNull(updated);
        assertEquals("it-roundtrip-updated@mercurius.local", updated.getEmail());
        assertEquals("7000-7000", updated.getPhoneNumber());

        clientService.delete(updated);
        assertNull(clientService.find(code), "deleted client must not be findable");
    }

    /**
     * Escenario 2 — searchByName usa LOWER(...) LIKE: insensible a
     * mayúsculas, por fragmento, y sin falsos positivos.
     */
    @Test
    void searchByNameIsCaseInsensitiveFragmentMatch() {
        createClient("IT Cliente Pagina A", "IT-301110001");
        createClient("IT Cliente Pagina B", "IT-301110002");
        createClient("IT Cliente Pagina C", "IT-301110003");

        List<Clients> hits = clientService.searchByName("pagina b");
        assertNotNull(hits);
        assertEquals(1, hits.size(), "exactly one fixture matches 'pagina b'");
        assertEquals("IT Cliente Pagina B", hits.get(0).getName());

        List<Clients> upperCaseHits = clientService.searchByName("PAGINA");
        assertNotNull(upperCaseHits);
        assertEquals(3, upperCaseHits.size(), "case-insensitive match over all three fixtures");

        List<Clients> misses = clientService.searchByName("zz-inexistente-it");
        assertNotNull(misses);
        assertTrue(misses.isEmpty(), "no fixture nor seeded row matches this fragment");
    }

    /**
     * Escenario 3 — checkClientName (exacto, case-insensitive) y
     * checkClientByIdNumber (canónico) sobre fixtures propios Y la fila
     * sembrada 'Cliente Contado'.
     */
    @Test
    void checkNameAndIdNumberGuards() {
        assertTrue(clientService.checkClientName("Cliente Contado"),
                "seeded client must be detected too");
        assertFalse(clientService.checkClientName("no-existo-it"));

        createClient("IT Cliente Guard", "IT-401110400");
        assertTrue(clientService.checkClientName("it cliente GUARD"),
                "exact-name check is case-insensitive on the fixture itself");
        assertTrue(clientService.checkClientByIdNumber("IT-401110400"));
        assertFalse(clientService.checkClientByIdNumber("999999999"),
                "unknown idNumber must not be reported as existing");
    }

    /**
     * Escenario 4 — bordes de paginación de GService.listPage: primera
     * página completa, página intermedia parcial y offset MÁS ALLÁ del
     * último registro → lista vacía, nunca una excepción.
     */
    @Test
    void listPagePagingEdgesReturnEmptyNotException() {
        createClient("IT Cliente Pagina A", "IT-301110001");
        createClient("IT Cliente Pagina B", "IT-301110002");
        createClient("IT Cliente Pagina C", "IT-301110003");
        int total = clientService.count().intValue(); // 3 fixtures + seeded row

        assertEquals(Math.min(2, total), clientService.listPage(0, 2).size(),
                "first page holds at most pageSize rows");
        assertEquals(Math.max(0, total - 2), clientService.listPage(2, 2).size(),
                "second page holds the remaining rows");

        List<Clients> beyondLast = clientService.listPage(total + 100, 10);
        assertNotNull(beyondLast, "paging past the end must return a list, not throw");
        assertTrue(beyondLast.isEmpty(), "offset beyond last row yields an empty page");

        assertEquals(total, clientService.listPage(0, total + 5).size(),
                "pageSize larger than the dataset returns everything exactly once");
    }

    /**
     * Escenario 5 — consistencia count()/listAll(): crear dos clientes
     * incrementa el conteo exactamente en dos y el conteo coincide con el
     * tamaño del listado completo.
     */
    @Test
    void countStaysConsistentWithListAllAfterInserts() {
        long baseline = clientService.count();

        createClient("IT Cliente Consistencia 1", "IT-501110501");
        createClient("IT Cliente Consistencia 2", "IT-501110502");

        assertEquals(baseline + 2, clientService.count(),
                "count must grow by exactly the number of inserts");
        assertEquals(clientService.listAll().size(), clientService.count(),
                "count() and listAll().size() must agree");
    }

    // ------------------------------------------------------------------
    // Programmatic fixture helper — satisfies every NOT NULL column of
    // Models/Clients (discount/taxpayer/zoneCode primitives included).
    // ------------------------------------------------------------------
    private Clients createClient(String name, String idNumber) {
        Clients client = new Clients();
        client.setName(name);
        client.setAddress("Barrio IT, San José");
        client.setProvincia("1"); // column length = 1
        client.setEmail(name.toLowerCase().replace(' ', '.') + "@mercurius.local");
        Date birthDate = Date.from(
                LocalDate.of(1995, 3, 15).atStartOfDay(ZoneId.systemDefault()).toInstant());
        client.setBirthDate(birthDate);
        client.setIdType("Cedula Fisica");
        client.setIdNumber(idNumber);
        client.setDiscount(0.0); // primitive double → NOT NULL column
        client.setPhoneNumber("8888-0000");
        client.setTaxpayer(true); // primitive boolean → NOT NULL column
        client.setZoneCode(1); // primitive int → NOT NULL column
        client.setTipoIdentificacion("01");
        client.setStatus(Boolean.TRUE);
        clientService.create(client);
        return client;
    }
}
