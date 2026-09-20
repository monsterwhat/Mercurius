package Services.Strategies;

import Models.AppSettings;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Jaxb.ND.NotaDebitoElectronicaDocumento;
import Models.Encabezado.*;
import Models.Enums.Tipo_CondicionVenta;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import jakarta.inject.Inject;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;

import Utils.XmlEncabezadoFlattener;
import Utils.XmlEncabezadoUnflattener;
import org.jboss.logging.Logger;
import java.util.Objects;

/**
 * Strategy for Nota de Débito Electrónica (ND, code "03").
 * Receptor is required with valid identification (no consumer fallback).
 */
@ApplicationScoped
public class NotaDebitoElectronicaStrategy implements DocumentoStrategy {

    private static final Logger LOG = Logger.getLogger(NotaDebitoElectronicaStrategy.class);
    private static final JAXBContext JAXB_CONTEXT;

    static {
        JAXBContext ctx = null;
        try {
            ctx = JAXBContext.newInstance(NotaDebitoElectronicaDocumento.class);
        } catch (JAXBException e) {
            LOG.error("Failed to initialize JAXBContext for NotaDebitoElectronicaDocumento", e);
        }
        JAXB_CONTEXT = ctx;
    }

    @Nonnull
    private final EmisorService emisorService;
    @Nonnull
    private final ReceptorService receptorService;

    @Inject
    public NotaDebitoElectronicaStrategy(@Nonnull EmisorService emisorService, @Nonnull ReceptorService receptorService) {
        this.emisorService = emisorService;
        this.receptorService = receptorService;
    }

    @Override
    public String getCodigoDocumento() {
        return "03";
    }

    @Override
    public String getRootElementName() {
        return "NotaDebitoElectronica";
    }

    @Override
    public String getNamespace() {
        return "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/notaDebitoElectronica";
    }

    @Override
    public boolean requiresReceptor() {
        return true;
    }

    @Override
    public String buildXml(ComprobantesEmitidos comprobante) throws JAXBException {
        if (JAXB_CONTEXT == null) {
            throw new JAXBException("JAXBContext was not initialized due to previous error");
        }
        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter sw = new StringWriter();
        NotaDebitoElectronicaDocumento doc = new NotaDebitoElectronicaDocumento(comprobante);
        marshaller.marshal(doc, sw);
        String flattened = XmlEncabezadoFlattener.flatten(sw.toString());
        if (isVersion43(comprobante)) {
            LOG.info("NotaDebitoElectronicaStrategy: emitting v4.3 unflattened XML for adjustment of 4.3 original | clave=" + (comprobante.getEncabezado() != null ? comprobante.getEncabezado().getClave() : "null"));
            return XmlEncabezadoUnflattener.unflatten(flattened);
        }
        return flattened;
    }

    private static boolean isVersion43(ComprobantesEmitidos c) {
        String v = null;
        if (c != null && c.getSchemaVersion() != null && !c.getSchemaVersion().isBlank()) {
            v = c.getSchemaVersion();
        } else if (c != null && c.getEncabezado() != null && c.getEncabezado().getSchemaVersion() != null && !c.getEncabezado().getSchemaVersion().isBlank()) {
            v = c.getEncabezado().getSchemaVersion();
        } else if (c != null && c.getResumen() != null && c.getResumen().getSchemaVersion() != null && !c.getResumen().getSchemaVersion().isBlank()) {
            v = c.getResumen().getSchemaVersion();
        }
        return "4.3".equals(v != null ? v.trim() : null);
    }

    @Override
    public Encabezado buildEncabezado(AppSettings appSettings, Clients selectedClient) {
        if (Objects.equals(appSettings.getEstatus(), Boolean.FALSE)) return null;

        try {
            Encabezado encabezado = new Encabezado();
            EncabezadoBuilder.initEncabezado(appSettings, encabezado, getCodigoDocumento());

            if (encabezado.getCondicionVenta() == null) {
                encabezado.setCondicionVenta(Tipo_CondicionVenta.CONTADO.getCodigo());
            }
            if ("99".equals(encabezado.getCondicionVenta())) {
                encabezado.setCondicionVentaOtros("Condicion de venta no especificada");
            }
            validarCondicionVenta(encabezado.getCondicionVenta());
            validarPlazoCredito(encabezado.getCondicionVenta(), encabezado.getPlazoCredito());

            Emisor emisor = EncabezadoBuilder.buildEmisor(appSettings, emisorService);
            encabezado.setEmisor(emisor);

            // ND requires a receptor with valid ID
            if (selectedClient == null || selectedClient.getName() == null) {
                throw new IllegalArgumentException("Nota de Débito Electrónica requiere un cliente/receptor");
            }
            Receptor receptor = EncabezadoBuilder.buildReceptor(selectedClient);
            encabezado.setReceptor(receptor);
            receptorService.createIfNotExist(receptor);

        String codigoAct = selectedClient.getPrimaryActividadCode();
        if (codigoAct != null && !codigoAct.isBlank()) {
            encabezado.setCodigoActividadReceptor(codigoAct);
            }

            return encabezado;

        } catch (RuntimeException e) {
            throw new RuntimeException("Error building ND encabezado: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getCondicionVentaPermitidas() {
        return Set.of("01", "02", "03", "04", "05", "06", "07", "08", "10", "99");
    }
}
