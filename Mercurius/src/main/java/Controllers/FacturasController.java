package Controllers;

import Services.FacturaService;
import Models.Articulos;
import Models.Comprobantes.ComprobanteFinal;
import Models.Comprobantes.Detalles.CodigoComercial;
import Models.Comprobantes.Detalles.Descuento;
import Models.Comprobantes.Detalles.DetalleServicio;
import Models.Comprobantes.Detalles.Impuesto;
import Models.Comprobantes.Detalles.LineaDetalle;
import Models.Comprobantes.Encabezado.Emisor;
import Models.Comprobantes.Encabezado.Encabezado;
import Models.Comprobantes.Encabezado.Fax;
import Models.Comprobantes.Encabezado.IdentificacionEmisor;
import Models.Comprobantes.Encabezado.IdentificacionReceptor;
import Models.Comprobantes.Encabezado.MedioPago;
import Models.Comprobantes.Encabezado.Receptor;
import Models.Comprobantes.Encabezado.Telefono;
import Models.Comprobantes.Encabezado.Ubicacion;
import Models.Comprobantes.Enums.MedioPagoEnum;
import Models.Comprobantes.Resumen.CodigoTipoMoneda;
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Departamento;
import Models.Inventario;
import Services.Facturas.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;

/**
 *
 * @author Al
 */

@Named
@Data
@ViewScoped
public class FacturasController implements Serializable {
    
    @Inject DetalleServicioService detalleServicioService;
    @Inject EmisorService emisorService;
    @Inject FacturaService facturaService;
    @Inject LineaDetalleService lineaDetalleService;
    @Inject ReceptorService receptorService;
    @Inject ResumenFacturaService resumenFacturaService;
    @Inject SessionController currentSession;
    @Inject ArticulosController articuloController;
    @Inject InventarioController inventarioController;
    @Inject DepartamentoController departamentosController;
    @Inject ImpuestoService impuestoService;
    @Inject DescuentoService descuentoService;
    @Inject CodigoComercialService codigoComercialService;
    @Inject EncabezadoService encabezadoService;
    @Inject MedioPagoService medioPagoService;
    
    private List<UploadedFile> files;
    private List<ComprobanteFinal> facturas;
    private List<ComprobanteFinal> facturasDetalladas;
    private List<ComprobanteFinal> carritoCompras;
    private ComprobanteFinal newFactura;
    
    private ComprobanteFinal selectedFactura;
    private String facturaFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedFactura = new ComprobanteFinal();
        carritoCompras = new ArrayList<>();
    }
    
    public List<ComprobanteFinal> facturasList() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        return facturas;
    }
    
    public List<ComprobanteFinal> facturasListDetalladas() {
        if(facturasDetalladas == null){
            facturasDetalladas = facturaService.listAll();
        }
        return facturasDetalladas;
    }
    
    public long facturaCount() {
        return facturaService.count();
    }

    public void updateFactura() {
        if(currentSession.isValid()){
            facturaService.updateAndDisable(selectedFactura);
            clearFactura();        
        }
    }

    public void deleteFactura() {
        if (selectedFactura != null) {
            facturaService.softDelete(selectedFactura);
            clearFactura();
        }
    }

    public void clearFactura() {
        //new factura if it existed...
        selectedFactura = null;
    }
    
    public void clearCache(){
        facturas = null;
        facturasDetalladas = null;
    }

    public List<ComprobanteFinal> getFilteredFacturas() {
        if(facturas == null){
            facturas = facturaService.ListAllEnabled();
        }
        if (facturaFilter != null && !facturaFilter.isEmpty()) {
            return facturasList().stream()
                    .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return facturasList();
        }
    }
    
    public List<ComprobanteFinal> getFilteredFacturasDetallados() {
        try {
            if(facturasDetalladas == null){
            facturasDetalladas = facturaService.listAll();
            }
            if (facturaFilter != null && !facturaFilter.isEmpty()) {
                return facturasListDetalladas().stream()
                        .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                        .collect(Collectors.toList());
            } else {
                return facturasListDetalladas();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            return null;
        }
        
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ComprobanteFinal factura = (ComprobanteFinal) value;
        return factura.getEncabezado().getCodigoActividad().toLowerCase().contains(filterText)
                || factura.getEncabezado().getCondicionVenta().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getNombre().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getCorreoElectronico().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getIdentificacion().getNumero().toLowerCase().contains(filterText)
                || factura.getEncabezado().getEmisor().getNombreComercial().toLowerCase().contains(filterText)
                || factura.getEncabezado().getFechaEmision().toString().toLowerCase().contains(filterText)
                || factura.getEncabezado().getNumeroConsecutivo().toLowerCase().contains(filterText);
    }

    public ComprobanteFinal findFacturaById(Integer number) {
        return facturaService.findById(number);
    }
    
    public void addFile(UploadedFile file){
        if(files == null){
            files = new ArrayList<>();
        }
        files.add(file);
    }
    
    public void parseXMLFromUploadedFile(UploadedFile uploadedFile) {
        try {
            InputStream inputStream = uploadedFile.getInputStream();    
            parseXML(inputStream);
        } catch (IOException e) {
            System.out.println("Error" + e.getLocalizedMessage());
        }
    }
    
    public void processFacturas(){
        if(!files.isEmpty()){
            for (int i = 0; i < files.size(); i++) {
                parseXMLFromUploadedFile(files.get(i));
            }
            files.clear();
            clearCache();

            PrimeFaces.current().executeScript("PF('facturasUpload').hide();");            
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Se procesaron las facturas", null));
        }else{
            FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "No hay facturas por procesar!", null));
        }
    }
    
    public void parseXML(InputStream inputStream) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder xmlContent = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                xmlContent.append(line);
            }

            XmlMapper xmlMapper = new XmlMapper();
            JsonNode rootNode = xmlMapper.readTree(xmlContent.toString());

            String numeroConsecutivo = rootNode.path("NumeroConsecutivo").asText();
            
            String clave = rootNode.path("Clave").asText();
            
            if(facturaService.findByNumeroConsecutivo(numeroConsecutivo)){
                System.out.println("La Factura ya existe.");
                FacesMessage message = new FacesMessage("Factura Duplicada","Ya existe la factura: " + numeroConsecutivo);
                FacesContext.getCurrentInstance().addMessage(null, message);
                return;
            }
            
            Encabezado encabezado = new Encabezado();
            DetalleServicio detalles = new DetalleServicio();
            
            Emisor emisor = new Emisor(); 
            Receptor receptor = new Receptor();
            ResumenFactura resumenFactura = new ResumenFactura();
            
            emisor = parseEmisor(rootNode.path("Emisor"));
            if(emisor == null){
                return;
            }
            
            receptor = parseReceptor(rootNode.path("Receptor"));
            if(receptor == null){
                return;
            }
            
            String codigoActividad = rootNode.path("CodigoActividad").asText();
            
            LocalDateTime localDateTime = parseFechaEmision(rootNode.path("FechaEmision").asText());
            if(localDateTime == null){
                return;
            }
            
            String condicionVenta = rootNode.path("CondicionVenta").asText();
            String plazoCredito = rootNode.path("PlazoCredito").asText();
            
            List<MedioPago> medioPago = parseMedioPago(rootNode.path("MedioPago"), encabezado);
            if(medioPago == null){
                return;
            }

            resumenFactura = parseResumenFactura(rootNode.path("ResumenFactura"));
            if(resumenFactura == null){
                return;
            }
            
            Emisor persistedEmisor = emisorService.createIfNotExist(emisor);
            Receptor persistedReceptor = receptorService.createIfNotExist(receptor);
            List<LineaDetalle> lineas = parseDetalleServicio(rootNode.path("DetalleServicio"));
            if(lineas == null){
                return;
            }
            
            List<LineaDetalle> ServicioLineas = new ArrayList<>();
            
            for(LineaDetalle linea : lineas){
                ServicioLineas.add(linea);
            }
            
            detalles.setLineasDetalle(ServicioLineas);
            detalleServicioService.create(detalles);
            resumenFacturaService.create(resumenFactura);
            ComprobanteFinal factura = new ComprobanteFinal();
            if(persistedEmisor != null){
                encabezado.setEmisor(persistedEmisor);
            }
            if(persistedReceptor != null){
                encabezado.setReceptor(persistedReceptor);
            }
            
            
            encabezado.setCodigoActividad(codigoActividad);
            encabezado.setNumeroConsecutivo(numeroConsecutivo);
            encabezado.setFechaEmision(localDateTime);
            encabezado.setCondicionVenta(condicionVenta);
            encabezado.setPlazoCredito(plazoCredito);
            encabezado.setMedioPago(medioPago);
            encabezado.setClave(clave);
            
            encabezadoService.create(encabezado);
            
            factura.setEncabezado(encabezado);
            factura.setDetalles(detalles);
            factura.setResumen(resumenFactura);
            factura.setUser(currentSession.getCurrentUser());
            factura.setStatus(true);
            factura.setProcessed(false);
            
            facturaService.create(factura);
            
        FacesMessage message = new FacesMessage("Exito","Se proceso exitosamente la facturas: " + factura.getEncabezado().getNumeroConsecutivo());
        FacesContext.getCurrentInstance().addMessage(null, message);
            
        } catch (Exception e) {
            System.out.println("Error: " + e.getLocalizedMessage());
            System.out.println("Error ParsingXML to Object: " + e.getMessage());
        }
    }
    
    private LocalDateTime parseFechaEmision(String fechaEmision) {
        try {
            if (fechaEmision == null || fechaEmision.isEmpty()) {
                return null;
            }

            DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME
            };

            for (DateTimeFormatter formatter : formatters) {
                try {
                    return LocalDateTime.parse(fechaEmision, formatter);
                } catch (Exception ex) {
                    // Ignore and try the next format
                }
            }

            // If no format works, throw an exception
            return null;
        } catch (Exception e) {
            System.out.println("Error parsing fecha emision: " + e.getMessage());
            return null;
        }
    }

    private Emisor parseEmisor(JsonNode emisorNode) {
        try {
            String nombre = emisorNode.path("Nombre").asText();
            String identificacionTipo = emisorNode.path("Identificacion").path("Tipo").asText();
            String identificacionNumero = emisorNode.path("Identificacion").path("Numero").asText();
            String nombreComercial = emisorNode.path("NombreComercial").asText();
            Ubicacion ubicacion = new Ubicacion();
            Telefono telefono = new Telefono();
            Fax fax = new Fax();
            // Parse Ubicacion si existe
            if (!emisorNode.path("Ubicacion").isMissingNode()) { 
                ubicacion = parseUbicacion(emisorNode.path("Ubicacion"));
                if(ubicacion == null){
                    return null;
                }
            }
            // Parse Telefono si existe
            if (!emisorNode.path("Telefono").isMissingNode()) {
                telefono = parseTelefono(emisorNode.path("Telefono"));
                if(telefono == null){
                    return null;
                }
            }
            // Parse Fax si existe
            if (!emisorNode.path("Fax").isMissingNode()) {
                fax = parseFax(emisorNode.path("Fax"));
                if(fax == null){
                    return null;
                }
            }
            String correoElectronico = emisorNode.path("CorreoElectronico").asText();

            Emisor emisor = new Emisor();
            emisor.setNombre(nombre);

            IdentificacionEmisor idEmisor = new IdentificacionEmisor();
            idEmisor.setNumero(identificacionNumero);
            idEmisor.setTipo(identificacionTipo);

            emisor.setIdentificacion(idEmisor);
            emisor.setNombreComercial(nombreComercial);
            emisor.setUbicacion(ubicacion);
            emisor.setTelefono(telefono);
            emisor.setFax(fax);
            emisor.setCorreoElectronico(correoElectronico);

            return emisor;
        } catch (Exception e) {
            System.out.println("Error Parsing Emisor: " + e.getLocalizedMessage());
            return null;
        }
    }

    private Receptor parseReceptor(JsonNode receptorNode) {
        try {
            String nombre = receptorNode.path("Nombre").asText();
            String identificacionTipo = receptorNode.path("Identificacion").path("Tipo").asText();
            String identeficacionNumero = receptorNode.path("Identificacion").path("Numero").asText();
            String nombreComercial = receptorNode.path("NombreComercial").asText();
            Ubicacion ubicacion = new Ubicacion();
            Telefono telefono = new Telefono();
            Fax fax = new Fax();

            if (!receptorNode.path("Ubicacion").isMissingNode()) {
                ubicacion = parseUbicacion(receptorNode.path("Ubicacion"));
            }
            if (!receptorNode.path("Telefono").isMissingNode()) {
                telefono = parseTelefono(receptorNode.path("Telefono"));
            }
            if (!receptorNode.path("Fax").isMissingNode()) {
                fax = parseFax(receptorNode.path("Fax"));
            }
            
            String correoElectronico = receptorNode.path("CorreoElectronico").asText();

            Receptor receptor = new Receptor();
            receptor.setNombre(nombre);

            IdentificacionReceptor idReceptor = new IdentificacionReceptor();
            idReceptor.setNumero(identeficacionNumero);
            idReceptor.setTipo(identificacionTipo);

            receptor.setIdentificacion(idReceptor);
            receptor.setNombreComercial(nombreComercial);
            receptor.setUbicacion(ubicacion);
            receptor.setTelefono(telefono);
            receptor.setFax(fax);
            receptor.setCorreoElectronico(correoElectronico);

            return receptor;
        } catch (Exception e) {
            System.out.println("Error parsing receptor: " + e.getLocalizedMessage());
            return null;
        }
    }

    private Ubicacion parseUbicacion(JsonNode ubicacionNode) {
        try {
        String provincia = ubicacionNode.path("Provincia").asText();
        String Canton = ubicacionNode.path("Canton").asText();
        String Distrito = ubicacionNode.path("Distrito").asText();
        String Barrio = ubicacionNode.path("Barrio").asText();
        String OtrasSenas = ubicacionNode.path("OtrasSenas").asText();
        
        Ubicacion parsedUbicacion = new Ubicacion();
        parsedUbicacion.setProvincia(provincia);
        parsedUbicacion.setCanton(Canton);
        parsedUbicacion.setDistrito(Distrito);
        parsedUbicacion.setBarrio(Barrio);
        parsedUbicacion.setOtrasSenas(OtrasSenas);
        
        return parsedUbicacion;
            
        } catch (Exception e) {
            System.out.println("Error parsing ubicacion: " + e.getLocalizedMessage());
            return null;
        }
    }

    private Telefono parseTelefono(JsonNode telefonoNode) {
        try {
            String codigoPais = telefonoNode.path("CodigoPais").asText();
            String numTelefono = telefonoNode.path("NumTelefono").asText();

            Telefono telefono = new Telefono();
            telefono.setCodigoPais(Integer.valueOf(codigoPais));
            telefono.setNumeroTelefono(Integer.valueOf(numTelefono));

            return telefono;
        } catch (Exception e) {
            System.out.println("Error parsing telefono: " + e.getLocalizedMessage());
            return null;
        }
    }

    private Fax parseFax(JsonNode faxNode) {        
        try {
            String codigoPais = faxNode.path("CodigoPais").asText();
            String numTelefono = faxNode.path("NumFax").asText();

            Fax fax = new Fax();

            if (!codigoPais.isEmpty()) {
                fax.setCodigoPais(Integer.valueOf(codigoPais));
            } else {
                fax.setCodigoPais(null); // or set to a default value, if applicable
            }

            if (!numTelefono.isEmpty()) {
                fax.setNumeroFax(Integer.valueOf(numTelefono));
            } else {
                fax.setNumeroFax(null); // or set to a default value, if applicable
            }

            return fax;
        } catch (Exception e) {
            System.out.println("Error parsing fax: " + e.getLocalizedMessage());
            return null;
        }
    }

    
    private List<LineaDetalle> parseDetalleServicio(JsonNode detalleServicio) {
        try {
            List<LineaDetalle> lineasDetalle = new ArrayList<>();

            JsonNode lineasDetalleNode = detalleServicio.path("LineaDetalle");
            if (lineasDetalleNode.isArray()) {
                for (JsonNode lineaDetalleNode : lineasDetalleNode) {
                    LineaDetalle lineaDetalle = parseLineaDetalle(lineaDetalleNode);
                    if(lineaDetalle != null){
                        lineasDetalle.add(lineaDetalle);
                    }
                }
            }else if(!lineasDetalleNode.isMissingNode()){
                LineaDetalle lineaDetalle = parseLineaDetalle(lineasDetalleNode);
                if(lineaDetalle != null){
                    lineasDetalle.add(lineaDetalle);
                }
            }

            return lineasDetalle;
        } catch (Exception e) {
            System.out.println("Error parsing detalle Servicio: " + e.getLocalizedMessage());
            return null;
        }
        
    }
    
    private List<MedioPago> parseMedioPago(JsonNode medioPagoNode, Encabezado comprobante){
        try {
            List<MedioPago> mediosPago = new ArrayList<>();

            if(medioPagoNode.isArray()){
                for(JsonNode medioPago : medioPagoNode){
                    var medioEnum = MedioPagoEnum.fromCodigo(medioPago.asText());
                    MedioPago medioDePago = new MedioPago();
                    medioDePago.setMedioPago(medioEnum.getCodigo());
                    medioDePago.setComprobante(comprobante);
                    mediosPago.add(medioDePago);
                }
            }else if(!medioPagoNode.isMissingNode()){
                var medioEnum = MedioPagoEnum.fromCodigo(medioPagoNode.asText());
                    MedioPago medioDePago = new MedioPago();
                    medioDePago.setMedioPago(medioEnum.getCodigo());
                    medioDePago.setComprobante(comprobante);
                    mediosPago.add(medioDePago);
            }

            return mediosPago;
        } catch (Exception e) {
            System.out.println("Error parsing medio pago: " + e.getLocalizedMessage());
            return null;
        }
    }
        
    
    private LineaDetalle parseLineaDetalle(JsonNode lineaDetalleNode){
        try {
            LineaDetalle lineaDetalle = new LineaDetalle();

            int numeroLinea = lineaDetalleNode.path("NumeroLinea").asInt();
            String codigo = lineaDetalleNode.path("Codigo").asText();

            List<CodigoComercial> codigosComerciales = new ArrayList<>();
            // Parse multiple CodigoComercial if present
            if (lineaDetalleNode.path("CodigoComercial").isArray()) {
                for (JsonNode codigoComercialNode : lineaDetalleNode.path("CodigoComercial")) {
                    CodigoComercial codigoComercial = new CodigoComercial();
                    codigoComercial = parseCodigoComercial(codigoComercialNode);
                    if(codigoComercial != null){
                        codigosComerciales.add(codigoComercial);
                    }
                }
            } else if (!lineaDetalleNode.path("CodigoComercial").isMissingNode()) {
                CodigoComercial codigoComercial = new CodigoComercial();
                codigoComercial = parseCodigoComercial(lineaDetalleNode.path("CodigoComercial"));
                if(codigoComercial != null){
                    codigosComerciales.add(codigoComercial);
                }
            }

            String cantidad = lineaDetalleNode.path("Cantidad").asText();
            String unidadMedida = lineaDetalleNode.path("UnidadMedida").asText();
            String unidadMedidaComercial = lineaDetalleNode.path("UnidadMedidaComercial").asText();
            String detalle = lineaDetalleNode.path("Detalle").asText();
            String precioUnitario =  lineaDetalleNode.path("PrecioUnitario").asText();
            String montoTotal = lineaDetalleNode.path("MontoTotal").asText();
            String subTotal =  lineaDetalleNode.path("SubTotal").asText();
            List<Impuesto> impuestos = new ArrayList<>();
            List<Descuento> descuentos = new ArrayList<>();

            // Parse Impuesto if present
            if (lineaDetalleNode.path("Impuesto").isArray()) {
                for(JsonNode ImpuestoNode : lineaDetalleNode.path("Impuesto")){
                    Impuesto impuesto = new Impuesto();
                    impuesto = parseImpuesto(ImpuestoNode);
                    if(impuesto != null){
                        impuestos.add(impuesto);
                    }
                }
            }else if(!lineaDetalleNode.path("Impuesto").isMissingNode()){
                Impuesto impuesto = new Impuesto();
                impuesto = parseImpuesto(lineaDetalleNode.path("Impuesto"));
                if(impuesto != null){
                    impuestos.add(impuesto);
                }
            }

            // Parse Descuento if present
            if (lineaDetalleNode.path("Descuento").isArray()) {
                for (JsonNode DescuentoNode : lineaDetalleNode.path("Descuento")){
                    Descuento descuento = new Descuento();
                    descuento = parseDescuento(DescuentoNode);
                    if(descuento != null){
                        descuentos.add(descuento); 
                    }
                }
            } else if (!lineaDetalleNode.path("Descuento").isMissingNode()){
                Descuento descuento = new Descuento();
                descuento = parseDescuento(lineaDetalleNode.path("Descuento"));
                if(descuento != null){
                    descuentos.add(descuento); 
                }
            }
            
            for (CodigoComercial codigoComercial : codigosComerciales){
                codigoComercialService.create(codigoComercial);
            }
            
            for (Impuesto impuesto : impuestos) {
                impuestoService.create(impuesto);
            }
            
            for (Descuento descuento : descuentos){
                descuentoService.create(descuento);
            }
            

            String montoTotalLinea = lineaDetalleNode.path("MontoTotalLinea").asText();

            lineaDetalle.setNumeroLinea(numeroLinea);
            lineaDetalle.setCodigoCabys(codigo);
            lineaDetalle.setCodigosComerciales(codigosComerciales);
            lineaDetalle.setCantidad(new BigDecimal(cantidad));
            lineaDetalle.setUnidadMedida(unidadMedida);
            lineaDetalle.setUnidadMedidaComercial(unidadMedidaComercial);
            lineaDetalle.setDetalle(detalle);
            lineaDetalle.setPrecioUnitario(new BigDecimal(precioUnitario));
            lineaDetalle.setMontoTotal(new BigDecimal(montoTotal));
            lineaDetalle.setDescuentos(descuentos);
            lineaDetalle.setSubTotal(new BigDecimal(subTotal));
            lineaDetalle.setImpuestos(impuestos);
            lineaDetalle.setMontoTotalLinea(new BigDecimal(montoTotalLinea));

            return lineaDetalle;
        } catch (Exception e) {
            System.out.println("Error parsing linea detalle: " + e.getLocalizedMessage());
            return null;
        }
        
    }


    private CodigoComercial parseCodigoComercial(JsonNode codigoComercialNode) {
        try {
            String tipo = codigoComercialNode.path("Tipo").asText();
            String codigo = codigoComercialNode.path("Codigo").asText();

            CodigoComercial codigoComercial = new CodigoComercial();
            codigoComercial.setTipo(tipo);
            codigoComercial.setCodigo(codigo);

            return codigoComercial;
        } catch (Exception e) {
            System.out.println("Error parsing codigo comercial: " + e.getLocalizedMessage());
            return null;
        }
        
    }


    private Impuesto parseImpuesto(JsonNode impuestoNode) {
        try {
            String codigo = impuestoNode.path("Codigo").asText();
            String codigoTarifa = impuestoNode.path("CodigoTarifa").asText();
            String tarifa = impuestoNode.path("Tarifa").asText();
            String monto = impuestoNode.path("Monto").asText();

            Impuesto impuesto = new Impuesto();
            impuesto.setCodigo(codigo);
            impuesto.setCodigoTarifa(codigoTarifa);
            impuesto.setTarifa(new BigDecimal(tarifa));
            impuesto.setMonto(new BigDecimal(monto));

            return impuesto;
        } catch (Exception e) {
            System.out.println("Error parsing impuestos: " + e.getLocalizedMessage());
            return null;
        }
        
    }

    private ResumenFactura parseResumenFactura(JsonNode resumenFacturaNode) {
    try {
            JsonNode codigoMonedaPath = resumenFacturaNode.path("CodigoTipoMoneda");
            String codigoMonedaText = codigoMonedaPath.path("CodigoMoneda").asText();
            String tipoCambioText = codigoMonedaPath.path("TipoCambio").asText();

            CodigoTipoMoneda codigoMoneda = parseCodigoMoneda(codigoMonedaText, tipoCambioText);
            if(codigoMoneda  == null){
                System.out.println("Error parsing moneda");
                return null;
            }

            // Extracting values
            String totalServiciosGravados = resumenFacturaNode.path("TotalServGravados").asText();
            String totalServiciosExentos = resumenFacturaNode.path("TotalServExentos").asText();
            String totalServiciosExonerados = resumenFacturaNode.path("TotalServExonerado").asText();
            String totalMercanciasGravadas = resumenFacturaNode.path("TotalMercanciasGravadas").asText();
            String totalMercanciasExentas = resumenFacturaNode.path("TotalMercanciasExentas").asText();
            String totalMercanciaExonerada = resumenFacturaNode.path("TotalMercExonerada").asText();
            String totalGravado = resumenFacturaNode.path("TotalGravado").asText();
            String totalExento = resumenFacturaNode.path("TotalExento").asText();
            String totalExonerado = resumenFacturaNode.path("TotalExonerado").asText();
            String totalVenta = resumenFacturaNode.path("TotalVenta").asText();
            String totalDescuentos = resumenFacturaNode.path("TotalDescuentos").asText();
            String totalVentaNeta = resumenFacturaNode.path("TotalVentaNeta").asText();
            String totalImpuesto = resumenFacturaNode.path("TotalImpuesto").asText();
            String totalIVADevuelto = resumenFacturaNode.path("TotalIVADevuelto").asText();
            String totalOtrosCargos = resumenFacturaNode.path("TotalOtrosCargos").asText();
            String totalComprobante = resumenFacturaNode.path("TotalComprobante").asText();

            // Check for null or empty values before converting to BigDecimal
            BigDecimal bigDecimalTotalServiciosGravados = totalServiciosGravados.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServiciosGravados);
            BigDecimal bigDecimalTotalServiciosExentos = totalServiciosExentos.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServiciosExentos);
            BigDecimal bigDecimalTotalServiciosExonerados = totalServiciosExonerados.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalServiciosExonerados);
            BigDecimal bigDecimalTotalMercanciasGravadas = totalMercanciasGravadas.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciasGravadas);
            BigDecimal bigDecimalTotalMercanciasExentas = totalMercanciasExentas.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciasExentas);
            BigDecimal bigDecimalTotalMercanciaExonerada = totalMercanciaExonerada.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalMercanciaExonerada);
            BigDecimal bigDecimalTotalGravado = totalGravado.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalGravado);
            BigDecimal bigDecimalTotalExento = totalExento.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalExento);
            BigDecimal bigDecimalTotalExonerado = totalExonerado.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalExonerado);
            BigDecimal bigDecimalTotalVenta = totalVenta.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalVenta);
            BigDecimal bigDecimalTotalDescuentos = totalDescuentos.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalDescuentos);
            BigDecimal bigDecimalTotalVentaNeta = totalVentaNeta.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalVentaNeta);
            BigDecimal bigDecimalTotalImpuesto = totalImpuesto.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalImpuesto);
            BigDecimal bigDecimalTotalIVADevuelto = totalIVADevuelto.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalIVADevuelto);
            BigDecimal bigDecimalTotalOtrosCargos = totalOtrosCargos.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalOtrosCargos);
            BigDecimal bigDecimalTotalComprobante = totalComprobante.isEmpty() ? BigDecimal.ZERO : new BigDecimal(totalComprobante);

            ResumenFactura resumenFactura = new ResumenFactura();

            resumenFactura.setCodigoMoneda(codigoMoneda);
            resumenFactura.setTotalServGravados(bigDecimalTotalServiciosGravados);
            resumenFactura.setTotalServExentos(bigDecimalTotalServiciosExentos);
            resumenFactura.setTotalServExonerado(bigDecimalTotalServiciosExonerados);
            resumenFactura.setTotalMercanciasGravadas(bigDecimalTotalMercanciasGravadas);
            resumenFactura.setTotalMercanciasExentas(bigDecimalTotalMercanciasExentas);
            resumenFactura.setTotalMercExonerada(bigDecimalTotalMercanciaExonerada);
            resumenFactura.setTotalGravado(bigDecimalTotalGravado);
            resumenFactura.setTotalExento(bigDecimalTotalExento);
            resumenFactura.setTotalExonerado(bigDecimalTotalExonerado);
            resumenFactura.setTotalVenta(bigDecimalTotalVenta);
            resumenFactura.setTotalDescuentos(bigDecimalTotalDescuentos);
            resumenFactura.setTotalVentaNeta(bigDecimalTotalVentaNeta);
            resumenFactura.setTotalImpuesto(bigDecimalTotalImpuesto);
            resumenFactura.setTotalIVADevuelto(bigDecimalTotalIVADevuelto);
            resumenFactura.setTotalOtrosCargos(bigDecimalTotalOtrosCargos);
            resumenFactura.setTotalComprobante(bigDecimalTotalComprobante);

            return resumenFactura;

        } catch (Exception e) {
            System.out.println("Error parsing resumen factura: " + e.getLocalizedMessage());
            return null;
        }
    }

    
    private CodigoTipoMoneda parseCodigoMoneda(String codigo, String tipo){
        try {
            CodigoTipoMoneda codigoMoneda = new CodigoTipoMoneda();
            codigoMoneda.setCodigoMoneda(codigo);
            codigoMoneda.setTipoCambioMoneda(new BigDecimal(tipo));

            return codigoMoneda;
        } catch (Exception e) {
            System.out.println("Error parsing codigo moneda: " + e.getLocalizedMessage());
            return null;
        }
        
    }

    private Descuento parseDescuento(JsonNode descuentoNode) {
        try {
            String montoDescuento = descuentoNode.path("MontoDescuento").asText();
            String naturalezaDescuento = descuentoNode.path("NaturalezaDescuento").asText();

            Descuento descuento = new Descuento();
            descuento.setMontoDescuento(new BigDecimal(montoDescuento));
            descuento.setNaturalezaDescuento(naturalezaDescuento);

            return descuento;
        } catch (Exception e) {
            System.out.println("Error parsing descuento: " + e.getLocalizedMessage());
            return null;
        }
    }
    
    public void processSelectedFactura(){
        if(selectedFactura != null){
            if(!selectedFactura.getProcessed() && selectedFactura.getStatus()){
                processFactura(selectedFactura);
                FacesMessage message = new FacesMessage("Exito","Se procesaron los articulos de la factura!");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }else{
                FacesMessage message = new FacesMessage("Oops!","La factura ya fue procesada.");
                FacesContext.getCurrentInstance().addMessage(null, message);
            }
        }else{
            FacesMessage message = new FacesMessage("Error","No hay una factura seleccionada");
            FacesContext.getCurrentInstance().addMessage(null, message);
        }
    }
        
    private void processFactura(ComprobanteFinal factura){
        try {
            List<LineaDetalle> lineasDetalle = factura.getDetalles().getLineasDetalle();
                        
            for(LineaDetalle lineaDetalle : lineasDetalle){
                String codigoBarra = "";
                String nombre = lineaDetalle.getDetalle();
                List<CodigoComercial> codigosComercialesLineaDetalle = lineaDetalle.getCodigosComerciales();

                for(CodigoComercial codigoComercial : codigosComercialesLineaDetalle){
                    if(codigoComercial.getTipo().contains("03")){
                        codigoBarra = codigoComercial.getCodigo();
                        break;
                    }
                }
                
                Articulos articuloExistente = (codigoBarra.isEmpty()) ?
                        articuloController.findArticuloByName(nombre) :
                        articuloController.findArticuloByBarCode(codigoBarra);
                
                var cantidad = lineaDetalle.getCantidad();
                String codigoCabys = lineaDetalle.getCodigoCabys();
                String unidadMedida = lineaDetalle.getUnidadMedida();
                String unidadMedidaComercial = lineaDetalle.getUnidadMedidaComercial();
                var montoTotalLinea = lineaDetalle.getMontoTotalLinea();
                var totalUnitario = montoTotalLinea.divide(cantidad,20,RoundingMode.HALF_UP);
                var precioUnitario = totalUnitario;
                var UnidadesParseadas = parseUnidadComercial(unidadMedida, unidadMedidaComercial) * cantidad.doubleValue();
                
                Articulos articulo = new Articulos();
                
                Departamento departamento = new Departamento();
                    departamento.setNombre(factura.getEncabezado().getEmisor().getNombre());
                    departamento.setStatus(true);
                    departamento.setUsuario(currentSession.getCurrentUser());
                    Departamento persistedDepartamento = departamentosController.createSimpleDepartamento(departamento);
                
                if(articuloExistente == null){
                    articulo.setNombre(nombre);
                    articulo.setCodigoBarra(codigoBarra);
                    articulo.setRecomendacionCabys(codigoCabys);
                    articulo.setDepartamento(persistedDepartamento);
                    articulo.setUnidadMedida(unidadMedida);
                    articulo.setUnidadMedidaComercial(unidadMedidaComercial);
                    articulo.setPrecioCostoSinIVA(precioUnitario);
                    articulo.setUsuario(currentSession.getCurrentUser());
                    articulo.setStatus(true);
                    articulo.setProcessed(false);
                    articuloController.createSimpleArticulo(articulo);
                }else{
                    articuloExistente.setRecomendacionCabys(codigoCabys);
                    articuloExistente.setPrecioCostoSinIVA(precioUnitario);
                    articuloExistente.setUsuario(currentSession.getCurrentUser());
                    articuloExistente.setUnidadMedida(unidadMedida);
                    articuloExistente.setUnidadMedidaComercial(unidadMedidaComercial);
                    articuloExistente.setStatus(true);
                    articuloExistente.setProcessed(false);
                    articuloExistente.setDepartamento(persistedDepartamento);
                    articuloExistente.setPrecioFinal(new BigDecimal(0));
                    articuloExistente.setPrecioCostoConIVA(new BigDecimal(0));
                    articuloController.updateSimpleArticulo(articuloExistente);
                }
                
                Inventario ajusteArticulo = new Inventario();
                
                if(articuloExistente != null){
                    ajusteArticulo.setArticulo(articuloExistente);
                }else{
                    ajusteArticulo.setArticulo(articulo);
                }
                ajusteArticulo.setUnidadesRecomendadasFactura(UnidadesParseadas);
                ajusteArticulo.setUsuario(currentSession.getCurrentUser());
                ajusteArticulo.setFechaMovimiento(new Date());
                ajusteArticulo.setTipoMovimiento("Automatico");
                ajusteArticulo.setStatus(true);
                ajusteArticulo.setProcessed(false);
                ajusteArticulo.setCantidad(cantidad);
                ajusteArticulo.setNotas((cantidad.doubleValue() != 0) ? "Auto procesado por el sistema" : "No se pudo auto adquirir la cantidad");                
                
                inventarioController.createSimpleInventario(ajusteArticulo);
            }
            
            factura.setProcessed(true);
            facturaService.update(factura);
            clearCache();
            
        } catch (Exception e) {
            System.out.println("Error procesing factura: " + e.getLocalizedMessage());
        }
    }
    
    private double parseUnidadComercial(String unidad, String unidadComercial) {
        return switch (unidad) {
            case "Otros" -> parseUnidadComercial(unidadComercial);
            case "Unid" -> parseUnidadComercial(unidadComercial);
            case "g" -> parseUnidadComercial(unidadComercial);
            default -> 0;
        }; // Default to 0 if no specific conversion found
    }

    private double parseUnidadComercial(String unidadComercial) {
        return switch (unidadComercial) {
            case "BOT" -> 1;
            case "LT" -> 1;
            case "ST" -> 6;
            case "Pieza" -> 1;
            case "UN" -> 1;
            case "Unid" -> 1;
            case "UND" -> 1;
            case "" -> 1;
            default -> 0;
        }; // Default to 0 if no conversion defined
    }
    
    public void cancel(){
        System.out.println("Cajero: " + currentSession.getCurrentUser().getUsername() + "Cancelo Factura");
    }
    
    public void openNewFactura(){
        newFactura = new ComprobanteFinal();
    }
    
}
