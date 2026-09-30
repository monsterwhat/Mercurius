package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Receptor;
import Models.Inventario;
import Models.NotaCredito;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.ComprobantesEmitidosService;
import Services.HaciendaServiceFacade;
import Services.InventarioService;
import Services.NotaCreditoService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * La NC se confirma ANTES de hablar con Hacienda, y el envío no retiene el
 * bloqueo del consecutivo de la nota de crédito.
 *
 * <p>Antes, {@code DevolucionesResource.authorize} era una sola transacción
 * ({@code @Transactional}) que guardaba la devolución <em>y</em> mandaba la NC
 * a Hacienda con su sondeo de estado. Como el consecutivo de la NC se numera
 * con {@code PESSIMISTIC_WRITE} sobre su fila, esa E/S de red corría con el
 * bloqueo tomado: cada devolución concurrente esperaba a que la anterior
 * terminara de hablar con Hacienda, y con un pool de 20 conexiones veinte
 * devoluciones simultáneas bastaban para agotarlo.</p>
 *
 * <p>La primera prueba lo patea así: la devolución A entra al envío y la
 * frontera de Hacienda queda <em>bloqueada</em> por la prueba (un latch), que es
 * el peor caso del sondeo de estado. Mientras A sigue ahí, la devolución B
 * entra por HTTP al mismo endpoint, contra otra factura y por ende con el
 * mismo par (sucursal, terminal, tipo "02") — o sea la misma fila bloqueada— y
 * tiene que <b>terminar</b>:</p>
 * <ol>
 *   <li>se espera a que A entre al envío;</li>
 *   <li>se comprueba que la NC de A YA está en la base: se ve desde este hilo
 *       aunque su request todavía no haya respondido, que es la prueba de que
 *       la fase (a) confirmó antes de la fase (b);</li>
 *   <li>B autoriza su propia devolución por HTTP. B pide el mismo
 *       {@code SELECT … FOR UPDATE} sobre el mismo consecutivo, así que si el
 *       envío siguiera dentro de la transacción B se quedaría esperando hasta
 *       que la prueba soltara el latch — que es exactamente lo que falla con el
 *       código anterior;</li>
 *   <li>se suelta A y las dos devoluciones quedan confirmadas y enviadas.</li>
 * </ol>
 *
 * <p>La segunda prueba fija el otro extremo: cuando Hacienda rechaza, la
 * devolución sigue confirmada, la NC conserva su motivo de fondo y el sobre de
 * respuesta es exactamente el de siempre ({@code ncGenerada=true} con el mismo
 * {@code mensaje}), porque la fase (b) no relanza — una devolución ya
 * registrada no se puede desregistrar.</p>
 *
 * <p><b>La frontera de Hacienda está STUBBED con {@code @InjectMock}:</b>
 * {@link HaciendaServiceFacade} es exactamente lo que
 * {@code ComprobanteService.enviarComprobanteAHacienda} llama, así que la
 * firma, el XML y el HTTPS no se ejecutan —el
 * {@link Services.ComprobanteService} real corre, que es lo que hay que
 * probar—. No es una prueba de integración con Hacienda, es una prueba de
 * <em>orden y de duración de la transacción</em>.</p>
 *
 * <p><b>Ambas devoluciones van por HTTP</b> (a diferencia de
 * {@code PosFacturarEnvioFueraDeTransaccionTest}, que dispara la segunda sobre
 * el servicio): acá el recurso completo es lo que hay que poner en paralelo, y
 * cada request de RestAssured trae su propio contexto, así que cada hilo tiene
 * su sesión de Hibernate. Un hilo pelado no la tendría.</p>
 *
 * <p><b>Fixtures:</b> dos facturas propias con un consecutivo único, receptor
 * homónimo del cliente sembrado por import-test.sql y su configuración de
 * aplicación; las notas de crédito, movimientos de inventario, NCs, filas de la
 * bandeja de Art. 21 párr. 3 y facturas que la prueba crea se borran en el
 * {@code finally}. Los artículos no se siembran porque la devolución no toca
 * {@code ArticuloStock} (paridad legacy {@code create()}).</p>
 */
@QuarkusTest
@Tag("devoluciones")
@DisplayName("Devoluciones: confirmar antes de enviar; el envío no retiene el consecutivo")
class DevolucionEnvioFueraDeTransaccionTest extends support.ContextPathIsolation {

    private static final Logger LOG = Logger.getLogger(DevolucionEnvioFueraDeTransaccionTest.class);

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/devoluciones";

    /** import-test.sql: admin / admin123 (BCrypt cost 12), rol admin. */
    private static final String SUPERVISOR = "admin";
    private static final String SUPERVISOR_PASS = "admin123";

    /** import-test.sql client the factura receptor must resolve to. */
    private static final String CLIENTE_CONTADO = "Cliente Contado";

    /** Motivo propio por factura: permite reconocer (y borrar) sus notas. */
    private static final String MOTIVO_A = "Devolucion concurrente A";
    private static final String MOTIVO_B = "Devolucion concurrente B";

    /** Margen para la devolucion B: si el bloqueo se retuviera, B no llegaría. */
    private static final int TIMEOUT_DEVOLUCION_B_SEGUNDOS = 20;
    private static final int TIMEOUT_ENVIO_SEGUNDOS = 30;
    /** Techo del latch, para que un fallo de aserción no deje hilos colgados. */
    private static final int TIMEOUT_LATCH_SEGUNDOS = 60;

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    NotaCreditoService notaCreditoService;

    @Inject
    InventarioService inventarioService;

    @Inject
    AppSettingsService appSettingsService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    /** La costura exacta que toca enviarComprobanteAHacienda: la de la red. */
    @InjectMock
    HaciendaServiceFacade haciendaServiceFacade;

    @BeforeEach
    void resetting() {
        reset(haciendaServiceFacade);
    }

    // ── Sesión / requests ───────────────────────────────────────────────

    private Map<String, String> sesionAdmin() {
        var login = given().redirects().follow(false).when().get(BASE + "/login");
        login.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(login.getCookies());
        var post = given().redirects().follow(false).cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", SUPERVISOR)
                .formParam("j_password", SUPERVISOR_PASS)
                .when().post(BASE + "/j_security_check");
        post.then().statusCode(302);
        cookies.putAll(post.getCookies());
        return cookies;
    }

    private RequestSpecification authed(Map<String, String> sesion) {
        RequestSpecification spec = given().redirects().follow(false).cookies(sesion);
        String token = sesion.get("csrf-token");
        if (token == null) {
            token = sesion.get("csrftoken");
        }
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }

    /** Autoriza la devolución de la línea 0, una unidad. */
    private Response autorizar(Map<String, String> sesion, long facturaId, String motivo) {
        return authed(sesion).contentType(ContentType.URLENC)
                .formParam("username", SUPERVISOR)
                .formParam("password", SUPERVISOR_PASS)
                .formParam("motivo", motivo)
                .formParam("lineaNumero", "0")
                .formParam("lineaCantidad", "1")
                .when().post(API + "/" + facturaId + "/authorize");
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    /** La factura sembrada va ACEPTADA: así el guard del Art. 19 la deja pasar. */
    private ComprobantesEmitidos sembrarFactura() {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivoUnico());
        encabezado.setFechaEmision(LocalDateTime.now().minusHours(2));
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("01");
        Receptor receptor = new Receptor();
        receptor.setNombre(CLIENTE_CONTADO);
        encabezado.setReceptor(receptor);

        DetalleServicio detalles = new DetalleServicio();
        detalles.setStatus(true);

        LineaDetalle gravado = new LineaDetalle();
        gravado.setDetalleServicio(detalles);
        gravado.setNumeroLinea(0);
        gravado.setCantidad(new BigDecimal("2"));
        gravado.setPrecioUnitario(new BigDecimal("5000.00"));
        gravado.setDetalle("Producto Gravado Concurrencia");
        gravado.setMontoTotal(new BigDecimal("10000.00"));
        gravado.setSubTotal(new BigDecimal("10000.00"));
        Impuesto impuesto = new Impuesto();
        impuesto.setLineaDetalle(gravado);
        impuesto.setCodigo("01");
        impuesto.setCodigoTarifaIVA("01");
        impuesto.setTarifa(new BigDecimal("13"));
        impuesto.setMonto(new BigDecimal("1300.00000"));
        gravado.setImpuestos(new ArrayList<>(List.of(impuesto)));

        LineaDetalle exento = new LineaDetalle();
        exento.setDetalleServicio(detalles);
        exento.setNumeroLinea(1);
        exento.setCantidad(new BigDecimal("1"));
        exento.setPrecioUnitario(new BigDecimal("2000.00"));
        exento.setDetalle("Producto Exento Concurrencia");
        exento.setMontoTotal(new BigDecimal("2000.00"));
        exento.setSubTotal(new BigDecimal("2000.00"));

        detalles.setLineasDetalle(new ArrayList<>(List.of(gravado, exento)));

        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalVenta(new BigDecimal("12000.00"));
        resumen.setTotalImpuesto(new BigDecimal("1300.00000"));
        resumen.setTotalComprobante(new BigDecimal("13300.00"));

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setSchemaVersion("4.4");
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        comprobante.setResumen(resumen);
        comprobante.setStatus(true);
        comprobante.setUser("t32envio-" + UUID.randomUUID().toString().substring(0, 8));
        comprobante.setHaciendaEstado("ACEPTADO");
        return emitidosService.createAndReturn(comprobante);
    }

    /** Sin identificación no se genera clave y no hay NC que enviar. */
    private void asegurarConfiguracion() {
        Models.ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
        if (settings == null) {
            settings = new Models.ConfiguracionAplicacion();
            settings.setEstatus(Boolean.TRUE);
            settings.setIdentificacion("310112345678");
            settings.setTipoIdentificacion("02");
            settings.setNombrePerfil("T32 devoluciones envio");
            settings.setCodigoSucursal("001");
            settings.setCodigoTerminal("001");
            appSettingsService.create(settings);
            return;
        }
        if (settings.getIdentificacion() == null || settings.getIdentificacion().isBlank()) {
            settings.setIdentificacion("310112345678");
            settings.setTipoIdentificacion("02");
            appSettingsService.update(settings);
        }
    }

    private static String consecutivoUnico() {
        String sufijo = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase();
        String valor = "T32E" + sufijo;
        return valor.length() > 20 ? valor.substring(0, 20) : valor;
    }

    // ── Observación y limpieza ──────────────────────────────────────────

    private Set<Long> idsDeComprobantes() {
        return emitidosService.listAll().stream()
                .map(ComprobantesEmitidos::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** Las NC que la prueba creó: las que no estaban antes de correr. */
    private List<ComprobantesEmitidos> ncNuevas(Set<Long> idsPrevios) {
        List<ComprobantesEmitidos> nuevas = new ArrayList<>();
        for (ComprobantesEmitidos comprobante : emitidosService.listAll()) {
            if (idsPrevios.contains(comprobante.getId())) {
                continue;
            }
            nuevas.add(comprobante);
        }
        return nuevas;
    }

    private long notasDe(long facturaId) {
        List<NotaCredito> notas = notaCreditoService.listPorComprobante(facturaId);
        return notas == null ? 0 : notas.size();
    }

    /**
     * Borra lo que la prueba dejó. Las filas con dueño (notas, movimientos, NCs
     * y facturas) van por los servicios, que resuelven las cascadas del mapeo;
     * la bandeja de Art. 21 párr. 3 se borra con una consulta propia porque
     * referencia a la NC por id y no tiene dependientes. Todo es cosmético
     * (%test dropea y recrea al arrancar), así que una limpieza que falle no
     * puede tumbar una prueba que sí pasó: se registra y se sigue.
     */
    private void limpiar(Set<Long> idsPrevios, List<ComprobantesEmitidos> facturas) {
        List<ComprobantesEmitidos> nuevas = ncNuevas(idsPrevios);
        try {
            utx.begin();
            if (!nuevas.isEmpty()) {
                em.createQuery("DELETE FROM EnvioFueraLinea e WHERE e.comprobanteId IN :ids")
                        .setParameter("ids", nuevas.stream().map(ComprobantesEmitidos::getId).toList())
                        .executeUpdate();
            }
            utx.commit();
        } catch (Exception e) {
            rollbackBestEffort();
            LOG.warn("No se pudo limpiar la bandeja de envio diferido: " + e.getMessage()
                    + " | cosmético: %test dropea el esquema al arrancar", e);
        }

        for (ComprobantesEmitidos nc : nuevas) {
            ComprobantesEmitidos managed = emitidosService.find(nc.getId());
            if (managed != null) {
                emitidosService.delete(managed);
            }
        }
        for (ComprobantesEmitidos factura : facturas) {
            for (NotaCredito nota : orEmpty(notaCreditoService.listPorComprobante(factura.getId()))) {
                notaCreditoService.delete(nota);
            }
            for (Inventario inv : movimientosDe(factura)) {
                inventarioService.delete(inv);
            }
            ComprobantesEmitidos managed = emitidosService.find(factura.getId());
            if (managed != null) {
                emitidosService.delete(managed);
            }
        }
    }

    private void rollbackBestEffort() {
        try {
            utx.rollback();
        } catch (Exception rollback) {
            LOG.warn("No se pudo revertir la limpieza: " + rollback.getMessage()
                    + " | cosmético: %test dropea el esquema al arrancar", rollback);
        }
    }

    private List<Inventario> movimientosDe(ComprobantesEmitidos factura) {
        String consecutivo = factura.getEncabezado() == null
                ? null : factura.getEncabezado().getNumeroConsecutivo();
        List<Inventario> encontrados = new ArrayList<>();
        for (Inventario inv : orEmpty(inventarioService.listAll())) {
            if ("Devolucion".equals(inv.getTipoMovimiento()) && inv.getNotas() != null
                    && consecutivo != null && inv.getNotas().contains(consecutivo)) {
                encontrados.add(inv);
            }
        }
        return encontrados;
    }

    private static <T> List<T> orEmpty(List<T> lista) {
        return lista == null ? List.of() : lista;
    }

    // ── 1. La NC se confirma antes del envío, y el envío no bloquea ──────

    @Test
    @DisplayName("con Hacienda lento, la segunda devolucion arma y confirma su NC igual")
    void segundaDevolucionNoEsperaAlEnvioDeLaPrimera() throws Exception {
        asegurarConfiguracion();
        Map<String, String> sesion = sesionAdmin();
        List<ComprobantesEmitidos> facturas = new ArrayList<>();
        Set<Long> idsPrevios = idsDeComprobantes();
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        // Techo del latch: sin esto, un fallo de aserción dejaría a A clavada
        // en Hacienda y el pool de conexiones del %test colgando.
        CountDownLatch liberarEnvio = new CountDownLatch(1);
        try {
            facturas.add(sembrarFactura());
            facturas.add(sembrarFactura());
            // La foto se toma DESPUÉS de sembrar: "lo nuevo" son las NC que la
            // prueba provoca, no la factura que ella misma creó.
            idsPrevios = idsDeComprobantes();

            // La frontera de Hacienda: la PRIMERA llamada se queda esperando (el
            // sondeo lento) y las siguientes responden ya.
            AtomicInteger llamadas = new AtomicInteger();
            CountDownLatch envioIniciado = new CountDownLatch(1);
            when(haciendaServiceFacade.isFidesEnabled()).thenReturn(false);
            when(haciendaServiceFacade.submitDocument(any())).thenAnswer(invocacion -> {
                if (llamadas.getAndIncrement() == 0) {
                    envioIniciado.countDown();
                    if (!liberarEnvio.await(TIMEOUT_LATCH_SEGUNDOS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("la prueba no liberó el envío lento");
                    }
                }
                return HaciendaServiceFacade.SubmitResult.accepted();
            });

            // Devolución A: el endpoint completo, por HTTP. Se queda adentro
            // del envío, así que su request no vuelve hasta que la prueba
            // suelte el latch; por eso va en un hilo aparte.
            long facturaA = facturas.get(0).getId();
            Future<Integer> devolucionA = hilos.submit(() -> autorizar(sesion, facturaA, MOTIVO_A).getStatusCode());
            assertThat(envioIniciado.await(TIMEOUT_ENVIO_SEGUNDOS, TimeUnit.SECONDS))
                    .as("la primera devolucion debe entrar a la fase de envío")
                    .isTrue();

            // A sigue dentro de Hacienda, pero su NC ya está confirmada: se ve
            // desde este hilo aunque su request no haya respondido.
            assertThat(ncNuevas(idsPrevios))
                    .as("la NC se confirma ANTES de hablar con Hacienda")
                    .hasSize(1);

            // Devolución B: mismo endpoint, otra factura y por ende el mismo
            // (sucursal, terminal, tipo "02"), o sea la fila del bloqueo.
            long facturaB = facturas.get(1).getId();
            long inicio = System.nanoTime();
            Response respuestaB = autorizar(sesion, facturaB, MOTIVO_B);
            long milisegundos = (System.nanoTime() - inicio) / 1_000_000L;

            assertThat(respuestaB.getStatusCode())
                    .as("la segunda devolucion también termina bien")
                    .isEqualTo(200);
            assertThat(milisegundos)
                    .as("la segunda devolucion no puede esperar al envío de la primera (tardó %d ms)",
                            milisegundos)
                    .isLessThan(TIMEOUT_DEVOLUCION_B_SEGUNDOS * 1000L);
            assertThat(devolucionA.isDone())
                    .as("A tenía que seguir en Hacienda cuando B terminó, "
                            + "si no la prueba no probó nada")
                    .isFalse();
            assertThat(ncNuevas(idsPrevios))
                    .as("la segunda NC también quedó confirmada")
                    .hasSize(2);

            liberarEnvio.countDown();
            assertThat(devolucionA.get(TIMEOUT_ENVIO_SEGUNDOS, TimeUnit.SECONDS))
                    .as("la devolucion lenta también termina bien")
                    .isEqualTo(200);

            // Y ACEPTADO: el estado de Hacienda lo sigue escribiendo el envío
            // (fuera de la transacción de la devolución), sin cambiar de valor.
            // Se relee con find() y no con la lista porque esta sesión de la
            // prueba ya tenía la NC cacheada de las aserciones anteriores:
            // una consulta no refresca una entidad ya administrada, y el
            // instance de arriba todavía dice ENVIADO aunque la fila ya esté
            // ACEPTADA. find() limpia la sesión antes de cargar.
            for (ComprobantesEmitidos nc : ncNuevas(idsPrevios)) {
                ComprobantesEmitidos releida = emitidosService.find(nc.getId());
                assertThat(releida).as("la NC %s debe seguir existiendo", nc.getId()).isNotNull();
                assertThat(releida.getHaciendaEstado())
                        .as("la NC %s quedó ACEPTADA por Hacienda", releida.getEncabezado().getClave())
                        .isEqualTo("ACEPTADO");
                assertThat(releida.getEncabezado().getEstado()).isEqualTo("ACEPTADO");
            }
        } finally {
            liberarEnvio.countDown();
            hilos.shutdownNow();
            limpiar(idsPrevios, facturas);
        }
    }

    // ── 2. Con Hacienda rechazando, la devolución sigue confirmada ───────

    @Test
    @DisplayName("si Hacienda rechaza, la devolucion sigue confirmada con el motivo a la vista")
    void rechazoDeHaciendaNoDeshaceLaDevolucion() {
        asegurarConfiguracion();
        List<ComprobantesEmitidos> facturas = new ArrayList<>();
        Set<Long> idsPrevios = idsDeComprobantes();
        try {
            ComprobantesEmitidos factura = sembrarFactura();
            facturas.add(factura);
            // La foto se toma DESPUÉS de sembrar: "lo nuevo" es la NC que la
            // prueba provoca, no la factura que ella misma creó.
            idsPrevios = idsDeComprobantes();

            when(haciendaServiceFacade.isFidesEnabled()).thenReturn(false);
            when(haciendaServiceFacade.submitDocument(any()))
                    .thenReturn(HaciendaServiceFacade.SubmitResult.rejected(
                            "documento rechazado por el proveedor de prueba"));

            Response respuesta = autorizar(sesionAdmin(), factura.getId(), MOTIVO_A);
            respuesta.then()
                    .statusCode(200)
                    // El sobre es el de siempre: la fase (b) no relanza ni
                    // cambia lo que el operador ve.
                    .body("data.ncGenerada", equalTo(true))
                    .body("data.mensaje", equalTo("Nota de Credito electronica generada"));
            String clave = respuesta.jsonPath().getString("data.clave");

            // La devolución está registrada y la NC escrita: el rechazo es de
            // Hacienda, no un fallo del recurso.
            assertThat(notasDe(factura.getId()))
                    .as("la nota de credito se registró igual")
                    .isEqualTo(1);
            assertThat(movimientosDe(factura))
                    .as("el inventario se devolvió igual")
                    .hasSize(1);
            assertThat(ncNuevas(idsPrevios))
                    .as("la NC existe aunque Hacienda la rechazara")
                    .hasSize(1);
            ComprobantesEmitidos nc = ncPorClave(clave);
            // El sello ENVIADO previo al envío se conserva igual que antes de
            // este cambio; el veredicto de fondo queda en el encabezado.
            assertThat(nc.getHaciendaEstado()).isEqualTo("ENVIADO");
            assertThat(nc.getEncabezado()).isNotNull();
            assertThat(nc.getEncabezado().getEstado()).isEqualTo("RECHAZADO");
            assertThat(nc.getEncabezado().getMotivoRechazo())
                    .contains("rechazado por el proveedor de prueba");
        } finally {
            limpiar(idsPrevios, facturas);
        }
    }

    /**
     * Lectura por clave de la NC: el sobre HTTP no trae su id, y
     * {@code findByClave} es el mismo lookup que usa el módulo.
     */
    private ComprobantesEmitidos ncPorClave(String clave) {
        List<ComprobantesEmitidos> porClave = emitidosService.findByClave(clave);
        assertThat(porClave).as("la NC con clave %s debe existir", clave).hasSize(1);
        return porClave.get(0);
    }
}
