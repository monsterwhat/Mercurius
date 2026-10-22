package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * El kit data-table saca los encabezados del modelo ({@code columnas}) y las
 * celdas de la plantilla. Si a la plantilla le falta una celda (o sobra), TODO
 * se desplaza una columna: en la tabla de clientes faltaba la celda "Código",
 * así que "Teléfono" caía sobre la cédula y "Correo" sobre el teléfono.
 *
 * <p>Este test fija el invariante: cada tabla dibuja tantas celdas como
 * encabezados tiene.</p>
 */
@QuarkusTest
@Tag("design-system")
class TablaColumnasTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";

    private static final Pattern TH = Pattern.compile("<th[^>]*>");
    private static final Pattern TD = Pattern.compile("<td[^>]*>");
    private static final Pattern THEAD = Pattern.compile("<thead[^>]*>(.*?)</thead>", Pattern.DOTALL);
    private static final Pattern TBODY = Pattern.compile("<tbody[^>]*>(.*?)</tbody>", Pattern.DOTALL);
    private static final Pattern TR = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL);

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    @DisplayName("cada tabla dibuja una celda por encabezado")
    void lasTablasNoSeDesalinean() {
        List<String> rutas = List.of(
                "/api/app/clientes/table",
                "/api/app/facturas-recibidas/tabla",
                "/api/app/recibos/tabla",
                "/api/app/cabys/table",
                "/api/app/articulos/table",
                "/app/reportes/clientes",
                "/app/reportes/facturas",
                "/app/reportes/recibos/pendientes",
                "/app/reportes/recibos/vencidos",
                "/app/reportes/inventario/resumen",
                "/app/reportes/inventario/precios",
                "/app/reportes/inventario/merma",
                "/app/reportes/inventario/alertas",
                "/app/reportes/articulos/fechas",
                "/app/reportes/articulos/tendencias",
                "/app/reportes/articulos/ventas",
                "/app/reportes/articulos/rendimiento",
                "/app/reportes/margenes",
                "/app/reportes/inventario/pronosticos",
                "/app/inventarios",
                "/app/tributacion/consultas",
                "/app/tributacion/declaracion");

        List<String> problemas = new ArrayList<>();
        for (String ruta : rutas) {
            Response r = given().redirects().follow(false).when().get(BASE + ruta);
            r.then().statusCode(200);
            String body = r.asString();

            // cada tabla: un <thead> y lo que viene despues hasta el siguiente
            Matcher tablas = Pattern.compile(
                    "<thead[^>]*>(.*?)</thead>(.*?)(?=<thead|</table>)", Pattern.DOTALL).matcher(body);
            int numero = 0;
            while (tablas.find()) {
                numero++;
                int encabezados = (int) TH.matcher(tablas.group(1)).results().count();
                if (encabezados == 0) {
                    continue;
                }
                List<Integer> anchos = new ArrayList<>();
                Matcher filas = TR.matcher(tablas.group(2));
                while (filas.find()) {
                    int n = (int) TD.matcher(filas.group(1)).results().count();
                    if (n > 1) {           // la fila de "sin resultados" lleva un solo td
                        anchos.add(n);
                    }
                }
                if (anchos.isEmpty()) {
                    continue;               // tabla sin filas: nada que comparar
                }
                int comun = anchos.stream().max(Integer::compareTo).orElse(-1);
                int iguales = (int) anchos.stream().filter(n -> n == comun).count();
                if (comun != encabezados || iguales != anchos.size()) {
                    problemas.add(ruta + " tabla#" + numero + ": " + encabezados
                            + " encabezados y celdas " + anchos);
                }
            }
        }
        assertTrue(problemas.isEmpty(), "tablas desalineadas: " + problemas);
    }
}
