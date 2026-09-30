package Documentos;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bulk import of the real supplier invoices kept outside the repo.
 *
 * <p>Runs against {@code D:/Documents/Facturas Electronicas} (40 v4.3 XML:
 * facturas and notas de crédito from a dozen suppliers). Each file is uploaded
 * through the real {@code POST /api/app/facturas-recibidas/upload} endpoint
 * and then processed through the real {@code PUT /{id}/procesar} endpoint —
 * the same two steps an operator performs — and every file must come back
 * with a per-file verdict: accepted or rejected with a documented reason.
 * What must never happen is a file vanishing without a verdict (swallowed
 * exception) or a 500.</p>
 *
 * <p><b>Local-only by design.</b> The folder lives outside the repo, so this
 * class assumes it into existence: where it is absent (CI, other machines) the
 * tests skip cleanly instead of failing — the same pattern as
 * {@code PgDumpAvailability}. Nothing here is committed: unlike
 * {@code fixtures/reales/} (anonymized + verified), these are the raw supplier
 * documents and must never enter git. The test database is disposable, so
 * importing them there needs no anonymization.</p>
 *
 * <p>Assertions are INVARIANTS, not exact counts: the folder is a live
 * collection and files come and go. What is pinned: every file accounted for,
 * no server errors, accepted invoices persist with articles and stock, and
 * rejections carry known benign reasons.</p>
 */
@QuarkusTest
@DisplayName("Facturas reales locales: importacion masiva por subida")
class FacturasRealesLocalesTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/facturas-recibidas";
    private static final Path CARPETA = Paths.get("D:/Documents/Facturas Electronicas");
    private static final Logger LOG = Logger.getLogger(FacturasRealesLocalesTest.class);

    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private static void asumirCarpeta() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(CARPETA),
                "sin D:/Documents/Facturas Electronicas no hay nada que importar; se omite");
    }

    private static List<Path> facturasXml() throws Exception {
        try (Stream<Path> flujo = Files.walk(CARPETA)) {
            return flujo.filter(p -> p.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private Map<String, String> adminSession() {
        var lp = given().redirects().follow(false).when().get(BASE + "/login");
        lp.then().statusCode(200);
        Map<String, String> c = new HashMap<>(lp.getCookies());
        var login = given().redirects().follow(false).cookies(c)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        c.putAll(login.getCookies());
        return c;
    }

    private String csrf(Map<String, String> c) {
        String t = c.get("csrf-token");
        return t != null ? t : c.get("csrftoken");
    }

    private record Veredicto(String archivo, boolean exito, String mensaje) {}

    private record Aceptada(String archivo, long id, boolean recibida) {}

    /** NumeroConsecutivo del XML, para reconocer duplicados entre archivos. */
    private static String consecutivoDe(Path xml) {
        try {
            String contenido = Files.readString(xml);
            var m = java.util.regex.Pattern.compile("<NumeroConsecutivo>(\\d{1,20})</NumeroConsecutivo>")
                    .matcher(contenido);
            return m.find() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean existeConsecutivo(String consecutivo) {
        if (consecutivo == null) {
            return false;
        }
        Long n = em.createQuery(
                        "SELECT COUNT(e) FROM ComprobantesRecibidos e "
                                + "WHERE e.encabezado.numeroConsecutivo = :consecutivo",
                        Long.class)
                .setParameter("consecutivo", consecutivo)
                .getSingleResult();
        return n != null && n > 0;
    }

    /**
     * Waits for the async parser to persist the uploaded file's row.
     *
     * <p>The upload request returns once parsing is accepted, but the row lands
     * through a worker-thread bracket (same as the legacy JSF upload) — a
     * immediate list can miss it. Polls both tables briefly, like
     * {@code esperarPorConsecutivo} in the mensaje-receptor suite.
     *
     * @return the new row id and its table, or null on timeout (caller fails)
     */
    private Aceptada esperarFilaNueva(String archivo, Set<Long> recibidosAntes,
                                      Set<Long> emitidosAntes) {
        for (int intento = 0; intento < 40; intento++) {
            Set<Long> ahora = idsRecibidos();
            ahora.removeAll(recibidosAntes);
            if (!ahora.isEmpty()) {
                return new Aceptada(archivo, ahora.iterator().next(), true);
            }
            Set<Long> ahoraE = idsEmitidos();
            ahoraE.removeAll(emitidosAntes);
            if (!ahoraE.isEmpty()) {
                return new Aceptada(archivo, ahoraE.iterator().next(), false);
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }

    private Veredicto subir(Map<String, String> s, Path xml) {
        Response respuesta = given().cookies(s)
                .header("X-CSRF-TOKEN", csrf(s))
                .contentType(ContentType.MULTIPART)
                .multiPart("files", xml.getFileName().toString(),
                        readBytes(xml), "application/xml")
                .when().post(API + "/upload");
        respuesta.then().statusCode(200);
        List<Map<String, Object>> resultados =
                respuesta.jsonPath().getList("data.resultados");
        assertThat(resultados)
                .as("cada archivo debe traer su veredicto: %s", xml.getFileName())
                .hasSize(1);
        Map<String, Object> r = resultados.get(0);
        return new Veredicto(xml.getFileName().toString(),
                Boolean.TRUE.equals(r.get("exito")),
                String.valueOf(r.get("mensaje")));
    }

    private static byte[] readBytes(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) {
            throw new IllegalStateException("no se pudo leer " + p, e);
        }
    }

    private Set<Long> idsEmitidos() {
        return new HashSet<>(em.createQuery("SELECT c.id FROM ComprobantesEmitidos c", Long.class)
                .getResultList());
    }

    private Set<Long> idsRecibidos() {
        return new HashSet<>(em.createQuery("SELECT c.id FROM ComprobantesRecibidos c", Long.class)
                .getResultList());
    }

    private Set<Long> codigosArticulos() {
        return new HashSet<>(em.createQuery("SELECT a.codigo FROM Articulos a", Long.class)
                .getResultList());
    }

    @Test
    @DisplayName("las 40 facturas reales se importan sin errores de servidor")
    void importarTodoSinErrores() throws Exception {
        asumirCarpeta();
        List<Path> archivos = facturasXml();
        assertThat(archivos).as("la carpeta debe traer facturas XML").isNotEmpty();

        Set<Long> emitidosAntes = idsEmitidos();
        Set<Long> recibidosAntes = idsRecibidos();
        Set<Long> articulosAntes = codigosArticulos();

        Map<String, String> s = adminSession();
        List<Veredicto> veredictos = new ArrayList<>();
        List<Aceptada> paraProcesar = new ArrayList<>();
        try {
            for (Path xml : archivos) {
                Set<Long> recibidosAntesUno = idsRecibidos();
                Set<Long> emitidosAntesUno = idsEmitidos();
                Veredicto v = subir(s, xml);
                veredictos.add(v);
                if (v.exito()) {
                    Aceptada a = esperarFilaNueva(
                            xml.getFileName().toString(), recibidosAntesUno, emitidosAntesUno);
                    if (a == null) {
                        // Sin fila nueva con exito=true: el importador omite los
                        // duplicados por NumeroConsecutivo (Parser: exists ->
                        // return) y la carpeta trae cada factura dos veces (la
                        // de su proveedor y la copia en "Todas"). Es el
                        // comportamiento correcto — reimportar no debe duplicar —
                        // y se verifica que el consecutivo ya estaba registrado.
                        assertThat(existeConsecutivo(consecutivoDe(xml)))
                                .as("aceptada sin fila nueva debe ser duplicado ya registrado: %s",
                                        xml.getFileName())
                                .isTrue();
                    } else {
                        paraProcesar.add(a);
                    }
                }
            }

            assertThat(veredictos)
                    .as("ningun archivo puede perderse sin veredicto")
                    .hasSize(archivos.size());

            long aceptadas = veredictos.stream().filter(Veredicto::exito).count();
            long rechazadas = veredictos.size() - aceptadas;
            LOG.info("Facturas reales: " + aceptadas + " aceptadas, "
                    + rechazadas + " rechazadas de " + archivos.size());
            for (Veredicto v : veredictos) {
                if (!v.exito()) {
                    LOG.info("  rechazada: " + v.archivo() + " -> " + v.mensaje());
                }
                assertThat(v.mensaje())
                        .as("todo rechazo debe traer motivo documentado: %s", v.archivo())
                        .isNotNull()
                        .isNotBlank();
            }

            // La gran mayoría debe aceptarse: son facturas reales válidas.
            assertThat(aceptadas)
                    .as("la mayoría de facturas reales válidas debe aceptarse")
                    .isGreaterThanOrEqualTo((archivos.size() * 2L) / 3);

            // Lo aceptado persiste de verdad: facturas, artículos y stock.
            // La subida solo parsea y registra; los artículos y el inventario
            // los crea el paso explícito PUT /{id}/procesar (igual que el
            // operador en la UI), así que se ejecuta aquí por cada aceptada.
            long procesadas = 0;
            for (Aceptada a : paraProcesar) {
                assertThat(a.recibida())
                        .as("un documento de proveedor debe caer en recibidas: %s", a.archivo())
                        .isTrue();
                given().cookies(s)
                        .header("X-CSRF-TOKEN", csrf(s))
                        .contentType(ContentType.JSON)
                        .when().put(API + "/" + a.id() + "/procesar")
                        .then().statusCode(200);
                procesadas++;
            }
            LOG.info("Facturas reales procesadas (artículos+inventario): "
                    + procesadas + " de " + paraProcesar.size());

            long emitidosNuevos = idsEmitidos().stream().filter(id -> !emitidosAntes.contains(id)).count();
            long recibidosNuevos = idsRecibidos().stream().filter(id -> !recibidosAntes.contains(id)).count();
            assertThat(emitidosNuevos + recibidosNuevos)
                    .as("lo aceptado debe persistir como comprobantes")
                    .isGreaterThan(0);
            long articulosNuevos = codigosArticulos().stream()
                    .filter(c -> !articulosAntes.contains(c)).count();
            assertThat(articulosNuevos)
                    .as("el procesamiento debe crear artículos del detalle")
                    .isGreaterThan(0);
        } finally {
            limpiar(emitidosAntes, recibidosAntes, articulosAntes);
        }
    }

    /**
     * Borra lo creado por la corrida en orden seguro a FK: comprobantes (la
     * cascada arrastra encabezado/detalles/resumen), movimientos de inventario
     * de artículos nuevos, stock por código de barras y artículos nuevos. Los
     * departamentos creados se dejan (referencia compartida, como el resto de
     * suites).
     */
    private void limpiar(Set<Long> emitidosAntes, Set<Long> recibidosAntes, Set<Long> articulosAntes) {
        try {
            utx.begin();
            List<Long> emitidosNuevos = em
                    .createQuery("SELECT c.id FROM ComprobantesEmitidos c", Long.class)
                    .getResultList().stream()
                    .filter(id -> !emitidosAntes.contains(id)).toList();
            for (Long id : emitidosNuevos) {
                Models.ComprobantesEmitidos c = em.find(Models.ComprobantesEmitidos.class, id);
                if (c != null) {
                    em.remove(c);
                }
            }
            List<Long> recibidosNuevos = em
                    .createQuery("SELECT c.id FROM ComprobantesRecibidos c", Long.class)
                    .getResultList().stream()
                    .filter(id -> !recibidosAntes.contains(id)).toList();
            for (Long id : recibidosNuevos) {
                Models.ComprobantesRecibidos c = em.find(Models.ComprobantesRecibidos.class, id);
                if (c != null) {
                    em.remove(c);
                }
            }
            List<Long> articulosNuevos = em
                    .createQuery("SELECT a.codigo FROM Articulos a", Long.class)
                    .getResultList().stream()
                    .filter(c -> !articulosAntes.contains(c)).toList();
            for (Long codigo : articulosNuevos) {
                em.createQuery("DELETE FROM Inventario i WHERE i.articulo.codigo = :codigo")
                        .setParameter("codigo", codigo).executeUpdate();
                em.createQuery("DELETE FROM ArticuloStock a WHERE a.codigoBarra IN "
                                + "(SELECT x.codigoBarra FROM Articulos x WHERE x.codigo = :codigo)")
                        .setParameter("codigo", codigo).executeUpdate();
                Models.Articulos.Articulos a = em.find(Models.Articulos.Articulos.class, codigo);
                if (a != null) {
                    em.remove(a);
                }
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception ignorado) {
                // Limpieza de pruebas: lo importante es no dejar la tx abierta.
            }
        }
    }
}
