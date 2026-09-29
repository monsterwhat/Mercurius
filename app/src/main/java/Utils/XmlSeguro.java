package Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Single hardened XML entry point for documents that arrive from outside the
 * process (uploaded invoices, e-mailed attachments).
 *
 * <p>Exists because the DOCTYPE policy used to be re-implemented — and
 * inconsistently — at each call site. {@code FacturasRecibidasResource} had two
 * substring-based escapes from its own hardened parse
 * ({@code xml.contains("MensajeHacienda")} and a
 * {@code catch} that returned success when the hardened parse failed and the
 * text happened to contain {@code NumeroConsecutivo} — the second of which
 * waved through precisely the DOCTYPE-bearing documents
 * {@code disallow-doctype-decl} exists to reject). Meanwhile
 * {@code EmailService} called the parser on an e-mailed attachment with no gate
 * at all. One implementation, one policy, no bypass.</p>
 *
 * <p>Controls applied by {@link #analizarSeguro(byte[])}:</p>
 * <ul>
 *   <li>{@code disallow-doctype-decl} — a DOCTYPE is a hard parse error, which
 *       structurally rules out both internal entity expansion (billion-laughs)
 *       and external entity resolution.</li>
 *   <li>{@code FEATURE_SECURE_PROCESSING} — parser resource limits.</li>
 *   <li>{@code ACCESS_EXTERNAL_DTD} / {@code ACCESS_EXTERNAL_SCHEMA} set to
 *       the empty string — no {@code http:}/{@code file:}/{@code jar:}
 *       resolution even for anything that slips past the DOCTYPE check.</li>
 *   <li>{@code setXIncludeAware(false)} and
 *       {@code setExpandEntityReferences(false)}.</li>
 * </ul>
 */
public final class XmlSeguro {

    private XmlSeguro() {
    }

    /**
     * A {@link DocumentBuilderFactory} with every external-resolution control
     * applied. Callers must still handle the {@code SAXParseException} that a
     * DOCTYPE produces.
     */
    @Nonnull
    public static DocumentBuilderFactory factoryEndurecido() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        try {
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException e) {
            // Implementacion JAXP que no conoce la propiedad: el resto de los
            // controles (sobre todo disallow-doctype-decl) siguen vigentes, pero
            // se deja constancia en el log en vez de perderlo en silencio.
            org.jboss.logging.Logger.getLogger(XmlSeguro.class)
                    .warn("JAXP no soporta ACCESS_EXTERNAL_DTD/SCHEMA: "
                            + "el analisis XML se apoya solo en disallow-doctype-decl", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static void setFeature(@Nonnull DocumentBuilderFactory factory,
                                   @Nonnull String feature,
                                   boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception e) {
            // Un parser sin esta capacidad no es seguro para entrada externa:
            // se avisa loudly y el resto de controles siguen aplicandose.
            org.jboss.logging.Logger.getLogger(XmlSeguro.class)
                    .warn("El parser XML no soporta la caracteristica " + feature, e);
        }
    }

    /**
     * Whether the raw document declares a DOCTYPE.
     *
     * <p>Scans for {@code <!DOCTYPE}, skipping occurrences inside comments and
     * CDATA so a document that merely mentions the token in prose is not
     * rejected. Biased towards rejecting: a false positive costs one upload, a
     * false negative costs the XXE guarantee, and no real comprobante declares
     * a DOCTYPE in any case.</p>
     */
    public static boolean contieneDoctype(@Nonnull String xml) {
        int busqueda = 0;
        while (true) {
            int i = xml.indexOf("<!DOCTYPE", busqueda);
            if (i < 0) {
                return false;
            }
            if (dentroDe(xml, "<!--", "-->", i)) {
                busqueda = i + "<!DOCTYPE".length();
                continue;
            }
            if (dentroDe(xml, "<![CDATA[", "]]>", i)) {
                busqueda = i + "<!DOCTYPE".length();
                continue;
            }
            return true;
        }
    }

    /** Whether position {@code i} falls inside a {@code apertura}..{@code cierre} span. */
    private static boolean dentroDe(@Nonnull String xml, @Nonnull String apertura,
                                     @Nonnull String cierre, int i) {
        return xml.lastIndexOf(apertura, i) > xml.lastIndexOf(cierre, i);
    }

    /** Uniform message for a rejected DOCTYPE, so callers stay consistent. */
    @Nonnull
    public static String mensajeDoctypeRechazado() {
        return "Error parsing XML: el documento declara un DOCTYPE, que no se admite";
    }

    /**
     * Parses untrusted bytes with every control applied.
     *
     * @return the parsed document, or {@code null} when the input is empty or
     *         cannot be parsed safely (including any DOCTYPE).
     */
    @Nullable
    public static Document analizarSeguro(@Nonnull byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            return null;
        }
        try {
            return factoryEndurecido().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(contenido));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Whether the document's root element is {@code nombreRaiz}.
     *
     * <p>Compares against the local name so a namespace prefix
     * ({@code <ns2:MensajeHacienda>}) still matches — the invoice documents
     * carry one.</p>
     */
    public static boolean raizEs(@Nullable Document documento, @Nonnull String nombreRaiz) {
        if (documento == null) {
            return false;
        }
        Element raiz = documento.getDocumentElement();
        if (raiz == null) {
            return false;
        }
        String local = raiz.getLocalName() != null ? raiz.getLocalName() : raiz.getNodeName();
        return nombreRaiz.equals(local);
    }
}
