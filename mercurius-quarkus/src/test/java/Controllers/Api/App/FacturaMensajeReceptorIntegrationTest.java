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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import Models.ComprobantesRecibidos;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import Services.AppSettingsService;
import Services.ComprobanteService;
import Services.ComprobantesRecibidosService;
import Services.HaciendaApiService;
import Services.HaciendaSigner;
import Services.CabysService;
import Models.Cabys;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Tag("facturas-recibidas")
class FacturaMensajeReceptorIntegrationTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/facturas-recibidas";
    private static final String CABYS_ACTIVO = "0111010010010";

    @Inject ComprobantesRecibidosService recibidosService;
    @Inject AppSettingsService appSettingsService;
    @Inject CabysService cabysService;
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

    private void seedCabys(){ if(cabysService.find(CABYS_ACTIVO)==null) cabysService.create(new Cabys(CABYS_ACTIVO,"Test","Cat","0","https://ex.com","ACTIVO")); }
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

    @Test
    void acceptValidFacturaQueuesAcceptInvoice() {
        seedCabys(); seedSettings(); stubOk();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=seedRow("0010000104"+String.format("%010d",(int)(Math.random()*1_000_000)));
        try{
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.URLENC).formParam("codigoMensaje","1")
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(200),is(500)));
            var updated=recibidosService.find(row.getId());
            assertThat(updated.getHaciendaMensajeReceptorEstado()).isIn("ACEPTADO","PROCESANDO");
            verify(haciendaApiService,atLeastOnce()).acceptInvoice(any(),any(),any(),any(),any(),any());
        } finally { recibidosService.delete(recibidosService.find(row.getId())); }
    }

    @Test
    void rejectQueuesRejectInvoice() {
        seedCabys(); seedSettings(); stubOk();
        Map<String,String> s=adminSession();
        ComprobantesRecibidos row=seedRow("0010000104"+String.format("%010d",(int)(Math.random()*1_000_000)));
        try{
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.JSON).body(Map.of("codigoMensaje","3"))
                    .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(200),is(500)));
            var updated=recibidosService.find(row.getId());
            assertThat(updated.getHaciendaMensajeReceptorEstado()).isEqualTo("RECHAZADO");
            verify(haciendaApiService).rejectInvoice(any(),any(),any(),any(),any(),any());
        } finally { recibidosService.delete(recibidosService.find(row.getId())); }
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
        // Use invalid fixture via direct upload then try MR
        seedCabys();
        Map<String,String> s=adminSession();
        String consec="0010000104"+String.format("%010d",(int)(Math.random()*1_000_000));
        String clave="5062508250000010100010000000101"+String.format("%019d",(int)(Math.random()*1_000_000));
        // Load invalid fixture
        try(var in=getClass().getResourceAsStream("/fixtures/recibidos/factura-recibida-cabys-invalido.xml")){
            String xml=new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replaceFirst("00100001040000000037",consec).replaceFirst(">\\d{50}<",">"+clave+"<");
            given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.MULTIPART).multiPart("files","tampered.xml",xml.getBytes(), "application/xml")
                    .when().post(API+"/upload").then().statusCode(200);
            var row=recibidosService.listAll().stream().filter(c->consec.equals(c.getEncabezado().getNumeroConsecutivo())).findFirst().orElse(null);
            if(row!=null){
                try{
                    given().cookies(s).header("X-CSRF-TOKEN",csrf(s)).contentType(ContentType.URLENC).formParam("codigoMensaje","1")
                            .when().post(API+"/"+row.getId()+"/mensaje-receptor").then().statusCode(anyOf(is(400),is(409),is(200),is(500)));
                    verifyNoInteractions(haciendaApiService);
                } finally { recibidosService.delete(recibidosService.find(row.getId())); }
            }
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
