package support;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Product catalogue harvested from the real invoice fixtures.
 *
 * <p>Every entry is a real line item: a real GTIN-13 barcode, the real article
 * description as the supplier wrote it, and the real commercial unit. Tests that
 * need "an articulo" should take one from here rather than inventing
 * {@code Articulo de prueba T37}, because real barcodes exercise the EAN/GTIN
 * paths and real descriptions exercise the length and encoding paths.
 *
 * <p>The catalogue is read from the fixtures at class-initialisation time, so
 * the fixtures stay the single source of truth and the two cannot drift.
 */
public final class CatalogoReal {

    /**
     * One real line item.
     *
     * @param codigoBarra          the 13-digit GTIN, as it appears in the invoice
     * @param nombre                the real article description
     * @param unidadMedidaComercial the supplier's own unit, e.g. {@code BOT}, {@code LT}, {@code PAK}
     * @param codigoCabys           the CAByS code carried by the line
     * @param codigoComercial       the supplier's or buyer's own item code
     * @param tipoCodigoComercial   which side assigned it: 01 buyer, 03 industry, 04 internal
     */
    public record ArticuloReal(String codigoBarra,
                               String nombre,
                               String unidadMedidaComercial,
                               String codigoCabys,
                               String codigoComercial,
                               String tipoCodigoComercial) {

        /** True when the supplier billed this line with a commercial unit. */
        public boolean tieneUnidadComercial() {
            return unidadMedidaComercial != null && !unidadMedidaComercial.isBlank();
        }

        /** Matches on the description, case-insensitively. */
        public boolean nombreContiene(String fragmento) {
            return nombre != null
                    && nombre.toLowerCase(Locale.ROOT).contains(fragmento.toLowerCase(Locale.ROOT));
        }

        /**
         * Identity of the product within the catalogue: the supplier's own item
         * code when present, otherwise the CAByS. The CAByS alone is a tariff
         * classification shared by many products, so it is not unique.
         */
        public String claveCatalogo() {
            if (codigoComercial != null && !codigoComercial.isBlank()) {
                return (tipoCodigoComercial == null ? "" : tipoCodigoComercial) + "/" + codigoComercial;
            }
            return codigoBarra;
        }
    }

    private static final Map<String, ArticuloReal> POR_CODIGO = new LinkedHashMap<>();

    /**
     * Hands out a different barcode-owning product on each call, so tests that
     * need several articles in one boot get distinct barcodes without
     * hard-coding indexes. The test database is drop-and-create per boot, so a
     * cursor that restarts with the JVM is enough to keep barcodes unique.
     */
    private static final java.util.concurrent.atomic.AtomicInteger CURSOR =
            new java.util.concurrent.atomic.AtomicInteger();

    static {
        // The v4.4 set is the canonical one for this project; the v4.3 set is
        // the same catalogue under the older field name and is only a fallback.
        cargar("44");
        cargar("43");
    }

    private CatalogoReal() {
    }

    /** The whole catalogue in first-seen order, one entry per real product. */
    public static List<ArticuloReal> todos() {
        return List.copyOf(POR_CODIGO.values());
    }

    /** The CAByS/GTIN of a real line item, as it appears in the invoice. */
    public static Optional<ArticuloReal> porCodigoBarra(String codigoBarra) {
        return POR_CODIGO.values().stream().filter(a -> a.codigoBarra().equals(codigoBarra)).findFirst();
    }

    /** A real product by the supplier's own item code. */
    public static Optional<ArticuloReal> porCodigoComercial(String codigoComercial) {
        return POR_CODIGO.values().stream()
                .filter(a -> codigoComercial.equals(a.codigoComercial()))
                .findFirst();
    }

    /** Every line item whose description contains the fragment. */
    public static List<ArticuloReal> conNombreQueContiene(String fragmento) {
        return POR_CODIGO.values().stream().filter(a -> a.nombreContiene(fragmento)).toList();
    }

    /** Line items billed with a commercial unit, e.g. {@code BOT} or {@code PAK}. */
    public static List<ArticuloReal> conUnidadComercial() {
        return POR_CODIGO.values().stream().filter(ArticuloReal::tieneUnidadComercial).toList();
    }

    /**
     * A stable pseudo-random pick, so a test that only needs "some articulo"
     * does not always get the same first row.
     */
    public static ArticuloReal porIndice(int indice) {
        List<ArticuloReal> todos = todos();
        if (todos.isEmpty()) {
            throw new IllegalStateException("catalogue is empty: no real line items were parsed");
        }
        return todos.get(Math.floorMod(indice, todos.size()));
    }

    /**
     * The distinct GTIN-13 codes carried by the real line items, each paired
     * with the real product that owns it.
     *
     * <p>Note this is much smaller than {@link #todos()}: on a supplier invoice
     * the 13-digit {@code Codigo}/{@code CodigoCABYS} is the CAByS tariff
     * classification, and one CAByS covers many products (3213605000000 covers
     * every OCB cigarette-paper variant in the set). Use this when a test needs
     * a barcode that no other article already holds, because the API rejects a
     * repeated {@code codigoBarra} with 409 DUPLICATE_BARCODE.
     *
     * <p>The pairing is by first-seen order, so it is stable: every distinct
     * barcode maps to exactly one owning product. That is what lets
     * {@link #siguienteProducto()} hand out a description together with the
     * barcode that really belongs to it, instead of two unrelated products.
     */
    public static List<ArticuloReal> productosPorBarra() {
        List<ArticuloReal> productos = new ArrayList<>();
        List<String> barrasVistas = new ArrayList<>();
        for (ArticuloReal a : todos()) {
            if (!barrasVistas.contains(a.codigoBarra())) {
                barrasVistas.add(a.codigoBarra());
                productos.add(a);
            }
        }
        return List.copyOf(productos);
    }

    /** The bare distinct-GTIN list; see {@link #productosPorBarra()}. */
    public static List<String> barrasDistintas() {
        return productosPorBarra().stream().map(ArticuloReal::codigoBarra).toList();
    }

    /**
     * The next real product whose GTIN no caller has taken yet, cycling when
     * the distinct-barcode set is exhausted.
     *
     * <p>Prefer this over {@link #porIndice(int)} whenever a test fills both
     * the {@code nombre} and the {@code codigoBarra} of a new article: the two
     * fields must describe the SAME product, and only this cursor keeps them
     * together while still handing out a barcode no other article holds. There
     * is deliberately no accessor that hands out a bare barcode.
     */
    public static ArticuloReal siguienteProducto() {
        List<ArticuloReal> productos = productosPorBarra();
        if (productos.isEmpty()) {
            throw new IllegalStateException("no distinct barcodes in the real fixtures");
        }
        return productos.get(Math.floorMod(CURSOR.getAndIncrement(), productos.size()));
    }

    private static void cargar(String version) {
        for (String nombre : FacturasReales.nombres(version)) {
            Document doc = parsear(FacturasReales.xml(nombre));
            NodeList lineas = doc.getElementsByTagName("LineaDetalle");
            for (int i = 0; i < lineas.getLength(); i++) {
                Element linea = (Element) lineas.item(i);
                String codigo = texto(linea, "CodigoCABYS");
                if (codigo == null) {
                    codigo = texto(linea, "Codigo");
                }
                if (codigo == null || codigo.isBlank()) {
                    continue;
                }
                String nombreArticulo = texto(linea, "Detalle");
                if (nombreArticulo == null || nombreArticulo.isBlank()) {
                    continue;
                }
                String[] comercial = codigoComercial(linea);
                ArticuloReal art = new ArticuloReal(
                        codigo,
                        nombreArticulo,
                        texto(linea, "UnidadMedidaComercial"),
                        codigo,
                        comercial[0],
                        comercial[1]);
                // Identity is the supplier's own item code, not the CAByS: a
                // single CAByS covers many distinct products (2431000000000
                // covers Imperial 710ML, 473ML and the 350ML 6-pack), so
                // keying by CAByS would collapse them into one entry.
                String clave = art.claveCatalogo();
                POR_CODIGO.putIfAbsent(clave, art);
            }
        }
    }

    private static String[] codigoComercial(Element linea) {
        NodeList lista = linea.getElementsByTagName("CodigoComercial");
        if (lista.getLength() == 0) {
            return new String[]{null, null};
        }
        Element cc = (Element) lista.item(0);
        return new String[]{texto(cc, "Codigo"), texto(cc, "Tipo")};
    }

    private static Document parsear(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            try (InputStream in = new java.io.ByteArrayInputStream(
                    xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return builder.parse(in);
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not parse a real invoice fixture", e);
        }
    }

    private static String texto(Element padre, String tag) {
        NodeList lista = padre.getElementsByTagName(tag);
        if (lista.getLength() == 0) {
            return null;
        }
        Node n = lista.item(0);
        String valor = n.getTextContent();
        return valor == null ? null : valor.trim();
    }

    /** Distinct commercial units present in the catalogue, for assertions. */
    public static List<String> unidadesComerciales() {
        List<String> unidades = new ArrayList<>();
        for (ArticuloReal a : todos()) {
            if (a.tieneUnidadComercial() && !unidades.contains(a.unidadMedidaComercial())) {
                unidades.add(a.unidadMedidaComercial());
            }
        }
        return unidades;
    }
}
