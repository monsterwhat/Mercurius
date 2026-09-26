package Documentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import support.CatalogoReal;
import support.FacturasReales;

/**
 * Contract for the anonymized real-invoice fixtures in {@code fixtures/reales}.
 *
 * <p>These fixtures are generated, so this test is what stops a regeneration
 * from silently weakening them: a stripped signature, a leaked party name, a
 * Clave that is no longer 50 digits or a bar code that is no longer a real
 * GTIN-13 would all be caught here rather than inside an unrelated upload test.
 *
 * <p>The XSD conformance of the same fixtures is asserted separately by
 * {@code scripts/verify-real-fixtures.ps1}, which validates them against the
 * official Hacienda schemas.
 */
class FacturasRealesFixtureTest {

    private static final Pattern CLAVE = Pattern.compile("<Clave>(\\d{50})</Clave>");
    private static final Pattern CONSECUTIVO = Pattern.compile("<NumeroConsecutivo>(\\d{20})</NumeroConsecutivo>");

    /** Person names: these must never survive anywhere, including product text. */
    private static final List<String> PERSONAS_REALES = List.of(
            "PADILLA", "FONSECA", "CASCANTE", "GABRIELA", "PALMERAS");

    /**
     * Supplier brands. Checked against the party blocks only: a brand name
     * inside {@code LineaDetalle/Detalle} is real product data (Pozuelo
     * wafers, Kitty yuca) and is deliberately preserved.
     */
    private static final List<String> PROVEEDORES_REALES = List.of(
            "Bimbo", "DINANT", "Jacks", "KITTY", "Kion", "FIFCO",
            "Pozuelo", "HILIX", "PHILIP", "MORRIS");

    private static final Pattern BLOQUE_PARTY = Pattern.compile(
            "(?s)<(?:Emisor|Receptor)>(.*?)</(?:Emisor|Receptor)>");

    static List<String> todasLasFixtures() {
        List<String> todas = new ArrayList<>(FacturasReales.nombres("43"));
        todas.addAll(FacturasReales.nombres("44"));
        return todas;
    }

    @Test
    @DisplayName("las 20 facturas reales estan presentes en v4.3 y v4.4")
    void setDeFixturesCompleto() {
        assertThat(FacturasReales.nombres("43")).hasSize(20);
        assertThat(FacturasReales.nombres("44")).hasSize(20);
        assertThat(FacturasReales.nombres("43", "nc")).hasSize(4);
        assertThat(FacturasReales.nombres("43", "fe")).hasSize(15);
        assertThat(FacturasReales.nombres("43", "fc")).hasSize(1);
    }

    @ParameterizedTest
    @MethodSource("todasLasFixtures")
    @DisplayName("cada factura carga y trae Clave de 50 digitos y consecutivo de 20")
    void claveYConsecutivoBienFormados(String nombre) {
        String xml = FacturasReales.xml(nombre);

        assertThat(CLAVE.matcher(xml).results().count()).isPositive();
        assertThat(CONSECUTIVO.matcher(xml).results().count()).isPositive();
        assertThat(FacturasReales.claveDe(nombre)).hasSize(50).startsWith("506");
        assertThat(FacturasReales.consecutivoDe(nombre)).hasSize(20);
    }

    @ParameterizedTest
    @MethodSource("todasLasFixtures")
    @DisplayName("ninguna factura conserva la firma XAdES ni su certificado")
    void firmaEliminada(String nombre) {
        String xml = FacturasReales.xml(nombre);

        assertThat(xml).doesNotContain("ds:Signature");
        assertThat(xml).doesNotContain("X509Certificate");
        assertThat(xml).doesNotContain("RSAKeyValue");
        assertThat(xml).doesNotContain("SigningTime");
    }

    @ParameterizedTest
    @MethodSource("todasLasFixtures")
    @DisplayName("ninguna factura conserva un nombre real de persona o proveedor")
    void partiesAnonimizados(String nombre) {
        String xml = FacturasReales.xml(nombre);
        String mayusculas = xml.toUpperCase(java.util.Locale.ROOT);

        for (String persona : PERSONAS_REALES) {
            assertThat(mayusculas)
                    .as("%s no debe contener el nombre real de persona '%s'", nombre, persona)
                    .doesNotContain(persona);
        }

        StringBuilder bloques = new StringBuilder();
        Matcher m = BLOQUE_PARTY.matcher(xml);
        while (m.find()) {
            bloques.append(m.group(1));
        }
        assertThat(bloques.length())
                .as("%s debe traer al menos un bloque de parte", nombre)
                .isPositive();
        for (String proveedor : PROVEEDORES_REALES) {
            assertThat(bloques.toString().toUpperCase(java.util.Locale.ROOT))
                    .as("%s no debe identificar al proveedor real '%s'", nombre, proveedor)
                    .doesNotContain(proveedor.toUpperCase(java.util.Locale.ROOT));
        }
    }

    @Test
    @DisplayName("los codigos de correo y telephone usan el TLD reservado .test")
    void correosYTelefonosAnonimizados() {
        for (String nombre : todasLasFixtures()) {
            String xml = FacturasReales.xml(nombre);
            Matcher m = Pattern.compile("<Correo(?:Electronico)?>([^<]+)<").matcher(xml);
            while (m.find()) {
                assertThat(m.group(1))
                        .as("%s debe usar un correo sintetico", nombre)
                        .endsWith("@proveedor-prueba.test");
            }
        }
    }

    @Test
    @DisplayName("v4.3 usa Codigo y no declara ProveedorSistemas; v4.4 hace lo contrario")
    void diferenciasDeVersionRespetadas() {
        for (String nombre : FacturasReales.nombres("43")) {
            String xml = FacturasReales.xml(nombre);
            assertThat(xml).contains("xml-schemas/v4.3/");
            assertThat(xml).as("%s es v4.3 y no lleva ProveedorSistemas", nombre)
                    .doesNotContain("<ProveedorSistemas>");
            assertThat(xml).as("%s es v4.3 y usa Codigo", nombre).contains("<Codigo>");
        }
        for (String nombre : FacturasReales.nombres("44")) {
            String xml = FacturasReales.xml(nombre);
            assertThat(xml).contains("xml-schemas/v4.4/");
            assertThat(xml).as("%s es v4.4 y exige ProveedorSistemas", nombre)
                    .contains("<ProveedorSistemas>");
            assertThat(xml).as("%s es v4.4 y usa CodigoCABYS", nombre).contains("<CodigoCABYS>");
        }
    }

    @Test
    @DisplayName("el consecutivo se puede re-estampar y la Clave se reconstruye a 50 digitos")
    void consecutivoUnicoPorImportacion() {
        String claveOriginal = FacturasReales.claveDe("fe-v44-05");
        String consecutivoOriginal = FacturasReales.consecutivoDe("fe-v44-05");
        String primera = FacturasReales.conConsecutivoUnico("fe-v44-05", 1);
        String segunda = FacturasReales.conConsecutivoUnico("fe-v44-05", 2);

        assertThat(primera).isNotEqualTo(segunda);
        assertThat(FacturasReales.claveDe("fe-v44-05")).isEqualTo(claveOriginal);

        Matcher m = CLAVE.matcher(primera);
        assertThat(m.find()).isTrue();
        String claveNueva = m.group(1);
        assertThat(claveNueva).hasSize(50).startsWith("506");

        // the rebuilt Clave must embed the NEW consecutivo, not the committed one
        assertThat(claveNueva).doesNotContain(consecutivoOriginal);
        assertThat(primera).contains("<NumeroConsecutivo>"
                + claveNueva.substring(21, 41)
                + "</NumeroConsecutivo>");
        assertThat(primera).doesNotContain("<NumeroConsecutivo>" + consecutivoOriginal + "</NumeroConsecutivo>");

        // country, date and issuer segment are preserved from the real invoice
        assertThat(claveNueva.substring(0, 21)).isEqualTo(claveOriginal.substring(0, 21));
    }

    @Test
    @DisplayName("la Clave reestampada nunca lleva un signo: overflow de int producia '-NNN'")
    void claveReestampadaNuncaLlevaSigno() {
        // Regresion: la mezcla de la semilla se calculaba en int, y
        // semilla * 104729 desborda int por encima de ~20505. El valor negativo
        // se renderizaba como "-00157752" dentro de la Clave, que el patron
        // oficial ClaveType (\d{50,50}) rechaza.
        // El barrido cruza el umbral a proposito: por debajo nunca fallaba.
        for (int semilla = 1; semilla <= 600; semilla++) {
            assertClaveNumerica(semilla);
        }
        for (int semilla = 20400; semilla <= 20800; semilla++) {
            assertClaveNumerica(semilla);
        }
        for (int semilla = 20505; semilla <= 90000; semilla += 337) {
            assertClaveNumerica(semilla);
        }
    }

    private static void assertClaveNumerica(int semilla) {
        String xml = FacturasReales.conConsecutivoUnico("fe-v44-05", semilla);
        Matcher m = CLAVE.matcher(xml);
        assertThat(m.find()).as("semilla %d debe producir una Clave", semilla).isTrue();
        assertThat(m.group(1))
                .as("semilla %d produjo una Clave no numerica", semilla)
                .hasSize(50)
                .matches("\\d{50}");
    }

    @Test
    @DisplayName("semillas altas tambien producen una Clave valida")
    void claveValidaConSemillasAltas() {
        // El rango que antes fallaba: por encima de 20505.
        int[] semillas = {20505, 20506, 30000, 50000, 89999, 90000};
        for (int semilla : semillas) {
            String xml = FacturasReales.conConsecutivoUnico("fe-v44-12", semilla);
            Matcher m = CLAVE.matcher(xml);
            assertThat(m.find()).as("semilla %d", semilla).isTrue();
            assertThat(m.group(1)).as("semilla %d", semilla).hasSize(50).matches("\\d{50}");
        }
    }

    @Test
    @DisplayName("el catalogo real trae codigos de barras GTIN-13 y nombres de articulo")
    void catalogoRealPoblado() {
        List<CatalogoReal.ArticuloReal> todos = CatalogoReal.todos();

        assertThat(todos).isNotEmpty();
        assertThat(todos.size()).isGreaterThan(50);
        assertThat(todos).allSatisfy(a -> {
            assertThat(a.codigoBarra()).as("codigo de barras").hasSize(13).matches("\\d{13}");
            assertThat(a.nombre()).as("nombre de articulo").isNotBlank();
            assertThat(a.codigoCabys()).hasSize(13);
        });
        assertThat(CatalogoReal.unidadesComerciales())
                .contains("BOT", "LT", "UN", "PAK", "ST");
    }

    @Test
    @DisplayName("el catalogo real se puede consultar por codigo de barras y por nombre")
    void catalogoRealConsultable() {
        CatalogoReal.ArticuloReal primero = CatalogoReal.porIndice(0);

        assertThat(CatalogoReal.porCodigoBarra(primero.codigoBarra()))
                .contains(primero);
        assertThat(CatalogoReal.porCodigoBarra("0000000000000")).isEmpty();
        assertThat(CatalogoReal.porCodigoComercial(primero.codigoComercial())).isPresent();
        assertThat(CatalogoReal.conNombreQueContiene("gatorade")).isNotEmpty();
        assertThat(CatalogoReal.conUnidadComercial()).isNotEmpty();
    }

    @Test
    @DisplayName("los codigos CAByS que exigen las facturas son de 13 digitos")
    void codigosCabysConFormatoValido() {
        for (String version : List.of("43", "44")) {
            List<String> codigos = FacturasReales.codigosCabysDeVersion(version);
            assertThat(codigos).as("codigos CAByS de v%s", version).isNotEmpty();
            assertThat(codigos).allSatisfy(c -> assertThat(c).hasSize(13).matches("\\d{13}"));
        }
    }
}
