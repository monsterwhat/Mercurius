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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jboss.logging.Logger;

/**
 * Reverse of {@link XmlEncabezadoFlattener}.
 * <p>
 * Takes a <strong>flat</strong> v4.4-style XML (root children are Clave,
 * CodigoActividadEmisor, Emisor, etc. and ResumenFactura contains
 * structured {@code <MedioPago><TipoMedioPago>01</...>}) and produces the
 * v4.3-style XML needed when adjusting a 4.3 original:
 * </p>
 * <ul>
 *   <li>Wraps flat {@code Clave .. PlazoCredito} children back into a single
 *       {@code <Encabezado>} element (preserving document order), handling the
 *       v4.3 {@code CodigoActividad} singular mapping and dropping v4.4-only
 *       fields ({@code ProveedorSistemas}, {@code CodigoActividadReceptor},
 *       {@code CondicionVentaOtros}).</li>
 *   <li>Moves {@code ResumenFactura/MedioPago} (v4.4 structured, with
 *       {@code TipoMedioPago}) to root-level simple {@code <MedioPago>code</MedioPago>}
 *       elements (v4.3) inserted after {@code <Encabezado>} and before
 *       {@code <DetalleServicio>}.</li>
 *   <li>Optionally rewrites the default namespace from {@code /v4.4/} to
 *       {@code /v4.3/} so the result validates against the v4.3 XSD.</li>
 * </ul>
 *
 * <h3>Before (flat v4.4 after {@link XmlEncabezadoFlattener}):</h3>
 * <pre>{@code
 * <NotaCreditoElectronica xmlns=".../v4.4/notaCreditoElectronica">
 *   <Clave>...</Clave>
 *   <ProveedorSistemas>...</ProveedorSistemas>
 *   <CodigoActividadEmisor>...</CodigoActividadEmisor>
 *   ...
 *   <PlazoCredito>...</PlazoCredito>
 *   <DetalleServicio>...</DetalleServicio>
 *   <ResumenFactura>
 *     ...
 *     <MedioPago><TipoMedioPago>01</TipoMedioPago><TotalMedioPago>...</TotalMedioPago></MedioPago>
 *     <TotalComprobante>...</TotalComprobante>
 *   </ResumenFactura>
 * </NotaCreditoElectronica>
 * }</pre>
 *
 * <h3>After (v4.3 with Encabezado wrapper + root MedioPago):</h3>
 * <pre>{@code
 * <NotaCreditoElectronica xmlns=".../v4.3/notaCreditoElectronica">
 *   <Encabezado>
 *     <Clave>...</Clave>
 *     <CodigoActividad>...</CodigoActividad>
 *     <NumeroConsecutivo>...</NumeroConsecutivo>
 *     ...
 *     <PlazoCredito>...</PlazoCredito>
 *   </Encabezado>
 *   <MedioPago>01</MedioPago>
 *   <DetalleServicio>...</DetalleServicio>
 *   <ResumenFactura> ... (without MedioPago) ... </ResumenFactura>
 * </NotaCreditoElectronica>
 * }</pre>
 */
public final class XmlEncabezadoUnflattener {

    private static final Logger LOG = Logger.getLogger(XmlEncabezadoUnflattener.class);

    private static final DocumentBuilderFactory DB_FACTORY;
    private static final TransformerFactory T_FACTORY;

    /** Flat header element local names for v4.4 NC/ND/FE/etc. */
    private static final Set<String> HEADER_NAMES = Set.of(
            "Clave",
            "ProveedorSistemas",
            "CodigoActividadEmisor",
            "CodigoActividadReceptor",
            "CodigoActividad",
            "NumeroConsecutivo",
            "FechaEmision",
            "Emisor",
            "Receptor",
            "CondicionVenta",
            "CondicionVentaOtros",
            "PlazoCredito"
    );

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

    private XmlEncabezadoUnflattener() {
        // utility class
    }

    /**
     * Wraps flat {@code Clave .. PlazoCredito} children back into {@code <Encabezado>}
     * and moves {@code ResumenFactura/MedioPago} codes to root {@code <MedioPago>} simple codes.
     * <p>
     * Also rewrites namespace {@code /v4.4/} to {@code /v4.3/} when present so the
     * result validates against v4.3 XSDs (NC/ND adjusting a 4.3 original).
     * </p>
     *
     * @param xml the flat XML string (after {@link XmlEncabezadoFlattener#flatten(String)})
     * @return the unflattened v4.3-style XML string.
     * @throws IllegalArgumentException if the XML cannot be parsed.
     */
    @Nonnull
    public static String unflatten(@Nonnull String xml) {
        try {
            DocumentBuilder builder = DB_FACTORY.newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            Element root = doc.getDocumentElement();
            String rootNs = root.getNamespaceURI();

            // If Encabezado already exists, skip wrapping but still handle MedioPago migration
            boolean hasEncabezado = false;
            NodeList rootChildren = root.getChildNodes();
            for (int i = 0; i < rootChildren.getLength(); i++) {
                Node n = rootChildren.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE && "Encabezado".equals(n.getLocalName())) {
                    hasEncabezado = true;
                    break;
                }
            }

            if (!hasEncabezado) {
                // Collect header elements in document order
                List<Node> headerNodes = new ArrayList<>();
                NodeList children = root.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node.getNodeType() != Node.ELEMENT_NODE) continue;
                    String local = node.getLocalName();
                    if (local != null && HEADER_NAMES.contains(local)) {
                        headerNodes.add(node);
                    }
                }

                if (!headerNodes.isEmpty()) {
                    // Create Encabezado wrapper with same namespace as root (v4.4 or v4.3)
                    String ns = rootNs != null ? rootNs : "";
                    Element encabezado = ns.isEmpty() ? doc.createElement("Encabezado") : doc.createElementNS(ns, "Encabezado");

                    for (Node headerNode : headerNodes) {
                        String local = headerNode.getLocalName();
                        if ("CodigoActividadEmisor".equals(local)) {
                            // v4.4 CodigoActividadEmisor -> v4.3 CodigoActividad singular
                            Element codigoActividad = ns.isEmpty() ? doc.createElement("CodigoActividad") : doc.createElementNS(ns, "CodigoActividad");
                            codigoActividad.setTextContent(headerNode.getTextContent() != null ? headerNode.getTextContent().trim() : "");
                            root.removeChild(headerNode);
                            encabezado.appendChild(codigoActividad);
                        } else if ("CodigoActividadReceptor".equals(local)
                                || "ProveedorSistemas".equals(local)
                                || "CondicionVentaOtros".equals(local)) {
                            // v4.4-only fields: drop for v4.3
                            root.removeChild(headerNode);
                        } else if ("CodigoActividad".equals(local)) {
                            // Already singular (e.g. if input was already v4.3-like) — keep as is
                            root.removeChild(headerNode);
                            encabezado.appendChild(headerNode);
                        } else {
                            root.removeChild(headerNode);
                            encabezado.appendChild(headerNode);
                        }
                    }

                    // Insert Encabezado as first element child (before DetalleServicio etc.)
                    Node firstElement = null;
                    NodeList afterRemoval = root.getChildNodes();
                    for (int i = 0; i < afterRemoval.getLength(); i++) {
                        Node n = afterRemoval.item(i);
                        if (n.getNodeType() == Node.ELEMENT_NODE) {
                            firstElement = n;
                            break;
                        }
                    }
                    if (firstElement != null) {
                        root.insertBefore(encabezado, firstElement);
                    } else {
                        root.appendChild(encabezado);
                    }
                } else {
                    LOG.warn("XmlEncabezadoUnflattener: No flat header children (Clave..PlazoCredito) found, returning XML as-is for MedioPago handling.");
                }
            } else {
                LOG.info("XmlEncabezadoUnflattener: Encabezado already present, skipping wrap, proceeding to MedioPago migration.");
            }

            // --- Move ResumenFactura/MedioPago to root MedioPago codes ---
            Element resumen = findChildElement(root, "ResumenFactura");
            if (resumen != null) {
                NodeList resumenChildren = resumen.getChildNodes();
                List<Node> medioPagoToRemove = new ArrayList<>();
                List<String> medioPagoCodes = new ArrayList<>();
                for (int i = 0; i < resumenChildren.getLength(); i++) {
                    Node n = resumenChildren.item(i);
                    if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                    if (!"MedioPago".equals(n.getLocalName())) continue;
                    String code = null;
                    // Structured v4.4: <MedioPago><TipoMedioPago>01</TipoMedioPago>...</MedioPago>
                    NodeList mpChildren = n.getChildNodes();
                    for (int j = 0; j < mpChildren.getLength(); j++) {
                        Node c = mpChildren.item(j);
                        if (c.getNodeType() != Node.ELEMENT_NODE) continue;
                        if ("TipoMedioPago".equals(c.getLocalName())) {
                            code = c.getTextContent() != null ? c.getTextContent().trim() : null;
                            break;
                        }
                    }
                    if (code == null || code.isBlank()) {
                        // Fallback: simple text content (v4.3 already) or whitespace
                        String txt = n.getTextContent() != null ? n.getTextContent().trim() : "";
                        // If txt contains multiple values due to structured children without TipoMedioPago, take first token
                        if (!txt.isBlank()) {
                            // If txt is like "01" or "01\n    " etc.
                            String[] parts = txt.split("\\s+");
                            for (String p : parts) {
                                if (!p.isBlank()) {
                                    code = p.trim();
                                    break;
                                }
                            }
                            if (code == null) code = txt;
                        }
                    }
                    if (code != null && !code.isBlank()) {
                        medioPagoCodes.add(code.trim());
                    } else {
                        LOG.warn("XmlEncabezadoUnflattener: MedioPago without TipoMedioPago code, skipping. Node: " + n.getTextContent());
                    }
                    medioPagoToRemove.add(n);
                }

                for (Node mp : medioPagoToRemove) {
                    resumen.removeChild(mp);
                }

                if (!medioPagoCodes.isEmpty()) {
                    // Insert root-level MedioPago simple codes after Encabezado, before DetalleServicio
                    String ns = rootNs != null ? rootNs : "";
                    Element detalle = findChildElement(root, "DetalleServicio");
                    Element otherAfterEnc = null;
                    // Prefer DetalleServicio as anchor; fallback to ResumenFactura
                    Node anchor = detalle != null ? detalle : resumen;
                    // If anchor is ResumenFactura and we are inserting before it, MedioPago will be between Encabezado and Resumen when no DetalleServicio
                    for (String code : medioPagoCodes) {
                        Element rootMp = ns.isEmpty() ? doc.createElement("MedioPago") : doc.createElementNS(ns, "MedioPago");
                        rootMp.setTextContent(code);
                        if (anchor != null) {
                            root.insertBefore(rootMp, anchor);
                        } else {
                            // No DetalleServicio and no Resumen? Insert after Encabezado
                            Element enc = findChildElement(root, "Encabezado");
                            if (enc != null && enc.getNextSibling() != null) {
                                root.insertBefore(rootMp, enc.getNextSibling());
                            } else {
                                root.appendChild(rootMp);
                            }
                        }
                    }
                }
            }

            // Serialize back to string
            Transformer transformer = T_FACTORY.newTransformer();
            StringWriter sw = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(sw));
            String result = sw.toString();
            // Rewrite namespace v4.4 -> v4.3 for NC/ND when the original flat was v4.4
            // Keep 4.4 path byte-identical by only doing this in unflatten (4.3 path).
            if (result.contains("/v4.4/")) {
                result = result.replace("/v4.4/", "/v4.3/");
            }
            return result;

        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to unflatten Encabezado wrapper in XML: " + e.getMessage(), e);
        }
    }

    private static Element findChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && localName.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }
}
