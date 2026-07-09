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
import java.util.logging.Level;
import Utils.XmlEncabezadoFlattener;
import java.util.logging.Logger;
import java.util.Objects;

/**
 * Strategy for Nota de Débito Electrónica (ND, code "02").
 * Receptor is required with valid identification (no consumer fallback).
 */
@ApplicationScoped
public class NotaDebitoElectronicaStrategy implements DocumentoStrategy {

    private static final Logger LOG = Logger.getLogger(NotaDebitoElectronicaStrategy.class.getName());
    private static final JAXBContext JAXB_CONTEXT;

    static {
        JAXBContext ctx = null;
        try {
            ctx = JAXBContext.newInstance(NotaDebitoElectronicaDocumento.class);
        } catch (JAXBException e) {
            LOG.log(Level.SEVERE, "Failed to initialize JAXBContext for NotaDebitoElectronicaDocumento", e);
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
        return "02";
    }

    @Override
    public String getRootElementName() {
        return "NotaDebitoElectronica";
    }

    @Override
    public String getNamespace() {
        return "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/notaDebitoElectronica";
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

            // ND requires a receptor with valid ID
            if (selectedClient == null || selectedClient.getName() == null) {
                throw new IllegalArgumentException("Nota de Débito Electrónica requiere un cliente/receptor");
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
            throw new RuntimeException("Error building ND encabezado: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getCondicionVentaPermitidas() {
        return Set.of("01", "02", "03", "04", "05", "06", "07", "08", "10", "99");
    }
}
