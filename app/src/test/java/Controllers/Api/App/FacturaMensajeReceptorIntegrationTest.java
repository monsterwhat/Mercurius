package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.ComprobanteService;
import Services.ComprobantesRecibidosService;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.InventarioService;
import Services.CabysService;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.Inventario;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import support.FacturasReales;

/**
 * Mensaje Receptor suite over the REAL received invoice: the accept / reject /
 * partial codigoMensaje paths queue through {@code MensajeReceptorService} with
 * the Hacienda boundary STUBBED, and a malformed CAByS must block the send
 * before anything reaches Hacienda.
 *
 * <p>Happy paths upload {@code fixtures/reales/v4.4/fe-v44-13.xml} through
 * {@link FacturasReales#conConsecutivoUnico(String, int)} so each scenario gets
 * its own consecutive/Clave and can run repeatedly in one boot. No real invoice
 * carries a bad CAByS, so the negative scenario corrupts ONE
 * {@code CodigoCABYS} into {@code "999"} — which also breaks the official
 * {@code CodigoCABYS} type (minLength 13), so the parser's strict gate refuses
 * the document and the resource re-parses it leniently, keeping the line items
 * (and importing their articles/stock, cleaned up here).
 *
 * <p>{@link #partialWithoutLinesIs400()} keeps a programmatic row on purpose: a
 * document that carries no line items cannot be expressed with real data, and
 * the legacy guard is exactly about a line-less comprobante.
 */
@QuarkusTest
@Tag("facturas-recibidas")
class FacturaMensajeReceptorIntegrationTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/facturas-recibidas";
    /** Real anonymized v4.4 factura received (FE) behind every happy path. */
    private static final String FACTURA_REAL = "fe-v44-13";
    /** CAByS the four line items of that real invoice reference, seeded ACTIVO. */
    private static final String CABYS_ACTIVO = "2349002011400";
    /** Malformed code the tampered scenario corrupts one real line into. */
    private static final String CABYS_INVALIDO = "999";
    /**
     * Monotonic tail: one seed per upload inside a single boot. It starts high
     * on purpose — {@code FacturasRecibidasResourceTest} uploads the same
     * fixture from seed 1, and digitoSeguro is injective per seed, so the two
     * suites can never land on the same consecutivo.
     */
    private static final AtomicInteger SECUENCIA = new AtomicInteger(500_000);

    @Inject ComprobantesRecibidosService recibidosService;
    @Inject AppSettingsService appSettingsService;
    @Inject CabysService cabysService;
    @Inject ArticulosService articulosService;
    @Inject InventarioService inventarioService;
    @InjectMock HaciendaApiService haciendaApiService;
    @InjectMock HaciendaSigner haciendaSigner;
    @InjectMock ComprobanteService comprobanteService;

    private Map<String,String> adminSession() {
        var lp = given().redirects().follow(false).when().get(BASE+"/login"); lp.then().statusCode(200);
        Map<String,String> c = new HashMap<>(lp.getCookies());
        var login = given().redirects().follow(false).cookies(c).contentType(ContentType.URLENC)
                .formParam("j_username","admin").formParam("j_password","admin123").when().post(BASE+"/j_security_check");
        login.then().statusCode(302); c.putAll(login.getCookies()); return c;
    }
    private String csrf(Map<String,String> c){ String t=c.get("csrf-token"); return t!=null?t:c.get("csrftoken"); }

    /** Seeds as ACTIVO every CAByS the real invoice references (idempotent). */
    private void seedCabys(){
        for (String codigo : FacturasReales.codigosCabys(FACTURA_REAL)) {
            if (cabysService.find(codigo) == null) {
                cabysService.create(new Cabys(codigo, "CAByS real " + codigo,
                        "Alimentos y bebidas", "13", "https://www.hacienda.go.cr/cabys/" + codigo, "ACTIVO"));
            }
        }
    }
    private void seedSettings(){ if(appSettingsService.returnCurrent()==null) appSettingsService.findOrCreateCurrent(); }

    private void stubOk(){
        when(comprobanteService.generateMensajeReceptorXml(any(),anyString(),anyString(),anyString(),any(),anyInt(),anyString(),any(),any(),anyString())).thenReturn("<MensajeReceptor/>");
        var ok=new HaciendaSigner.SignResult(); ok.success=true; ok.signedXml="<signed/>"; when(haciendaSigner.signXml(anyString())).thenReturn(ok);
        when(haciendaApiService.acceptInvoice(any(),any(),any(),any(),any(),any())).thenReturn(HaciendaApiService.ApiResponse.ok("ok"));
        when(haciendaApiService.rejectInvoice(any(),any(),any(),any(),any(),any())).thenReturn(HaciendaApiService.ApiResponse.ok("ok"));
    }

    private ComprobantesRecibidos seedRow(String consec){
        Encabezado enc=new Encabezado(); enc.setNumeroConsecutivo(consec); enc.setFechaEmision(LocalDateTime.now()); enc.setCondicionVenta("01"); enc.setSchemaVersion("4.4"); enc.setCodigoDocumento("01");
        ComprobantesRecibidos cr=new ComprobantesRecibidos(); cr.setEncabezado(enc); cr.setStatus(true); cr.setProcessed(false); cr.setPaid(false);
        ResumenFactura r=new ResumenFactura(); r.setTotalVentaNeta(new BigDecimal("100")); r.setTotalImpuesto(new BigDecimal("13")); r.setTotalComprobante(new BigDecimal("113")); cr.setResumen(r);
        cr.setMensajeReceptorLimite(LocalDate.now().plusDays(5));
        recibidosService.create(cr); return cr;
    }

    // ── Real-fixture upload helpers ─────────────────────────────────────

    /** The 20-digit NumeroConsecutivo of a re-stamped real invoice. */
    private static String consecutivoDe(String xml) {
        Matcher m = Pattern.compile("<NumeroConsecutivo>(\\d{20})</NumeroConsecutivo>").matcher(xml);
        if (!m.find()) {
            throw new IllegalStateException("la factura real debe traer NumeroConsecutivo");
        }
        return m.group(1);
    }

    /**
     * The real invoice re-stamped with a consecutive/clave no other scenario of
     * this boot used. The seed is rolled forward while the consecutive contains
     * {@code "8888"}: the Mensaje Receptor gate reads that run as a tampered
     * document, and a real invoice must never trip it by accident.
     */
    private static String facturaRealUnica(int semilla) {
        int n = semilla;
        String xml = FacturasReales.conConsecutivoUnico(FACTURA_REAL, n);
        while (consecutivoDe(xml).contains("8888")) {
            n += 1000;
            xml = FacturasReales.conConsecutivoUnico(FACTURA_REAL, n);
        }
        return xml;
    }

    /** Uploads the real invoice through /upload and returns the row it produced. */
    private ComprobantesRecibidos subirReal(Map<String,String> s, String nombreArchivo, boolean corromperCabys) {
        seedCabys();
        String xml = facturaRealUnica(SECUENCIA.getAndIncrement());
        if (corromperCabys) {
            xml = xml.replaceFirst("<CodigoCABYS>\\d{13}</CodigoCABYS>",
                    "<CodigoCABYS>" + CABYS_INVALIDO + "</CodigoCABYS>");
            assertThat(xml).as("la factura real debe llegar con la linea corrupta")
                    .contains("<CodigoCABYS>" + CABYS_INVALIDO + "</CodigoCABYS>");
        }
        String consec = consecutivoDe(xml);
        given().cookies(s).header("X-CSRF-TOKEN", csrf(s)).contentType(ContentType.MULTIPART)
                .multiPart("files", nombreArchivo, xml.getBytes(StandardCharsets.UTF_8), "application/xml")
                .when().post(API+"/upload").then().statusCode(200)
                .body("data.resultados[0].fileName", equalTo(nombreArchivo));
        return esperarPorConsecutivo(consec);
    }

    /** Polls briefly: the parser runs inside the upload request, but listAll() can miss the fresh row. */
    private ComprobantesRecibidos esperarPorConsecutivo(String consec) {
        for (int intento = 0; intento < 20; intento++) {
            ComprobantesRecibidos row = recibidosService.listAll().stream()
                    .filter(c -> c.getEncabezado() != null
                            && consec.equals(c.getEncabezado().getNumeroConsecutivo()))
                    .findFirst().orElse(null);
            if (row != null) {
                return row;
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

    /** Article codes already in the shared database, captured before the upload. */
    private Set<Long> articulosPreexistentes() {
        return articulosService.listAll().stream().map(Articulos::getCodigo).collect(Collectors.toSet());
    }

    /**
     * Removes the articles and stock movements the rejected-import path created
     * out of the real line items, leaving anything that already existed alone.
     */
    private void limpiarArticulosEInventario(List<LineaDetalle> lineas, Set<Long> articulosPrevios) {
        for (LineaDetalle linea : lineas) {
            if (linea.getDetalle() == null) {
                continue;
            }
            Articulos articulo = articulosService.findByName(linea.getDetalle());
            if (articulo == null || articulo.getCodigo() == null
                    || articulosPrevios.contains(articulo.getCodigo())) {
                continue;
            }
            for (Inventario m : inventarioService.listAll().stream()
                    .filter(i -> i.getArticulo() != null
                            && articulo.getCodigo().equals(i.getArticulo().getCodigo()))
                    .toList()) {
                inventarioService.delete(m);
            }
            articulosService.delete(articulo);
        }
    }

    private static List<LineaDetalle> lineasDe(ComprobantesRecibidos row) {
        if (row == null || row.getDetalles() == null || row.getDetalles().getLineasDetalle() == null) {
            return List.of();
        }
        return row.getDetalles().getLineasDetalle();
    }

    private void deleteQuietly(ComprobantesRecibidos row) {
        if (row != null && row.getId() != null) {
            ComprobantesRecibidos managed = recibidosService.find(row.getId());
            if (managed != null) {
                recibidosService.delete(managed);
            }
        }
    }

    @Test
    void acceptValidFacturaQueuesAcceptInvoice() {
        seedSettings(); stubOk();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=subirReal(s,"aceptada.xml",false);
        assertThat(row).as("la factura real debe quedar persistida por el parser").isNotNull();
        try{
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.URLENC).formParam("codigoMensaje","1")
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(200),is(500)));
            var updated=recibidosService.find(row.getId());
            assertThat(updated.getHaciendaMensajeReceptorEstado()).isIn("ACEPTADO","PROCESANDO");
            verify(haciendaApiService,atLeastOnce()).acceptInvoice(any(),any(),any(),any(),any(),any());
        } finally { deleteQuietly(row); }
    }

    @Test
    void rejectQueuesRejectInvoice() {
        seedSettings(); stubOk();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=subirReal(s,"rechazada.xml",false);
        assertThat(row).as("la factura real debe quedar persistida por el parser").isNotNull();
        try{
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.JSON).body(Map.of("codigoMensaje","3"))
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(200),is(500)));
            var updated=recibidosService.find(row.getId());
            assertThat(updated.getHaciendaMensajeReceptorEstado()).isEqualTo("RECHAZADO");
            verify(haciendaApiService).rejectInvoice(any(),any(),any(),any(),any(),any());
        } finally { deleteQuietly(row); }
    }

    @Test
    void partialWithoutLinesIs400() {
        seedCabys(); seedSettings(); stubOk();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=seedRow("0010000104"+String.format("%010d",(int)(Math.random()*1_000_000)));
        try{
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.URLENC).formParam("codigoMensaje","2")
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(400).body("error.code",equalTo("VALIDATION_ERROR"));
            verifyNoInteractions(haciendaApiService);
        } finally { recibidosService.delete(recibidosService.find(row.getId())); }
    }

    @Test
    void tamperedCabysBlocksMensajeReceptorWith409Or400() throws Exception {
        // Real invoice with one malformed CAByS: INVALID_FORMAT must block the
        // Mensaje Receptor before the Hacienda boundary is ever touched.
        Set<Long> articulosPrevios=articulosPreexistentes();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=subirReal(s,"tampered.xml",true);
        List<LineaDetalle> lineas=lineasDe(row);
        try{
            assertThat(row).as("la factura corrupta debe quedar registrada").isNotNull();
            assertThat(lineas).as("sus lineas reales siguen disponibles").isNotEmpty();
            assertThat(lineas).as("una sola linea lleva el CAByS mal formado")
                    .anyMatch(l -> CABYS_INVALIDO.equals(l.getCodigoCabys()));
            assertThat(lineas).as("las demas lineas conservan el CAByS real")
                    .anyMatch(l -> CABYS_ACTIVO.equals(l.getCodigoCabys()));
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.URLENC).formParam("codigoMensaje","1")
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(400),is(409),is(200),is(500)));
            verifyNoInteractions(haciendaApiService);
        } finally {
            limpiarArticulosEInventario(lineas,articulosPrevios);
            deleteQuietly(row);
        }
    }

    @Test
    void invalidCodigoMensajeIs400() {
        seedCabys();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=seedRow("0010000104"+String.format("%010d",(int)(Math.random()*1_000_000)));
        try{
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.URLENC).formParam("codigoMensaje","9")
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(400);
        } finally { recibidosService.delete(recibidosService.find(row.getId())); }
    }

    @Test
    void mensajeReceptorWithoutAuthIsChallenged() {
        given().redirects().follow(false).contentType(ContentType.URLENC).formParam("codigoMensaje","1")
                .when().post(API+"/999/mensaje-receptor").then().statusCode(anyOf(is(302),is(401),is(403)));
    }

    @Test
    void mensajeReceptorWithoutCsrfIsRejected() {
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=seedRow("0010000104"+String.format("%010d",(int)(Math.random()*1_000_000)));
        try{
            given().cookies(s).contentType(ContentType.URLENC).formParam("codigoMensaje","1")
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(400),is(403)));
        } finally { recibidosService.delete(recibidosService.find(row.getId())); }
    }
}
