package Services.Strategies;

import Models.AppSettings;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Documentos.NotaDebitoElectronicaDocumento;
import Models.Encabezado.*;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Enums.Tipo_CondicionVenta;
import Services.Facturas.EmisorService;
import Services.Facturas.ReceptorService;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;
import jakarta.inject.Inject;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Strategy for Nota de Débito Electrónica (ND, code "03").
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

    private final EmisorService emisorService;
    private final ReceptorService receptorService;

    @Inject
    public NotaDebitoElectronicaStrategy(EmisorService emisorService, ReceptorService receptorService) {
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
        return sw.toString();
    }

    @Override
    public Encabezado buildEncabezado(AppSettings appSettings, Clients selectedClient) {
        if (Objects.equals(appSettings.getEstatus(), Boolean.FALSE)) return null;

        try {
            Encabezado encabezado = new Encabezado();
            encabezado.setCodigoActividadEmisor(appSettings.getCodigoActividad());
            encabezado.setProveedorSistemas(appSettings.getProvedor());
            encabezado.setNumeroConsecutivo("");
            LocalDateTime emision = LocalDateTime.now().withNano(0);
            encabezado.setFechaEmision(emision);
            if (encabezado.getCondicionVenta() == null) {
                encabezado.setCondicionVenta(Tipo_CondicionVenta.CONTADO.getCodigo());
            }

            // CondicionVentaOtros when "99"
            if ("99".equals(encabezado.getCondicionVenta())) {
                encabezado.setCondicionVentaOtros("Condicion de venta no especificada");
            }

            validarCondicionVenta(encabezado.getCondicionVenta());
            validarPlazoCredito(encabezado.getCondicionVenta(), encabezado.getPlazoCredito());

            encabezado.setCodigoDocumento(getCodigoDocumento());

            // Emisor
            Emisor emisor = new Emisor();
            emisor.setNombre(appSettings.getNombre());
            IdentificacionEmisor emisorId = new IdentificacionEmisor();
            emisorId.setNumero(appSettings.getIdentificacion());
            emisorId.setTipo(appSettings.getTipoIdentificacion());
            emisor.setIdentificacion(emisorId);
            emisor.setNombreComercial(appSettings.getNombreNegocio());
            Ubicacion emisorUbicacion = new Ubicacion();
            emisorUbicacion.setProvincia(appSettings.getProvincia());
            emisorUbicacion.setCanton(appSettings.getCanton());
            emisorUbicacion.setDistrito(appSettings.getDistrito());
            emisorUbicacion.setBarrio(appSettings.getBarrio());
            emisorUbicacion.setOtrasSenas(appSettings.getDireccionCompleta());
            emisor.setUbicacion(emisorUbicacion);
            Telefono emisorTelefono = new Telefono();
            emisorTelefono.setCodigoPais(appSettings.getCodigoPais());
            emisorTelefono.setNumeroTelefono(appSettings.getTelefono());
            emisor.setTelefono(emisorTelefono);
            List<CorreoElectronicoEmisor> correosElectronicos = new ArrayList<>();

            if (appSettings.getCorreoElectronicoTributacion() != null && !appSettings.getCorreoElectronicoTributacion().trim().isEmpty()) {
                CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                correo.setCorreo(appSettings.getCorreoElectronicoTributacion());
                correo.setEmisor(emisor);
                correosElectronicos.add(correo);
            }

            if (appSettings.getCorreoElectronicoTributacion2() != null && !appSettings.getCorreoElectronicoTributacion2().trim().isEmpty()) {
                CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                correo.setCorreo(appSettings.getCorreoElectronicoTributacion2());
                correo.setEmisor(emisor);
                correosElectronicos.add(correo);
            }
            if (appSettings.getCorreoElectronicoTributacion3() != null && !appSettings.getCorreoElectronicoTributacion3().trim().isEmpty()) {
                CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                correo.setCorreo(appSettings.getCorreoElectronicoTributacion3());
                correo.setEmisor(emisor);
                correosElectronicos.add(correo);
            }
            if (appSettings.getCorreoElectronicoTributacion4() != null && !appSettings.getCorreoElectronicoTributacion4().trim().isEmpty()) {
                CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
                correo.setCorreo(appSettings.getCorreoElectronicoTributacion4());
                correo.setEmisor(emisor);
                correosElectronicos.add(correo);
            }

            emisor.setCorreosElectronicos(correosElectronicos);
            encabezado.setEmisor(emisor);
            emisorService.create(emisor);

            // ND requires a receptor with valid ID
            if (selectedClient == null || selectedClient.getName() == null) {
                throw new IllegalArgumentException("Nota de Débito Electrónica requiere un cliente/receptor");
            }
            Receptor receptor = TiqueteElectronicoStrategy.buildReceptor(selectedClient);
            encabezado.setReceptor(receptor);
            receptorService.createIfNotExist(receptor);

            // CodigoActividadReceptor from selectedClient
            if (selectedClient != null && selectedClient.getCodigoActividadComercial() != null
                && !selectedClient.getCodigoActividadComercial().trim().isEmpty()) {
                encabezado.setCodigoActividadReceptor(selectedClient.getCodigoActividadComercial());
            }

            return encabezado;

        } catch (Exception e) {
            throw new RuntimeException("Error building ND encabezado: " + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getCondicionVentaPermitidas() {
        return Set.of("01", "02", "03", "04", "05", "06", "07", "08", "10", "99");
    }
}
