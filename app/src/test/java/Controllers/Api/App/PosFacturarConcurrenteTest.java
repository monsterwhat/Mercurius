package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import Models.Articulos.Articulos;
import Models.Articulos.ArticuloPrecio;
import Models.Cabys;
import Models.Usuarios;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CabysService;
import Services.ComprobantesEmitidosService;
import Services.DirectoryService;
import Services.HaciendaServiceFacade;
import Services.LoginService;
import Services.cart.CartSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Diez ventas simultáneas facturan diez veces — ni una más, ni una menos.
 *
 * <p>Dos escenarios sobre el mismo invariante (un intento = una factura + un
 * movimiento de stock):</p>
 * <ol>
 *   <li><b>Diez cajeros a la vez.</b> Diez sesiones distintas venden en paralelo.
 *       El bloqueo pesimista del consecutivo las serializa; al final debe haber
 *       10 facturas con 10 consecutivos distintos y cada artículo descontado
 *       exactamente una vez. Sin el bloqueo, dos ventas leerían el mismo
 *       consecutivo o partirían el stock.</li>
 *   <li><b>Tormenta sobre el mismo carrito.</b> Diez POST del mismo cajero a la
 *       vez (doble/triple clic, reintento impaciente). El monitor por entrada +
 *       el sello de idempotencia hacen que solo la primera facture: el resto
 *       reintentan lo ya creado o ven el carrito vacío. Invariante: UNA factura
 *       y UNA rebaja, sin importar el entrelazado.</li>
 * </ol>
 *
 * <p>El envío a Hacienda se stubbea a rechazo instantáneo: lo que se mide es la
 * integridad de numeración e inventario bajo concurrencia, no la red. El PDF es
 * real (nombres únicos por id de factura). Cada hilo arma su propio
 * RequestSpecification — RestAssured no se comparte entre hilos.</p>
 */
@QuarkusTest
@DisplayName("POS concurrente: diez ventas a la vez facturan diez veces")
class PosFacturarConcurrenteTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String POS = BASE + "/api/app/pos";
    private static final int CAJEROS = 10;

    private static final AtomicInteger SECUENCIA = new AtomicInteger(0);

    @Inject ArticulosService articulosService;
    @Inject CabysService cabysService;
    @Inject LoginService loginService;
    @Inject AppSettingsService appSettingsService;
    @Inject CartSessionStore cartSessionStore;
    @Inject ComprobantesEmitidosService emitidosService;
    @Inject DirectoryService dirService;
    @Inject EntityManager em;

    @InjectMock HaciendaServiceFacade haciendaFacade;

    private static String tag() {
        return String.format("%04d", SECUENCIA.getAndIncrement());
    }

    private void ensureAppSettings() {
        if (appSettingsService.returnCurrent() != null) {
            return;
        }
        var settings = new Models.ConfiguracionAplicacion();
        settings.setEstatus(true);
        settings.setNombre("Cajero concurrente");
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("00001");
        settings.setIdentificacion("3100100008");
        settings.setTipoIdentificacion("02");
        appSettingsService.create(settings);
    }

    private Cabys ensureCabys() {
        Cabys cabys = cabysService.find("CONCPOS001");
        if (cabys == null) {
            cabys = new Cabys("CONCPOS001", "Exento concurrente",
                    "Pruebas", "0", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        return cabys;
    }

    private Articulos sembrarArticulo() {
        String t = tag();
        Articulos articulo = new Articulos();
        articulo.setNombre("Articulo concurrente " + t);
        articulo.setCodigoBarra("CONC" + t);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal("1000"));
        precio.setPorcentajeUtilidad(BigDecimal.ZERO);
        precio.setPrecioConUtilidad(new BigDecimal("1000"));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));
        articulo.setCodigoCabys(ensureCabys());

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private String sembrarCajero(String t) {
        String username = "concur" + t;
        if (loginService.findByUsername(username) == null) {
            Usuarios u = new Usuarios();
            u.setUsername(username);
            u.setPassword("concpass");
            u.setGroupName("facturacion");
            u.setStatus(true);
            u.setEmail(username + "@mercurius.local");
            loginService.create(u);
        }
        return username;
    }

    private Map<String, String> sesionDe(String username, String password) {
        var lp = given().redirects().follow(false).when().get(BASE + "/login");
        lp.then().statusCode(200);
        Map<String, String> c = new HashMap<>(lp.getCookies());
        var login = given().redirects().follow(false).cookies(c)
                .contentType(ContentType.URLENC)
                .formParam("j_username", username)
                .formParam("j_password", password)
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        c.putAll(login.getCookies());
        return c;
    }

    private String csrfDe(Map<String, String> c) {
        String t = c.get("csrf-token");
        return t != null ? t : c.get("csrftoken");
    }

    private void escanear(Map<String, String> s, String codigoBarra) {
        given().redirects().follow(false).cookies(s)
                .header("X-CSRF-TOKEN", csrfDe(s))
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + codigoBarra + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);
    }

    private Response facturar(Map<String, String> s) {
        return given().redirects().follow(false).cookies(s)
                .header("X-CSRF-TOKEN", csrfDe(s))
                .contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":1000}],"
                        + "\"puntosARedimir\":0}")
                .when().post(POS + "/facturar");
    }

    private BigDecimal stockDe(String codigoBarra) {
        List<Models.Articulos.ArticuloStock> filas = em.createQuery(
                        "SELECT a FROM ArticuloStock a WHERE a.codigoBarra = :codigo",
                        Models.Articulos.ArticuloStock.class)
                .setParameter("codigo", codigoBarra)
                .getResultList();
        return filas.isEmpty() ? null : filas.get(0).getStock();
    }

    private String consecutivoDe(Long comprobanteId) {
        Models.ComprobantesEmitidos c =
                em.find(Models.ComprobantesEmitidos.class, comprobanteId);
        assertThat(c).isNotNull();
        assertThat(c.getEncabezado()).isNotNull();
        return c.getEncabezado().getNumeroConsecutivo();
    }

    private void limpiarFacturas(Set<Long> idsPrevios) {
        for (Models.ComprobantesEmitidos comprobante : emitidosService.listAll()) {
            if (!idsPrevios.contains(comprobante.getId())) {
                emitidosService.delete(comprobante);
                try {
                    Files.deleteIfExists(Paths.get(dirService.getFacturasDirPath(),
                            "tiqueteElectronico_" + comprobante.getId() + ".pdf"));
                } catch (java.io.IOException e) {
                    throw new AssertionError("No se pudo borrar el PDF de prueba "
                            + comprobante.getId() + ": " + e.getMessage(), e);
                }
            }
        }
    }

    @Test
    @DisplayName("diez cajeros simultaneos producen diez facturas distintas")
    void diezCajerosSimultaneos() throws Exception {
        ensureAppSettings();
        when(haciendaFacade.submitDocument(any()))
                .thenReturn(HaciendaServiceFacade.SubmitResult.rejected("rechazo de prueba"));

        Set<Long> idsPrevios = new HashSet<>();
        emitidosService.listAll().forEach(f -> idsPrevios.add(f.getId()));
        List<Articulos> articulos = new ArrayList<>();
        List<Map<String, String>> sesiones = new ArrayList<>();
        try {
            // Preparación secuencial: 10 cajeros con su carrito de 1 unidad.
            for (int i = 0; i < CAJEROS; i++) {
                String t = tag();
                String username = sembrarCajero(t);
                cartSessionStore.remove(username);
                Articulos articulo = sembrarArticulo();
                articulos.add(articulo);
                Map<String, String> s = sesionDe(username, "concpass");
                escanear(s, articulo.getCodigoBarra());
                sesiones.add(s);
            }

            // Disparo simultáneo de las 10 ventas.
            CountDownLatch salida = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(CAJEROS);
            List<Future<Response>> futuros = new ArrayList<>();
            for (Map<String, String> s : sesiones) {
                Callable<Response> tiro = () -> {
                    salida.await(60, TimeUnit.SECONDS);
                    return facturar(s);
                };
                futuros.add(pool.submit(tiro));
            }
            salida.countDown();

            List<Response> respuestas = new ArrayList<>();
            for (Future<Response> f : futuros) {
                respuestas.add(f.get(300, TimeUnit.SECONDS));
            }
            pool.shutdown();

            // Las 10 ventas cierran con 200.
            for (Response r : respuestas) {
                r.then().statusCode(200);
            }
            Set<Long> ids = new HashSet<>();
            for (Response r : respuestas) {
                ids.add(r.jsonPath().getLong("data.comprobanteId"));
            }
            assertThat(ids)
                    .as("diez ventas simultaneas deben producir diez facturas distintas")
                    .hasSize(CAJEROS);

            // Diez consecutivos distintos: el bloqueo pesimista serializó bien.
            Set<String> consecutivos = new HashSet<>();
            for (Long id : ids) {
                consecutivos.add(consecutivoDe(id));
            }
            assertThat(consecutivos)
                    .as("cada factura lleva su propio consecutivo")
                    .hasSize(CAJEROS);

            // Cada artículo se descontó exactamente una vez.
            for (Articulos articulo : articulos) {
                assertThat(stockDe(articulo.getCodigoBarra()))
                        .as("stock de " + articulo.getCodigoBarra())
                        .isEqualByComparingTo(new BigDecimal("-1"));
            }
        } finally {
            limpiarFacturas(idsPrevios);
            cartSessionStore.remove("admin");
        }
    }

    @Test
    @DisplayName("diez envios sobre el mismo carrito facturan una sola vez")
    void tormentaSobreElMismoCarrito() throws Exception {
        ensureAppSettings();
        when(haciendaFacade.submitDocument(any()))
                .thenReturn(HaciendaServiceFacade.SubmitResult.rejected("rechazo de prueba"));

        Set<Long> idsPrevios = new HashSet<>();
        emitidosService.listAll().forEach(f -> idsPrevios.add(f.getId()));
        try {
            String t = tag();
            String username = sembrarCajero(t);
            cartSessionStore.remove(username);
            Articulos articulo = sembrarArticulo();
            Map<String, String> s = sesionDe(username, "concpass");
            escanear(s, articulo.getCodigoBarra());

            // Diez POST a la vez contra el mismo carrito: doble clic masivo.
            // No se afirma el código de cada respuesta (200 real, replay 200 o
            // carrito vacío según el entrelazado) sino los invariantes: una
            // factura y una rebaja, pase lo que pase con el orden.
            CountDownLatch salida = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(CAJEROS);
            List<Future<Response>> futuros = new ArrayList<>();
            for (int i = 0; i < CAJEROS; i++) {
                Callable<Response> tiro = () -> {
                    salida.await(60, TimeUnit.SECONDS);
                    return facturar(s);
                };
                futuros.add(pool.submit(tiro));
            }
            salida.countDown();

            for (Future<Response> f : futuros) {
                Response r = f.get(300, TimeUnit.SECONDS);
                assertThat(r.getStatusCode()).isIn(200, 409, 500);
            }
            pool.shutdown();

            long nuevas = emitidosService.listAll().stream()
                    .filter(f -> !idsPrevios.contains(f.getId())).count();
            assertThat(nuevas)
                    .as("diez envios simultaneos sobre un carrito crean una sola factura")
                    .isEqualTo(1);
            assertThat(stockDe(articulo.getCodigoBarra()))
                    .as("y descuentan inventario una sola vez")
                    .isEqualByComparingTo(new BigDecimal("-1"));
        } finally {
            limpiarFacturas(idsPrevios);
        }
    }
}
