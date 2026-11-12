package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * Importador de respaldos XML: simulación sin escritura, alta real como venta
 * y deduplicación por clave en una segunda carga.
 */
@QuarkusTest
class ImportacionFacturasResourceTest extends AppBase {

    private static final String BASE = "/api/app/importacion-facturas";
    private static final Path FIXTURE = Path.of("src/test/resources/fixtures/reales/v4.3/fe-v43-01.xml");

    @Inject
    EntityManager em;

    private Map<String, String> session() {
        Response mint = given().when().get("/api/app/email-templates/table");
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    private byte[] zip() throws Exception {
        byte[] xml = Files.readAllBytes(FIXTURE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("DOCUMENTOS_ELECTRONICOS/2024/03/01/fe.xml"));
            zos.write(xml);
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    private Response upload(Map<String, String> cookies, byte[] zip, String simular) {
        return given().cookies(cookies)
                .header("X-CSRF-TOKEN", cookies.get("csrf-token"))
                .multiPart("archivo", "facturas.zip", zip, "application/zip")
                .formParam("usuario", "importado")
                .formParam("simular", simular)
                .when().post(BASE);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void simulaImportaYOmiteDuplicados() throws Exception {
        Map<String, String> cookies = session();
        byte[] zip = zip();

        Response simulada = upload(cookies, zip, "true")
                .then().statusCode(200)
                .extract().response();
        assertThat(simulada.jsonPath().getBoolean("data.simulacion")).isTrue();
        assertThat(simulada.jsonPath().getInt("data.nuevas")).isEqualTo(1);
        assertThat(simulada.jsonPath().getInt("data.importadas")).isZero();

        Response real = upload(cookies, zip, "false")
                .then().statusCode(200)
                .extract().response();
        assertThat(real.jsonPath().getInt("data.importadas")).isEqualTo(1);
        assertThat(real.jsonPath().getInt("data.errores")).isZero();

        Response repetida = upload(cookies, zip, "false")
                .then().statusCode(200)
                .extract().response();
        assertThat(repetida.jsonPath().getInt("data.importadas")).isZero();
        assertThat(repetida.jsonPath().getInt("data.omitidas")).isEqualTo(1);

        long lineas = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                em.createQuery("SELECT COUNT(ld) FROM ComprobantesEmitidos c "
                        + "JOIN c.detalles d JOIN d.lineasDetalle ld", Long.class)
                        .getSingleResult());
        assertThat(lineas).as("las lineas importadas deben persistir").isPositive();

        // La venta importada aparece en el reporte SIN elegir cajero (todas).
        String ventas = given().cookies(cookies)
                .queryParam("desde", "2024-01-01")
                .queryParam("hasta", "2024-12-31")
                .when().get("/app/reportes/articulos/ventas")
                .then().statusCode(200)
                .extract().asString();
        assertThat(ventas).doesNotContain("Sin ventas");
        assertThat(ventas).contains("importado");

        // Ruta de limpieza de compras importadas (idempotente, sin FEC en el ZIP de prueba).
        Response limpia = given().cookies(cookies)
                .header("X-CSRF-TOKEN", cookies.get("csrf-token"))
                .when().post(BASE + "/limpiar-compras?usuario=importado")
                .then().statusCode(200)
                .extract().response();
        assertThat(limpia.jsonPath().getInt("data.eliminadas")).isZero();
    }
}
