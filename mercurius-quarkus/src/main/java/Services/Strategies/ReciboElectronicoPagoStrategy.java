package Services.Strategies;

import Models.AppSettings;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Jaxb.REP.ReciboElectronicoPagoDocumento;
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
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Strategy for Recibo Electrónico de Pago (REP, code "07").
 * Per Hacienda v4.4 spec, REP is used for credit sales, government institution sales,
 * and electronic payment receipts. Receptor is required with valid identification.
 */
@ApplicationScoped
public class ReciboElectronicoPagoStrategy implements DocumentoStrategy {

    private static final Logger LOG = Logger.getLogger(ReciboElectronicoPagoStrategy.class.getName());
    private static final JAXBContext JAXB_CONTEXT;

    static {
        JAXBContext ctx = null;
        try {
            ctx = JAXBContext.newInstance(ReciboElectronicoPagoDocumento.class);
        } catch (JAXBException e) {
            LOG.log(Level.SEVERE, "Failed to initialize JAXBContext for ReciboElectronicoPagoDocumento", e);
        }
        JAXB_CONTEXT = ctx;
    }

    @Nonnull
    private final EmisorService emisorService;
    @Nonnull
    private final ReceptorService receptorService;

    @Inject
    public ReciboElectronicoPagoStrategy(@Nonnull EmisorService emisorService, @Nonnull ReceptorService receptorService) {
        this.emisorService = emisorService;
        this.receptorService = receptorService;
    }

    @Override
    public String getCodigoDocumento() {
        return "10";
    }

    @Override
    public String getRootElementName() {
        return "ReciboElectronicoPago";
    }

    @Override
    public String getNamespace() {
        return "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/reciboElectronicoPago";
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
        ReciboElectronicoPagoDocumento doc = new ReciboElectronicoPagoDocumento(comprobante);
        marshaller.marshal(doc, sw);
        return XmlEncabezadoFlattener.flatten(sw.toString());
    }

    @Override
    public Encabezado buildEncabezado(AppSettings appSettings, Clients selectedClient) {
        if (Objects.equals(appSettings.getEstatus(), Boolean.FALSE)) return null;

        try {
            Encabezado encabezado = new Encabezado();
            // REP V4.4 XSD does NOT have CodigoActividadEmisor in EncabezadoType
            encabezado.setProveedorSistemas(appSettings.getProvedor());
            encabezado.setNumeroConsecutivo("");
            encabezado.setFechaEmision(LocalDateTime.now().withNano(0));
            if (encabezado.getCondicionVenta() == null) {
                // REP only uses 09 (Pago servicios prestados al Estado) or 11 (Pago venta credito IVA 90 dias)
                encabezado.setCondicionVenta(Tipo_CondicionVenta.PAGO_VENTA_CREDITO_IVA_HASTA_90_DIAS.getCodigo());
            }

            validarCondicionVenta(encabezado.getCondicionVenta());
            encabezado.setCodigoDocumento(getCodigoDocumento());

            Emisor emisor = EncabezadoBuilder.buildEmisor(appSettings, emisorService);
            encabezado.setEmisor(emisor);

            // REP requires a receptor with valid ID
            if (selectedClient == null || selectedClient.getName() == null) {
                throw new IllegalArgumentException("Recibo Electrónico de Pago requiere un cliente/receptor");
            }
            Receptor receptor = EncabezadoBuilder.buildReceptor(selectedClient);
            encabezado.setReceptor(receptor);
            receptorService.createIfNotExist(receptor);

            // REP V4.4 XSD does NOT have CodigoActividadReceptor in EncabezadoType

            return encabezado;

        } catch (RuntimeException e) {
            throw new RuntimeException("Error building REP encabezado: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getCondicionVentaPermitidas() {
        // REP: ONLY 09 (Pago servicios prestados al Estado) and 11 (Pago venta credito IVA 90 dias)
        return Set.of("09", "11");
    }
}
