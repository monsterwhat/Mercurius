package Documentos;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JAXB mirrors must copy the domain completely — field by field, per document.
 *
 * <p>Why this test exists: {@code Models.Jaxb} holds seven near-identical
 * packages that MUST stay separate (namespaces are package-scoped; see
 * {@code Models/Jaxb/package-info.java}), and every class except the seven
 * roots carries a hand-written {@code public X(Models.…​.X src)} copy
 * constructor. That list rots silently: adding a field to the domain without
 * extending the mirror's constructor drops data from every signed document
 * with no compiler error. These round trips build a domain object with every
 * XML-mapped field set to a sentinel, copy it, and assert nothing was lost.</p>
 *
 * <p>The structural sweep below it enforces the pattern itself: any new JAXB
 * class must declare the domain copy constructor, so the next mirror cannot be
 * added without a copy path at all.</p>
 */
@DisplayName("Jaxb: la copia del dominio es completa por documento")
class JaxbCopiaCompletaTest {

    private static final String[] PAQUETES =
            {"FE", "FEC", "FEE", "NC", "ND", "REP", "TE"};

    /** Document roots take ComprobantesEmitidos, not the domain — excluded. */
    private static boolean esRaiz(String nombre) {
        return nombre.endsWith("Documento");
    }

    /** All concrete JAXB classes in a document package, discovered from source. */
    private static List<String> clasesDe(String paquete) throws Exception {
        Path dir = Paths.get("src/main/java/Models/Jaxb", paquete);
        assertThat(Files.isDirectory(dir))
                .as("el paquete Models.Jaxb.%s debe existir en el arbol de fuentes", paquete)
                .isTrue();
        List<String> nombres = new ArrayList<>();
        try (var flujo = Files.list(dir)) {
            flujo.filter(p -> p.toString().endsWith(".java"))
                    .map(p -> p.getFileName().toString().replace(".java", ""))
                    .filter(n -> !"package-info".equals(n))
                    .sorted()
                    .forEach(nombres::add);
        }
        return nombres;
    }

    @Test
    @DisplayName("cada clase espejo declara su constructor de copia del dominio")
    void cadaEspejoTieneConstructorDeCopia() throws Exception {
        List<String> sinCtor = new ArrayList<>();
        for (String paquete : PAQUETES) {
            for (String nombre : clasesDe(paquete)) {
                if (esRaiz(nombre)) {
                    continue;
                }
                Class<?> espejo = Class.forName("Models.Jaxb." + paquete + "." + nombre);
                boolean tiene = false;
                for (Constructor<?> c : espejo.getConstructors()) {
                    if (c.getParameterCount() == 1
                            && c.getParameterTypes()[0].getName().startsWith("Models.")
                            && !c.getParameterTypes()[0].getName().startsWith("Models.Jaxb")) {
                        tiene = true;
                        break;
                    }
                }
                if (!tiene) {
                    sinCtor.add(paquete + "." + nombre);
                }
            }
        }
        assertThat(sinCtor)
                .as("toda clase espejo (salvo las 7 raices) debe copiar desde el dominio")
                .isEmpty();
    }

    @Test
    @DisplayName("el patron cubre las siete familias sin huecos")
    void lasSieteFamiliasTienenEspejos() throws Exception {
        for (String paquete : PAQUETES) {
            List<String> clases = clasesDe(paquete);
            assertThat(clases).as("Models.Jaxb.%s no debe quedar vacio", paquete).hasSizeGreaterThan(20);
            assertThat(clases).as("Models.Jaxb.%s debe traer Encabezado", paquete).contains("Encabezado");
        }
    }

    // ── round trips del nucleo fiscal (FE como referencia) ─────────────────

    @Test
    @DisplayName("Emisor copia todos sus campos simples y anidados")
    void emisorCopiaTodo() {
        var id = new Models.Encabezado.IdentificacionEmisor();
        id.setTipo("01");
        id.setNumero("3100100008");

        var ubi = new Models.Encabezado.Ubicacion();
        ubi.setProvincia("01");
        ubi.setCanton("01");
        ubi.setDistrito("01");

        var tel = new Models.Encabezado.Telefono();
        tel.setCodigoPais("506");
        tel.setNumeroTelefono("88881234");

        var correo = new Models.Encabezado.CorreoElectronicoEmisor();
        correo.setCorreo("emisor@ejemplo.cr");

        var src = new Models.Encabezado.Emisor();
        src.setNombre("Emisor de Prueba");
        src.setIdentificacion(id);
        src.setRegistrofiscal8707("R-1");
        src.setNombreComercial("Comercial");
        src.setUbicacion(ubi);
        src.setTelefono(tel);
        src.setCorreosElectronicos(List.of(correo));

        var copiado = new Models.Jaxb.FE.Emisor(src);

        assertThat(copiado.getNombre()).isEqualTo("Emisor de Prueba");
        assertThat(copiado.getRegistrofiscal8707()).isEqualTo("R-1");
        assertThat(copiado.getNombreComercial()).isEqualTo("Comercial");
        assertThat(copiado.getIdentificacion().getNumero()).isEqualTo("3100100008");
        assertThat(copiado.getUbicacion().getDistrito()).isEqualTo("01");
        assertThat(copiado.getTelefono().getNumeroTelefono()).isEqualTo("88881234");
        assertThat(copiado.getCorreosElectronicos())
                .extracting(Models.Jaxb.FE.CorreoElectronicoEmisor::getCorreo)
                .containsExactly("emisor@ejemplo.cr");
    }

    @Test
    @DisplayName("LineaDetalle e Impuesto copian montos e impuestos")
    void lineaDetalleCopiaMontos() {
        var imp = new Models.Detalles.Impuesto();
        imp.setCodigo("01");
        imp.setTarifa(new BigDecimal("13"));
        imp.setMonto(new BigDecimal("130"));

        var src = new Models.Detalles.LineaDetalle();
        src.setNumeroLinea(1);
        src.setDetalle("Leche entera 1 L");
        src.setCantidad(new BigDecimal("2"));
        src.setPrecioUnitario(new BigDecimal("1000"));
        src.setMontoTotal(new BigDecimal("2000"));
        src.setImpuestos(java.util.List.of(imp));

        var copiado = new Models.Jaxb.FE.LineaDetalle(src);

        assertThat(copiado.getNumeroLinea()).isEqualTo(1);
        assertThat(copiado.getDetalle()).isEqualTo("Leche entera 1 L");
        assertThat(copiado.getCantidad()).isEqualByComparingTo("2");
        assertThat(copiado.getMontoTotal()).isEqualByComparingTo("2000");
        assertThat(copiado.getImpuestos()).hasSize(1);
        assertThat(copiado.getImpuestos().get(0).getTarifa()).isEqualByComparingTo("13");
        assertThat(copiado.getImpuestos().get(0).getMonto()).isEqualByComparingTo("130");
    }

    @Test
    @DisplayName("ResumenFactura copia los totales")
    void resumenCopiaTotales() {
        var moneda = new Models.Resumen.CodigoTipoMoneda();
        moneda.setCodigoMoneda("CRC");

        var src = new Models.Resumen.ResumenFactura();
        src.setTotalVentaNeta(new BigDecimal("1000"));
        src.setTotalImpuesto(new BigDecimal("130"));
        src.setTotalComprobante(new BigDecimal("1130"));
        src.setCodigoMoneda(moneda);

        var copiado = new Models.Jaxb.FE.ResumenFactura(src);

        assertThat(copiado.getTotalVentaNeta()).isEqualByComparingTo("1000");
        assertThat(copiado.getTotalImpuesto()).isEqualByComparingTo("130");
        assertThat(copiado.getTotalComprobante()).isEqualByComparingTo("1130");
        assertThat(copiado.getCodigoMoneda().getCodigoMoneda()).isEqualTo("CRC");
    }

    @Test
    @DisplayName("Encabezado copia la cabecera sin los campos solo-JPA")
    void encabezadoCopiaCabecera() {
        var emisor = new Models.Encabezado.Emisor();
        emisor.setNombre("E");
        var receptor = new Models.Encabezado.Receptor();
        receptor.setNombre("R");

        var src = new Models.Encabezado.Encabezado();
        src.setClave("506" + "0".repeat(47));
        src.setNumeroConsecutivo("00100001040000000001");
        src.setCondicionVenta("01");
        src.setEmisor(emisor);
        src.setReceptor(receptor);

        var copiado = new Models.Jaxb.FE.Encabezado(src);

        assertThat(copiado.getClave()).isEqualTo(src.getClave());
        assertThat(copiado.getNumeroConsecutivo()).isEqualTo("00100001040000000001");
        assertThat(copiado.getCondicionVenta()).isEqualTo("01");
        assertThat(copiado.getEmisor().getNombre()).isEqualTo("E");
        assertThat(copiado.getReceptor().getNombre()).isEqualTo("R");
    }

    // ── divergencias reales entre documentos ──────────────────────────────

    @Test
    @DisplayName("FEE.Exoneracion mapea sus nombres propios de campo")
    void feeExoneracionMapeaNombres() {
        var src = new Models.Detalles.Exoneracion();
        src.setTipoDocumentoEX1("01");
        src.setFechaEmisionEX(java.time.LocalDateTime.of(2026, 1, 2, 3, 4));
        src.setTarifaExonerada(new BigDecimal("13"));

        var copiado = new Models.Jaxb.FEE.Exoneracion(src);

        assertThat(copiado.getTipoDocumento()).isEqualTo("01");
        assertThat(copiado.getPorcentajeExoneracion()).isEqualByComparingTo("13");
        assertThat(copiado.getFechaEmision()).isEqualTo(src.getFechaEmisionEX());
    }

    @Test
    @DisplayName("REP.Emisor es minimo por su XSD y REP.Impuesto no exonera")
    void repDivergenciasDocumentadas() {
        var src = new Models.Encabezado.Emisor();
        src.setNombre("E");
        src.setNombreComercial("Comercial");
        src.setRegistrofiscal8707("R-1");

        var copiado = new Models.Jaxb.REP.Emisor(src);

        assertThat(copiado.getNombre()).isEqualTo("E");
        assertThat(copiado.getNombreComercial())
                .as("REP anula el nombre comercial por su XSD")
                .isNull();
        assertThat(copiado.getRegistrofiscal8707())
                .as("REP anula el registro fiscal por su XSD")
                .isNull();

        assertThat(Modifier.isPublic(Models.Jaxb.REP.Impuesto.class.getModifiers())).isTrue();
        assertThatCode(() -> Class.forName("Models.Jaxb.REP.Exoneracion"))
                .as("REP no tiene Exoneracion en su XSD")
                .isInstanceOf(ClassNotFoundException.class);
    }

    private static org.assertj.core.api.AbstractThrowableAssert<?, ? extends Throwable> assertThatCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        return org.assertj.core.api.Assertions.assertThatCode(callable);
    }
}
