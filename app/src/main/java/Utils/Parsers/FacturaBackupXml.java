package Utils.Parsers;

import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Utils.XmlEncabezadoUnflattener;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import org.jboss.logging.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Importa los XML de facturación respaldados por el sistema anterior
 * ({@code DOCUMENTOS_ELECTRONICOS/AAAA/MM/DD/*.xml}) al modelo persistido
 * {@link ComprobantesEmitidos}.
 *
 * <p>Los XML de Hacienda traen el encabezado "plano" (Clave, Emisor, ... como
 * hijos directos de la raíz). {@link XmlEncabezadoUnflattener} lo vuelve a
 * envolver en {@code <Encabezado>}; luego se quitan namespaces/prefijos, se
 * renombra la raíz al modelo y {@code <ResumenFactura>} a {@code <Resumen>}
 * para que JAXB llene la entidad completa.</p>
 */
public final class FacturaBackupXml {

    private static final Logger LOG = Logger.getLogger(FacturaBackupXml.class);
    private static final Pattern ROOT = Pattern.compile("<(?:[A-Za-z0-9]+:)?([A-Za-z]+)[ >\\r\\n]");
    private static final Pattern VERSION = Pattern.compile("/v(\\d+\\.\\d+)/");
    private static final JAXBContext CONTEXT = crearContexto();

    private FacturaBackupXml() {
    }

    private static JAXBContext crearContexto() {
        try {
            return JAXBContext.newInstance(ComprobantesEmitidos.class);
        } catch (Exception e) {
            LOG.error("No se pudo crear el JAXBContext del importador de facturas", e);
            return null;
        }
    }

    /**
     * @param xml     contenido del XML (factura, tiquete, nota o recibo)
     * @param usuario nombre de usuario que quedará como cajero del documento
     */
    @Nonnull
    public static ComprobantesEmitidos parse(@Nonnull String xml, @Nonnull String usuario) throws Exception {
        if (CONTEXT == null) {
            throw new IllegalStateException("JAXBContext no disponible para importar facturas");
        }
        String envuelto = XmlEncabezadoUnflattener.unflatten(xml);
        Document doc = parseSinNamespaces(envuelto);
        doc.renameNode(doc.getDocumentElement(), null, "ComprobantesEmitidos");
        renombrar(doc.getDocumentElement(), "ResumenFactura", "Resumen");

        Unmarshaller unmarshaller = CONTEXT.createUnmarshaller();
        ComprobantesEmitidos ce = (ComprobantesEmitidos) unmarshaller.unmarshal(new DOMSource(doc));
        if (ce.getEncabezado() == null
                || ce.getEncabezado().getClave() == null
                || ce.getEncabezado().getClave().isBlank()) {
            throw new IllegalArgumentException("XML sin Clave en el encabezado");
        }
        ce.setSchemaVersion(versionDe(xml));
        ce.setUser(usuario);
        ce.setStatus(Boolean.TRUE);
        ce.setHaciendaClave(ce.getEncabezado().getClave());
        ce.setHaciendaEstado("ACEPTADO");
        ce.setCorrectionAttempts(0);
        String codigo = codigoDocumento(tipoDocumento(xml));
        if (codigo != null) {
            ce.getEncabezado().setCodigoDocumento(codigo);
        }
        enlazarDetalles(ce);
        return ce;
    }

    /**
     * JAXB llena las listas pero no las referencias inversas {@code mappedBy};
     * sin esto las líneas se insertan sin {@code detalle_servicio_id}.
     */
    private static void enlazarDetalles(@Nonnull ComprobantesEmitidos ce) {
        Models.Encabezado.Encabezado enc = ce.getEncabezado();
        if (enc != null) {
            if (enc.getEmisor() != null && enc.getEmisor().getCorreosElectronicos() != null) {
                enc.getEmisor().getCorreosElectronicos().forEach(c -> c.setEmisor(enc.getEmisor()));
            }
            if (enc.getReceptor() != null && enc.getReceptor().getCorreosElectronicos() != null) {
                enc.getReceptor().getCorreosElectronicos().forEach(c -> c.setReceptor(enc.getReceptor()));
            }
            if (enc.getMedioPago() != null) {
                enc.getMedioPago().forEach(m -> m.setComprobante(enc));
            }
        }
        DetalleServicio ds = ce.getDetalles();
        if (ds == null) {
            return;
        }
        if (ds.getOtrosCargos() != null) {
            ds.getOtrosCargos().forEach(cargo -> cargo.setDetalleServicio(ds));
        }
        if (ds.getLineasDetalle() == null) {
            return;
        }
        for (LineaDetalle linea : ds.getLineasDetalle()) {
            linea.setDetalleServicio(ds);
            if (linea.getCodigosComerciales() != null) {
                linea.getCodigosComerciales().forEach(c -> c.setLineaDetalle(linea));
            }
            if (linea.getDescuentos() != null) {
                linea.getDescuentos().forEach(d -> d.setLineaDetalle(linea));
            }
            if (linea.getImpuestos() != null) {
                linea.getImpuestos().forEach(i -> i.setLineaDetalle(linea));
            }
            if (linea.getNumerosVINoSerie() != null) {
                linea.getNumerosVINoSerie().forEach(n -> n.setLineaDetalle(linea));
            }
        }
    }

    /** Nombre del elemento raíz (FacturaElectronica, TiqueteElectronico, ...). */
    @Nonnull
    public static String tipoDocumento(@Nonnull String xml) {
        Matcher m = ROOT.matcher(xml);
        return m.find() ? m.group(1) : "DESCONOCIDO";
    }

    /** Código de documento de Hacienda para el tipo de raíz. */
    @Nullable
    public static String codigoDocumento(@Nonnull String tipo) {
        return switch (tipo) {
            case "FacturaElectronica" -> "01";
            case "NotaCreditoElectronica" -> "02";
            case "NotaDebitoElectronica" -> "03";
            case "TiqueteElectronico" -> "04";
            case "FacturaExportacionElectronica" -> "05";
            case "FacturaElectronicaCompra" -> "08";
            case "ReciboElectronicoPago" -> "10";
            default -> null;
        };
    }

    /** Documentos que representan una venta (los que pide el importador). */
    public static boolean esVenta(@Nonnull String tipo) {
        return "FacturaElectronica".equals(tipo)
                || "TiqueteElectronico".equals(tipo)
                || "FacturaExportacionElectronica".equals(tipo);
    }

    @Nonnull
    private static String versionDe(@Nonnull String xml) {
        Matcher m = VERSION.matcher(xml);
        return m.find() ? m.group(1) : "4.4";
    }

    @Nonnull
    private static Document parseSinNamespaces(@Nonnull String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // El XML viene de un respaldo local, no de la red: se desactiva la
        // resolución de entidades externas.
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document doc = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        limpiar(doc.getDocumentElement());
        return doc;
    }

    private static void limpiar(@Nonnull Element el) {
        NamedNodeMap attrs = el.getAttributes();
        for (int i = attrs.getLength() - 1; i >= 0; i--) {
            Node attr = attrs.item(i);
            if (attr.getNodeName().startsWith("xmlns") || attr.getNodeName().contains(":")) {
                el.removeAttributeNode((org.w3c.dom.Attr) attr);
            }
        }
        NodeList hijos = el.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node hijo = hijos.item(i);
            if (hijo.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) hijo;
                String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                el.getOwnerDocument().renameNode(child, null, local.replaceAll("^.*:", ""));
                limpiar(child);
            }
        }
    }

    private static void renombrar(@Nonnull Element el, @Nonnull String desde, @Nonnull String hacia) {
        NodeList hijos = el.getChildNodes();
        for (int i = 0; i < hijos.getLength(); i++) {
            Node hijo = hijos.item(i);
            if (hijo.getNodeType() == Node.ELEMENT_NODE) {
                Element child = (Element) hijo;
                String local = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
                if (desde.equals(local)) {
                    el.getOwnerDocument().renameNode(child, null, hacia);
                }
                renombrar(child, desde, hacia);
            }
        }
    }
}
