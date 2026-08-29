package Utils;

import jakarta.annotation.Nonnull;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import org.jboss.logging.Logger;

/**
 * Post-processes JAXB-marshalled XML to remove the {@code <Encabezado>} wrapper element
 * and promote its children to be direct children of the root element.
 * <p>
 * Hacienda v4.4 XSDs define a <strong>flat</strong> structure where fields like
 * Clave, Emisor, Receptor, etc. are direct children of the root element
 * ({@code <FacturaElectronica>}, {@code <TiqueteElectronico>}, etc.).
 * The existing JAXB model wraps these fields inside {@code <Encabezado>}
 * (the v4.3 convention), which fails v4.4 XSD validation.
 * </p>
 * <p>
 * This flattener is applied after JAXB marshalling and before XSD validation/signing
 * to produce the v4.4-compliant flat XML without modifying the JPA entity model.
 * </p>
 *
 * <h3>Before:</h3>
 * <pre>{@code
 * <TiqueteElectronico xmlns="...">
 *   <Encabezado>
 *     <Clave>...</Clave>
 *     <Emisor>...</Emisor>
 *     <Receptor>...</Receptor>
 *     ...
 *   </Encabezado>
 *   <DetalleServicio>...</DetalleServicio>
 *   <ResumenFactura>...</ResumenFactura>
 * </TiqueteElectronico>
 * }</pre>
 *
 * <h3>After:</h3>
 * <pre>{@code
 * <TiqueteElectronico xmlns="...">
 *   <Clave>...</Clave>
 *   <Emisor>...</Emisor>
 *   <Receptor>...</Receptor>
 *   ...
 *   <DetalleServicio>...</DetalleServicio>
 *   <ResumenFactura>...</ResumenFactura>
 * </TiqueteElectronico>
 * }</pre>
 */
public final class XmlEncabezadoFlattener {

    private static final Logger LOG = Logger.getLogger(XmlEncabezadoFlattener.class);

    private static final DocumentBuilderFactory DB_FACTORY;
    private static final TransformerFactory T_FACTORY;

    static {
        DB_FACTORY = DocumentBuilderFactory.newInstance();
        DB_FACTORY.setNamespaceAware(true);
        try {
            DB_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DB_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DB_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (ParserConfigurationException e) {
            LOG.warn("Failed to set XXE-prevention features on DocumentBuilderFactory", e);
        }
        DB_FACTORY.setXIncludeAware(false);
        DB_FACTORY.setExpandEntityReferences(false);

        T_FACTORY = TransformerFactory.newInstance();
        try {
            T_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            T_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            T_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            LOG.warn("Failed to set XXE-prevention features on TransformerFactory", e);
        }
    }

    private XmlEncabezadoFlattener() {
        // utility class
    }

    /**
     * Removes the {@code <Encabezado>} wrapper element from {@code xml}, promoting
     * its child elements to be direct children of the root element.
     * <p>
     * The order of Encabezado children (Clave, Emisor, etc.) is preserved and they
     * are inserted before existing non-Encabezado siblings (DetalleServicio, ResumenFactura, etc.).
     *
     * @param xml the marshalled XML string that may contain an {@code <Encabezado>} wrapper.
     * @return the flattened XML string with {@code <Encabezado>} removed and its children
     *         promoted to the root element.
     * @throws IllegalArgumentException if the XML cannot be parsed.
     */
    @Nonnull
    public static String flatten(@Nonnull String xml) {
        try {
            DocumentBuilder builder = DB_FACTORY.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();

            // Find and remove the Encabezado wrapper element
            NodeList children = root.getChildNodes();
            Element encabezadoElem = null;
            int encabezadoIndex = -1;

            for (int i = 0; i < children.getLength(); i++) {
                Node node = children.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE
                    && "Encabezado".equals(node.getLocalName())) {
                    encabezadoElem = (Element) node;
                    encabezadoIndex = i;
                    break;
                }
            }

            if (encabezadoElem == null) {
                // No Encabezado wrapper found — return as-is
                LOG.warn("XmlEncabezadoFlattener: Encabezado tag not found, returning XML as-is. Input may not be a valid comprobante.");
                return xml;
            }

            // Promote all non-text children of Encabezado to root, preserving their order
            // Insert them at the position where Encabezado was
            NodeList encChildren = encabezadoElem.getChildNodes();
            // Collect element nodes in reverse order so insertBefore preserves their order
            java.util.ArrayList<Node> promotedNodes = new java.util.ArrayList<>();
            for (int i = 0; i < encChildren.getLength(); i++) {
                Node child = encChildren.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    promotedNodes.add(child);
                }
            }

            // Insert before the reference node (the sibling that was after Encabezado)
            Node refNode = encabezadoElem.getNextSibling();
            for (Node promoted : promotedNodes) {
                encabezadoElem.removeChild(promoted);
                root.insertBefore(promoted, refNode);
            }

            // Remove the now-empty Encabezado element
            root.removeChild(encabezadoElem);

            // Serialize back to string
            Transformer transformer = T_FACTORY.newTransformer();
            StringWriter sw = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();

        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to flatten Encabezado wrapper in XML: " + e.getMessage(), e);
        }
    }
}
