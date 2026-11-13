package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import Models.EnvioFueraLinea;
import Services.EnvioFueraLineaService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pantalla de la cola de envío (outbox Art. 21 ¶3). No se prueba el envío real
 * a Hacienda: las filas sembradas quedan con {@code proximoIntento} futuro para
 * que el gating del servidor las rechace sin tocar la red.
 */
@QuarkusTest
@Tag("cola-envio")
class ColaEnvioPagesTest extends support.ContextPathIsolation {

    private static final String BASE = "";
    private static final String PAGE = BASE + "/app/tributacion/cola-envio";
    private static final String API = BASE + "/api/app/tributacion/cola-envio";

    @Inject
    EnvioFueraLineaService service;

    @Inject
    EntityManager em;

    @Test
    void paginaMuestraLaColaYLosPendientes() {
        Map<String, String> session = adminSession();
        EnvioFueraLinea fila = seed("PAGINA");
        try {
            assertThat(service.findColaFila(fila.getId())).isNotNull();
            assertThat(service.findByEstado(EnvioFueraLineaService.ESTADO_PENDIENTE, 50))
                    .extracting(EnvioFueraLineaService.ColaFila::id)
                    .contains(fila.getId());
            given().cookies(session)
                    .when().get(PAGE)
                    .then().statusCode(200)
                    .body(containsString("Cola de envío a Hacienda"))
                    .body(containsString("Pendientes"))
                    .body(containsString(fila.getConsecutivo()));
        } finally {
            cleanup(fila.getId());
        }
    }

    @Test
    void fragmentoTableroNoIncluyeElLayout() {
        Map<String, String> session = adminSession();
        EnvioFueraLinea fila = seed("FRAG");
        try {
            given().cookies(session).header("HX-Request", "true")
                    .when().get(PAGE)
                    .then().statusCode(200)
                    .body(containsString("metric-strip"))
                    .body(not(containsString("<html")));
        } finally {
            cleanup(fila.getId());
        }
    }

    @Test
    void reintentarUnaFilaProgramadaNoLlamaHacienda() {
        Map<String, String> session = adminSession();
        EnvioFueraLinea fila = seed("FUTURA");
        try {
            authed(session).header("HX-Request", "true")
                    .when().post(API + "/" + fila.getId() + "/reintentar")
                    .then().statusCode(200)
                    .body(containsString("Programado para"));
        } finally {
            cleanup(fila.getId());
        }
    }

    private static Map<String, String> adminSession() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get("csrftoken");
        if (token == null) {
            token = cookies.get("csrf-token");
        }
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }

    private void cleanup(Long id) {
        if (id == null) {
            return;
        }
        QuarkusTransaction.requiringNew().run(() ->
                em.createQuery("DELETE FROM EnvioFueraLinea e WHERE e.id = :id")
                        .setParameter("id", id)
                        .executeUpdate());
    }

    private EnvioFueraLinea seed(String sufijo) {
        EnvioFueraLinea e = new EnvioFueraLinea();
        e.setTipoDocumento("04");
        e.setClave(String.format("%-50s",
                "T" + UUID.randomUUID().toString().replace("-", "")).replace(' ', '0'));
        e.setConsecutivo("0010000104" + String.format("%010d",
                Math.abs(UUID.randomUUID().getMostSignificantBits()) % 10_000_000_000L));
        e.setSucursal("001");
        e.setTerminal("00001");
        e.setSituacion("3");
        e.setEstado(EnvioFueraLineaService.ESTADO_PENDIENTE);
        e.setIntentos(1);
        e.setOrigen(EnvioFueraLineaService.ORIGEN_OFFLINE_SITUACION_3);
        e.setFechaEmision(LocalDateTime.now().minusHours(2));
        e.setFechaCreacion(LocalDateTime.now());
        e.setProximoIntento(LocalDateTime.now().plusHours(1));
        e.setVencimiento(LocalDateTime.now().plusDays(1));
        e.setUltimoError("timeout de prueba " + sufijo);
        QuarkusTransaction.requiringNew().run(() -> {
            em.persist(e);
            em.flush();
        });
        return e;
    }
}
