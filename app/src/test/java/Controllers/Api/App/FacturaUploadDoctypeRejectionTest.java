package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import support.FacturasReales;

/**
 * An uploaded invoice must never be able to declare a DOCTYPE.
 *
 * <p>Behavioral pin for the XXE fix in
 * {@code FacturasRecibidasResource.prevalidateXml}. The method had two escapes
 * from its {@code disallow-doctype-decl} parse, both keyed on a plain
 * substring match over the untrusted bytes:</p>
 *
 * <ol>
 *   <li>{@code if (xml.contains("MensajeHacienda")) return null;} — a file
 *       containing that token anywhere skipped the hardened parse entirely.</li>
 *   <li>the {@code catch} did
 *       {@code if (xml.contains("NumeroConsecutivo")) return null;} — and since
 *       {@code disallow-doctype-decl} makes a DOCTYPE-bearing document
 *       <em>fail</em> that parse, this branch waved through exactly the
 *       documents the flag exists to reject.</li>
 * </ol>
 *
 * <p>Both payloads below are the real fixture (so they carry a genuine
 * {@code NumeroConsecutivo} and satisfy the second bypass) with a DOCTYPE plus
 * an external entity spliced in. Before the fix each was accepted; now the
 * upload is rejected and no invoice row is created.</p>
 */
@QuarkusTest
@DisplayName("Subida de facturas: un DOCTYPE nunca se acepta")
class FacturaUploadDoctypeRejectionTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String API = BASE + "/api/app/facturas-recibidas";

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

    /** The real fixture with a DOCTYPE that declares an external entity. */
    private String conDoctype() {
        String xml = FacturasReales.conConsecutivoUnico("fe-v44-13", 770_001);
        String dtd = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE FacturaElectronica [\n"
                + "  <!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n"
                + "]>\n";
        // Replace the original XML declaration with one followed by the DOCTYPE.
        int finDeclaracion = xml.indexOf("?>");
        assertThat(finDeclaracion).as("la fixture debe declarar XML").isPositive();
        return dtd + xml.substring(finDeclaracion + 2);
    }

    /**
     * Uploads one file and returns the envelope.
     *
     * <p>Assertions read {@code data.resultados[0]}, not the HTTP status: the
     * upload is a batch endpoint that answers 200 and reports each file's fate
     * individually (the same contract {@code FacturasRecibidasResourceTest}
     * pins), so a per-file rejection is a 200 carrying {@code exito=false}.</p>
     */
    private io.restassured.response.Response subir(Map<String, String> s, String nombre, String xml) {
        return given().cookies(s)
                .header("X-CSRF-TOKEN", csrf(s))
                .contentType(ContentType.MULTIPART)
                .multiPart("files", nombre, xml.getBytes(StandardCharsets.UTF_8), "application/xml")
                .when().post(API + "/upload");
    }

    @Test
    @DisplayName("una factura con DOCTYPE y entidad externa se rechaza")
    void doctypeConEntidadExternaSeRechaza() {
        Map<String, String> s = adminSession();

        subir(s, "xxe.xml", conDoctype())
                .then()
                .statusCode(200)
                .body("data.procesados", equalTo(0))
                .body("data.fallidos", equalTo(1))
                .body("data.resultados[0].exito", equalTo(false))
                .body("data.resultados[0].mensaje", containsString("DOCTYPE"));
    }

    @Test
    @DisplayName("el DOCTYPE se rechaza aunque el documento declare MensajeHacienda")
    void doctypeSeRechazaAunConMensajeHacienda() {
        // Segundo bypass: el token MensajeHacienda en el cuerpo abria la puerta
        // de la factura real sin pasar por el parseo endurecido.
        Map<String, String> s = adminSession();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE MensajeHacienda [\n"
                + "  <!ENTITY xxe SYSTEM \"file:///etc/passwd\">\n"
                + "]>\n"
                + "<MensajeHacienda>&xxe;</MensajeHacienda>";

        subir(s, "xxe-mr.xml", xml)
                .then()
                .statusCode(200)
                .body("data.fallidos", equalTo(1))
                .body("data.resultados[0].exito", equalTo(false))
                .body("data.resultados[0].mensaje", containsString("DOCTYPE"));
    }

    @Test
    @DisplayName("una factura real sin DOCTYPE se sigue aceptando (sin regresion)")
    void facturaRealSinDoctypeSeAcepta() {
        // Control: el endurecimiento no debe rechazar documentos legitimos.
        Map<String, String> s = adminSession();
        String xml = FacturasReales.conConsecutivoUnico("fe-v44-13", 770_002);
        assertThat(xml).as("control: la fixture real no lleva DOCTYPE")
                .doesNotContain("<!DOCTYPE");

        subir(s, "control.xml", xml)
                .then()
                .body("data.resultados[0].exito", equalTo(true));
    }

    @Test
    @DisplayName("el documento malicioso no entra por la importacion leniente")
    void elDocumentoMaliciosoNoSeImporta() {
        // Un DOCTYPE debe rechazarse de plano, NO tomar la via de
        // importarProductosDeFacturaRechazada, que existe para documentos
        // invalidos contra el esquema pero estructuralmente sanos. Si el mensaje
        // habla de productos importados, la puerta se salto.
        Map<String, String> s = adminSession();

        String mensaje = subir(s, "xxe-3.xml", conDoctype())
                .then().statusCode(200)
                .extract().path("data.resultados[0].mensaje");

        assertThat(mensaje)
                .as("un DOCTYPE no debe entrar por la via de importacion leniente")
                .isNotNull()
                .contains("DOCTYPE")
                .doesNotContain("productos");
    }
}
