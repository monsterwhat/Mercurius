package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Cabys;
import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Models.EntradaPago;
import Models.Usuarios;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CabysService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.DirectoryService;
import Services.EnvioFueraLineaService;
import Services.HaciendaServiceFacade;
import Services.LoginService;
import Services.Strategies.DocumentoStrategyFactory;
import Services.cart.CartSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La factura se confirma ANTES de hablar con Hacienda, y el envío no retiene el
 * bloqueo del consecutivo.
 *
 * <p>Antes, {@code ComprobanteService.crearComprobante} era una sola
 * transacción que armaba la factura <em>y</em> la enviaba a Hacienda con
 * sondeo de estado. Como el consecutivo se numera con
 * {@code PESSIMISTIC_WRITE} sobre su fila, esa E/S de red corría con el
 * bloqueo tomado: cada venta concurrente esperaba a que la anterior terminara
 * de hablar con Hacienda, y con un pool de 20 conexiones veinte ventas
 * simultáneas bastaban para agotarlo.</p>
 *
 * <p>La primera prueba lo patea así: la venta A entra al envío y la frontera
 * de Hacienda queda <em>bloqueada</em> por la prueba (un latch), que es el
 * peor caso del sondeo de estado. Mientras A sigue ahí, la venta B corre su
 * fase de armado —el mismo {@code crearComprobante} que el POS invoca por
 * HTTP, con el mismo carrito y el mismo par (sucursal, terminal, tipo), o sea
 * la misma fila bloqueada— y tiene que <b>terminar</b>:</p>
 * <ol>
 *   <li>se espera a que A entre al envío;</li>
 *   <li>se comprueba que la factura de A YA está en la base: se ve desde otro
 *       hilo aunque su request todavía no haya respondido;</li>
 *   <li>B arma y confirma su factura. B pide el mismo
 *       {@code SELECT … FOR UPDATE}, así que si el envío siguiera dentro de la
 *       transacción B se quedaría esperando hasta que la prueba soltara el
 *       latch — que es exactamente lo que falla con el código anterior;</li>
 *   <li>se libera A y las dos ventas quedan confirmadas.</li>
 * </ol>
 *
 * <p>La segunda prueba fija el otro extremo: cuando Hacienda rechaza, la
 * factura sigue confirmada y el motivo queda a la vista, en vez de una venta
 * perdida.</p>
 *
 * <p><b>La frontera de Hacienda está STUBBED con {@code @InjectMock}:</b>
 * {@link HaciendaServiceFacade} es exactamente lo que
 * {@code enviarComprobanteAHacienda} llama, así que la firma, el XML y el
 * HTTPS no se ejecutan. Esto no es una prueba de integración con Hacienda, es
 * una prueba de <em>orden y de duración de la transacción</em>. El sondeo de
 * conectividad también va mockeado
 * ({@link EnvioFueraLineaService#hayConectividadConHacienda()}) porque si no
 * dependería de que la máquina que corre las pruebas tenga salida a internet.</p>
 *
 * <p><b>Por qué B se dispara sobre el servicio y no por HTTP:</b> el bloqueo
 * que se está probando vive en la fase de armado, y llamarla directamente la
 * aísla de las cookies y del monitor por cajero, de modo que lo único que
 * puede detener a B es el bloqueo de la base. (Un segundo usuario de verdad
 * no es opción: {@code LoginService.create} no es
 * {@code @Transactional} y en un hilo de prueba falla en silencio.)</p>
 *
 * <p><b>Fixtures:</b> artículo exento con código de barras propio y
 * configuración de aplicación; las facturas que la prueba crea y sus PDF se
 * borran en el {@code finally}. El artículo se deja porque la venta dejó
 * inventario que lo referencia por FK.</p>
 */
@QuarkusTest
@DisplayName("POS: confirmar antes de enviar; el envío no retiene el consecutivo")
class PosFacturarEnvioFueraDeTransaccionTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String POS = BASE + "/api/app/pos";
    private static final String USUARIO = "admin";

    /** Precio = total exacto: artículo exento, sin nada que ajustar. */
    private static final String PRECIO = "1000";
    private static final String CABYS_CODIGO = "EVTPOS0001";
    private static final String TIPO_DOCUMENTO = "04";

    /** Margen para la venta B: si el bloqueo se retuviera, B no llegaría. */
    private static final int TIMEOUT_VENTA_B_SEGUNDOS = 20;
    private static final int TIMEOUT_ENVIO_SEGUNDOS = 30;
    /** Techo del latch, para que un fallo de aserción no deje hilos colgados. */
    private static final int TIMEOUT_LATCH_SEGUNDOS = 60;

    @Inject ArticulosService articulosService;
    @Inject CabysService cabysService;
    @Inject LoginService loginService;
    @Inject AppSettingsService appSettingsService;
    @Inject CartSessionStore cartSessionStore;
    @Inject ComprobantesEmitidosService emitidosService;
    @Inject DirectoryService dirService;
    @Inject ComprobanteService comprobanteService;
    @Inject DocumentoStrategyFactory strategyFactory;

    @InjectMock HaciendaServiceFacade haciendaServiceFacade;
    @InjectMock EnvioFueraLineaService envioFueraLineaService;

    private static final AtomicInteger SECUENCIA = new AtomicInteger(0);

    @BeforeEach
    void isolating() {
        reset(haciendaServiceFacade, envioFueraLineaService);
    }

    // ── Auth / helpers ──────────────────────────────────────────────────

    private Map<String, String> adminSession() {
        var lp = given().redirects().follow(false).when().get(BASE + "/login");
        lp.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(lp.getCookies());
        var login = given().redirects().follow(false).cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", USUARIO)
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get("csrf-token");
        if (token == null) {
            token = cookies.get("csrftoken");
        }
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private void ensureAppSettings() {
        ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
        if (settings == null) {
            settings = new ConfiguracionAplicacion();
            settings.setEstatus(true);
            settings.setNombre("Cajero envio");
            settings.setNombreNegocio("Mercurius Envio SA");
            settings.setTipoIdentificacion("02");
            settings.setIdentificacion("310123456789");
            settings.setTelefono("88888888");
            settings.setProvedor("Mercurius");
            settings.setCodigoActividad("620101");
            settings.setCodigoSucursal("001");
            settings.setCodigoTerminal("00001");
            appSettingsService.create(settings);
            return;
        }
        // Sin identificación no se genera clave y no hay venta que probar.
        if (settings.getIdentificacion() == null || settings.getIdentificacion().isBlank()) {
            settings.setIdentificacion("310123456789");
            settings.setTipoIdentificacion("02");
            appSettingsService.update(settings);
        }
    }

    /** Artículo exento: el total de la venta es el precio, exacto y sin IVA. */
    private Articulos sembrarArticulo() {
        Cabys cabys = cabysService.find(CABYS_CODIGO);
        if (cabys == null) {
            cabys = new Cabys(CABYS_CODIGO, "Articulo de prueba envio",
                    "Pruebas", "0", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        // 13 dígitos, todos numéricos: el código de barras viaja al comprobante
        // y un valor no numérico lo hace fallar más abajo.
        String barcode = String.format("9%012d", SECUENCIA.incrementAndGet());
        Articulos articulo = new Articulos();
        articulo.setNombre("Articulo envio " + barcode);
        articulo.setCodigoBarra(barcode);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal(PRECIO));
        precio.setPorcentajeUtilidad(BigDecimal.ZERO);
        precio.setPrecioConUtilidad(new BigDecimal(PRECIO));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));
        articulo.setCodigoCabys(cabys);

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private void escanear(Map<String, String> sesion, Articulos articulo) {
        cartSessionStore.remove(USUARIO);
        authed(sesion).contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);
    }

    private Response facturar(Map<String, String> sesion) {
        return authed(sesion).contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"" + TIPO_DOCUMENTO + "\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":" + PRECIO + "}],"
                        + "\"puntosARedimir\":0}")
                .when().post(POS + "/facturar");
    }

    private EntradaPago pagoEfectivo() {
        EntradaPago pago = new EntradaPago();
        pago.setMetodoPago("01");
        pago.setMonto(new BigDecimal(PRECIO));
        return pago;
    }

    /**
     * Una venta completa por el servicio, tal cual el POS la arma: fase (a) y
     * después fase (b). Corre en el hilo de la prueba a propósito —un hilo
     * pelado no tiene contexto de request y el {@code EntityManager} se
     * negaría a trabajar— y por eso el cronómetro de la prueba lo usa.
     */
    private Long armarYEnviarVenta(List<ArticuloCarrito> carrito) {
        Usuarios cajero = loginService.findByUsername(USUARIO);
        ComprobanteService.CrearComprobanteResult r = comprobanteService.crearComprobante(
                appSettingsService.returnCurrent(),
                carrito,
                null,
                null,
                cajero,
                strategyFactory.forCode(TIPO_DOCUMENTO),
                List.of(pagoEfectivo()),
                BigDecimal.ZERO);
        comprobanteService.enviarComprobanteCreado(r);
        return r.comprobante.getId();
    }

    // ── Limpieza ────────────────────────────────────────────────────────

    private Set<Long> idsDeComprobantes() {
        return emitidosService.listAll().stream()
                .map(ComprobantesEmitidos::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Borra lo que la prueba dejó. El PDF vive en disco y sobrevive al
     * drop-and-create del arranque, así que es lo que hay que limpiar de
     * verdad; el borrado de filas es cosmético y va best-effort para que una
     * limpieza no pueda tumbar una prueba que sí pasó.
     */
    private void limpiar(Set<Long> idsPrevios) {
        for (ComprobantesEmitidos comprobante : emitidosService.listAll()) {
            if (idsPrevios.contains(comprobante.getId())) {
                continue;
            }
            try {
                emitidosService.delete(comprobante);
            } catch (RuntimeException ignored) {
                // cosmético
            }
            try {
                Files.deleteIfExists(Paths.get(dirService.getFacturasDirPath(),
                        "tiqueteElectronico_" + comprobante.getId() + ".pdf"));
            } catch (java.io.IOException ignored) {
                // cosmético
            }
        }
        cartSessionStore.remove(USUARIO);
    }

    // ── 1. La factura se confirma antes del envío, y el envío no bloquea ──

    @Test
    @DisplayName("con Hacienda lento, la segunda venta arma y confirma su factura igual")
    void segundaVentaNoEsperaAlEnvioDeLaPrimera() throws Exception {
        ensureAppSettings();
        Articulos articulo = sembrarArticulo();
        Map<String, String> sesion = adminSession();
        escanear(sesion, articulo);

        // El carrito que armó el POS, copiado línea por línea: la segunda venta
        // factura las mismas líneas que la primera sobre el mismo
        // (sucursal, terminal, tipo), que es la fila del bloqueo.
        List<ArticuloCarrito> carrito = new ArrayList<>(
                cartSessionStore.getOrCreate(USUARIO).getCartContext().getCarrito());
        assertThat(carrito).as("el carrito debe tener la línea escaneada").hasSize(1);

        // Hacienda alcanzable: se intenta el envío inmediato.
        when(envioFueraLineaService.hayConectividadConHacienda()).thenReturn(true);
        when(haciendaServiceFacade.isFidesEnabled()).thenReturn(false);

        // La frontera de Hacienda: la PRIMERA llamada se queda esperando (el
        // sondeo lento) y las siguientes responden ya.
        AtomicInteger llamadas = new AtomicInteger();
        CountDownLatch envioIniciado = new CountDownLatch(1);
        CountDownLatch liberarEnvio = new CountDownLatch(1);
        when(haciendaServiceFacade.submitDocument(any())).thenAnswer(invocacion -> {
            if (llamadas.getAndIncrement() == 0) {
                envioIniciado.countDown();
                if (!liberarEnvio.await(TIMEOUT_LATCH_SEGUNDOS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("la prueba no liberó el envío lento");
                }
            }
            return HaciendaServiceFacade.SubmitResult.accepted();
        });

        Set<Long> idsPrevios = idsDeComprobantes();
        ExecutorService hilos = Executors.newSingleThreadExecutor();
        try {
            // Venta A: el camino completo del POS, por HTTP. Se queda adentro
            // del envío. Va en un hilo aparte porque su request no vuelve hasta
            // que la prueba suelte el latch.
            Future<Integer> ventaA = hilos.submit(() -> facturar(sesion).getStatusCode());
            assertThat(envioIniciado.await(TIMEOUT_ENVIO_SEGUNDOS, TimeUnit.SECONDS))
                    .as("la primera venta debe entrar a la fase de envío")
                    .isTrue();

            // A sigue dentro de Hacienda, pero su factura ya está confirmada:
            // se ve desde este hilo aunque su request no haya respondido.
            assertThat(idsDeComprobantes())
                    .as("la factura se confirma ANTES de hablar con Hacienda")
                    .hasSize(idsPrevios.size() + 1);

            // Venta B: la fase de armado, tal cual la invoca el POS. Pide el
            // mismo SELECT … FOR UPDATE sobre el mismo consecutivo, así que si
            // el envío siguiera dentro de la transacción B se quedaría
            // esperando hasta que la prueba soltara el latch.
            long inicio = System.nanoTime();
            Long idB = armarYEnviarVenta(carrito);
            long milisegundos = (System.nanoTime() - inicio) / 1_000_000L;

            assertThat(idB)
                    .as("la segunda venta debe confirmar su factura")
                    .isNotNull();
            assertThat(milisegundos)
                    .as("la segunda venta no puede esperar al envío de la primera (tardó %d ms)",
                            milisegundos)
                    .isLessThan(TIMEOUT_VENTA_B_SEGUNDOS * 1000L);
            assertThat(ventaA.isDone())
                    .as("A tenía que seguir en Hacienda cuando B terminó, "
                            + "si no la prueba no probó nada")
                    .isFalse();
            assertThat(idsDeComprobantes())
                    .as("la segunda factura también quedó confirmada")
                    .hasSize(idsPrevios.size() + 2);

            liberarEnvio.countDown();
            assertThat(ventaA.get(TIMEOUT_ENVIO_SEGUNDOS, TimeUnit.SECONDS))
                    .as("la venta lenta también termina bien")
                    .isEqualTo(200);
        } finally {
            liberarEnvio.countDown();
            hilos.shutdownNow();
            limpiar(idsPrevios);
        }
    }

    // ── 2. Con Hacienda rechazando, la venta sigue confirmada ────────────

    @Test
    @DisplayName("si Hacienda rechaza, la factura queda confirmada y el motivo queda visible")
    void rechazoDeHaciendaNoDeshaceLaVenta() {
        ensureAppSettings();
        Articulos articulo = sembrarArticulo();
        Map<String, String> sesion = adminSession();
        escanear(sesion, articulo);

        when(envioFueraLineaService.hayConectividadConHacienda()).thenReturn(true);
        when(haciendaServiceFacade.isFidesEnabled()).thenReturn(false);
        when(haciendaServiceFacade.submitDocument(any()))
                .thenReturn(HaciendaServiceFacade.SubmitResult.rejected(
                        "documento rechazado por el proveedor de prueba"));

        Set<Long> idsPrevios = idsDeComprobantes();
        try {
            Response respuesta = facturar(sesion);
            respuesta.then()
                    .statusCode(200)
                    .body("data.comprobanteId", notNullValue())
                    .body("data.haciendaMensaje", containsString("NO enviado a Hacienda"));
            int id = respuesta.then().extract().jsonPath().getInt("data.comprobanteId");

            // La venta está cobrada y su documento existe: el rechazo es de
            // Hacienda, no un fallo del POS.
            assertThat(idsDeComprobantes())
                    .as("un rechazo de Hacienda no puede borrar una venta ya cobrada")
                    .hasSize(idsPrevios.size() + 1);

            ComprobantesEmitidos comprobante = emitidosService.find((long) id);
            assertThat(comprobante).isNotNull();
            // El sello ENVIADO previo al envío se conserva igual que antes de
            // este cambio; el veredicto de fondo queda en el encabezado.
            assertThat(comprobante.getHaciendaEstado()).isEqualTo("ENVIADO");
            assertThat(comprobante.getEncabezado()).isNotNull();
            assertThat(comprobante.getEncabezado().getEstado()).isEqualTo("RECHAZADO");
            assertThat(comprobante.getEncabezado().getMotivoRechazo())
                    .contains("rechazado por el proveedor de prueba");
        } finally {
            limpiar(idsPrevios);
        }
    }
}
