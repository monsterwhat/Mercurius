package Services.Strategies;

import Models.AppSettings;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Jaxb.FE.FacturaElectronicaDocumento;
import Models.Encabezado.*;
import Models.Enums.Tipo_CondicionVenta;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import jakarta.inject.Inject;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.util.logging.Level;
import Utils.XmlEncabezadoFlattener;
import java.util.logging.Logger;
import java.util.Objects;

/**
 * Strategy for Factura Electrónica (FE, code "01").
 * Receptor is required with valid identification (no consumer fallback).
 */
@ApplicationScoped
public class FacturaElectronicaStrategy implements DocumentoStrategy {

    private static final Logger LOG = Logger.getLogger(FacturaElectronicaStrategy.class.getName());
    private static final JAXBContext JAXB_CONTEXT;

    static {
        JAXBContext ctx = null;
        try {
            ctx = JAXBContext.newInstance(FacturaElectronicaDocumento.class);
        } catch (JAXBException e) {
            LOG.log(Level.SEVERE, "Failed to initialize JAXBContext for FacturaElectronicaDocumento", e);
        }
        JAXB_CONTEXT = ctx;
    }

    private final EmisorService emisorService;
    private final ReceptorService receptorService;

    @Inject
    public FacturaElectronicaStrategy(EmisorService emisorService, ReceptorService receptorService) {
        this.emisorService = emisorService;
        this.receptorService = receptorService;
    }

    @Override
    public String getCodigoDocumento() {
        return "01";
    }

    @Override
    public String getRootElementName() {
        return "FacturaElectronica";
    }

    @Override
    public String getNamespace() {
        return "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica";
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
        FacturaElectronicaDocumento doc = new FacturaElectronicaDocumento(comprobante);
        marshaller.marshal(doc, sw);
        return XmlEncabezadoFlattener.flatten(sw.toString());
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

            // FE requires a receptor with valid ID
            if (selectedClient == null || selectedClient.getName() == null) {
                throw new IllegalArgumentException("Factura Electrónica requiere un cliente/receptor");
            }
            Receptor receptor = EncabezadoBuilder.buildReceptor(selectedClient);
            encabezado.setReceptor(receptor);
            receptorService.createIfNotExist(receptor);

            // CodigoActividadReceptor from selectedClient
            if (selectedClient.getCodigoActividadComercial() != null
                && !selectedClient.getCodigoActividadComercial().trim().isEmpty()) {
                encabezado.setCodigoActividadReceptor(selectedClient.getCodigoActividadComercial());
            }

            return encabezado;

        } catch (RuntimeException e) {
            throw new RuntimeException("Error building FE encabezado: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getCondicionVentaPermitidas() {
        return Set.of("01", "02", "03", "04", "05", "06", "07", "08", "10",
                      "12", "13", "14", "15", "99");
    }
}
