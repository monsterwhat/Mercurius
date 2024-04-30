package Controllers;

import Models.Facturas.*;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
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
    @Inject ViewController viewManager;
    
    private List<UploadedFile> files;
    private List<Factura> facturas;
    
    private Factura selectedFactura;
    private String facturaFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
        facturas = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedFactura = new Factura();
        updateFacturas();
    }
    
    public List<Factura> facturasList() {
        if (facturas == null) {
            facturas = facturaService.ListAllEnabled();
        }
        return facturas;
    }
    
    public void updateFacturas(){
        facturas = facturaService.ListAllEnabled();
    }
    
    public List<Factura> facturasListAll() {
        return facturaService.listAll();
    }

    public long facturaCount() {
        return facturaService.count();
    }

    public void updateFactura() {
        if(currentSession.isValid()){
        facturaService.updateAndDisable(selectedFactura);
        clearSelectedFactura();        
        }
    }

    public void deleteFactura() {
        if (selectedFactura != null) {
            facturaService.softDelete(selectedFactura);
            clearSelectedFactura();
        }
    }

    public void clearSelectedFactura() {
        facturas = null;
        selectedFactura = null;
        viewManager.selectViewFacturas();
    }

    public List<Factura> getFilteredFacturas() {
        if (facturaFilter != null && !facturaFilter.isEmpty()) {
            return facturasList().stream()
                    .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return facturasList();
        }
    }
    
    public List<Factura> getFilteredFacturasDetallados() {
        if (facturaFilter != null && !facturaFilter.isEmpty()) {
            return facturasListAll().stream()
                    .filter(factura -> globalFilterFunction(factura, facturaFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return facturasListAll();
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Factura factura = (Factura) value;
        return factura.getCodigoActividad().toLowerCase().contains(filterText)
                || factura.getCondicionVenta().toLowerCase().contains(filterText)
                || factura.getEmisor().getNombre().toLowerCase().contains(filterText)
                || factura.getEmisor().getCorreoElectronico().toLowerCase().contains(filterText)
                || factura.getEmisor().getIdentificacionNumero().toLowerCase().contains(filterText)
                || factura.getEmisor().getNombreComercial().toLowerCase().contains(filterText)
                || factura.getFechaEmision().toLowerCase().contains(filterText)
                || factura.getNumeroConsecutivo().toLowerCase().contains(filterText);
    }

    public Factura findFacturaById(Integer number) {
        
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
        for (int i = 0; i < files.size(); i++) {
            parseXMLFromUploadedFile(files.get(i));
        }
        files.clear();
        updateFacturas();
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
            
            if(facturaService.findByNumeroConsecutivo(numeroConsecutivo)){
                System.out.println("La Factura ya existe.");
                FacesMessage message = new FacesMessage("Factura Duplicada","Ya existe la factura: " + numeroConsecutivo);
                FacesContext.getCurrentInstance().addMessage(null, message);
                return;
            }
            
            Emisor emisor = new Emisor(); 
            Receptor receptor = new Receptor(); 
            DetalleServicio detalleServicio = new DetalleServicio();
            detalleServicio.setEnabled(true);
            ResumenFactura resumenFactura = new ResumenFactura();
            
            emisor = parseEmisor(rootNode.path("Emisor"));
            receptor = parseReceptor(rootNode.path("Receptor"));
            
            String codigoActividad = rootNode.path("CodigoActividad").asText();
            String fechaEmision = rootNode.path("FechaEmision").asText();

            String condicionVenta = rootNode.path("CondicionVenta").asText();
            String plazoCredito = rootNode.path("PlazoCredito").asText();
            String medioPago = rootNode.path("MedioPago").asText();       

            resumenFactura = parseResumenFactura(rootNode.path("ResumenFactura"));
            
            Emisor foundEmisor = emisorService.createIfNotExist(emisor);
            Receptor foundReceptor = receptorService.createIfNotExist(receptor);
            List<LineaDetalle> lineas = parseDetalleServicio(rootNode.path("DetalleServicio"));
            List<LineaDetalle> ServicioLineas = new ArrayList<>();
            
            for(LineaDetalle linea : lineas){
                linea.setDetalleServicio(detalleServicio);
                ServicioLineas.add(linea);
            }
            
            detalleServicio.setLineasDetalle(ServicioLineas);
            detalleServicioService.create(detalleServicio);
            resumenFacturaService.create(resumenFactura);
            Factura factura = new Factura();
            if(foundEmisor != null){
                factura.setEmisor(foundEmisor);
            }else{
                factura.setEmisor(emisor);
            }
            if(foundReceptor != null){
                factura.setReceptor(foundReceptor);
            }else{
                factura.setReceptor(receptor);
            }
            factura.setCodigoActividad(codigoActividad);
            factura.setNumeroConsecutivo(numeroConsecutivo);
            factura.setFechaEmision(fechaEmision);
            factura.setCondicionVenta(condicionVenta);
            factura.setPlazoCredito(plazoCredito);
            factura.setMedioPago(medioPago);
            factura.setDetalleServicio(detalleServicio);
            factura.setResumenFactura(resumenFactura);
            factura.setUser(currentSession.getCurrentUser());
            factura.setStatus(true);
            
            facturaService.create(factura);
            
        FacesMessage message = new FacesMessage("Exito","Se proceso exitosamente la facturas: " + factura.getNumeroConsecutivo());
        FacesContext.getCurrentInstance().addMessage(null, message);
            
        } catch (Exception e) {
            System.out.println("Error ParsingXML to Object: " + e.getMessage());
        }
    }

    private Emisor parseEmisor(JsonNode emisorNode) {
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
        }
        // Parse Telefono si existe
        if (!emisorNode.path("Telefono").isMissingNode()) {
            telefono = parseTelefono(emisorNode.path("Telefono"));
        }
        // Parse Fax si existe
        if (!emisorNode.path("Fax").isMissingNode()) {
            fax = parseFax(emisorNode.path("Fax"));
        }
        String correoElectronico = emisorNode.path("CorreoElectronico").asText();
        
        Emisor emisor = new Emisor();
        emisor.setNombre(nombre);
        emisor.setIdentificacionTipo(identificacionTipo);
        emisor.setIdentificacionNumero(identificacionNumero);
        emisor.setNombreComercial(nombreComercial);
        emisor.setUbicacion(ubicacion);
        emisor.setTelefono(telefono);
        emisor.setFax(fax);
        emisor.setCorreoElectronico(correoElectronico);
        
        return emisor;
    }

    private Receptor parseReceptor(JsonNode receptorNode) {
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
        receptor.setIdentificacionTipo(identificacionTipo);
        receptor.setIdentificacionNumero(identeficacionNumero);
        receptor.setNombreComercial(nombreComercial);
        receptor.setUbicacion(ubicacion);
        receptor.setTelefono(telefono);
        receptor.setFax(fax);
        receptor.setCorreoElectronico(correoElectronico);
        
        return receptor;
    }

    private Ubicacion parseUbicacion(JsonNode ubicacionNode) {
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
    }

    private Telefono parseTelefono(JsonNode telefonoNode) {
        String codigoPais = telefonoNode.path("CodigoPais").asText();
        String numTelefono = telefonoNode.path("NumTelefono").asText();
        
        Telefono telefono = new Telefono();
        telefono.setCodigoPaisTelefono(codigoPais);
        telefono.setNumTelefono(numTelefono);
        
        return telefono;
    }

    private Fax parseFax(JsonNode faxNode) {        
        String codigoPais = faxNode.path("CodigoPais").asText();
        String numTelefono = faxNode.path("NumFax").asText();
        
        Fax fax = new Fax();
        fax.setCodigoPaisFax(codigoPais);
        fax.setNumFax(numTelefono);
        
        return fax;
    }
    
    private List<LineaDetalle> parseDetalleServicio(JsonNode detalleServicio) {
        List<LineaDetalle> lineasDetalle = new ArrayList<>();

        JsonNode lineasDetalleNode = detalleServicio.path("LineaDetalle");
        if (lineasDetalleNode.isArray()) {
            for (JsonNode lineaDetalleNode : lineasDetalleNode) {
                LineaDetalle lineaDetalle = parseLineaDetalle(lineaDetalleNode);
                lineasDetalle.add(lineaDetalle);
            }
        }else if(!lineasDetalleNode.isMissingNode()){
            LineaDetalle lineaDetalle = parseLineaDetalle(lineasDetalleNode);
            lineasDetalle.add(lineaDetalle);
        }

        return lineasDetalle;
    }
        
    
    private LineaDetalle parseLineaDetalle(JsonNode lineaDetalleNode){
        LineaDetalle lineaDetalle = new LineaDetalle();

        int numeroLinea = lineaDetalleNode.path("NumeroLinea").asInt();
        String codigo = lineaDetalleNode.path("Codigo").asText();

        List<CodigoComercial> codigosComerciales = new ArrayList<>();
        // Parse multiple CodigoComercial if present
        if (lineaDetalleNode.path("CodigoComercial").isArray()) {
            for (JsonNode codigoComercialNode : lineaDetalleNode.path("CodigoComercial")) {
                CodigoComercial codigoComercial = new CodigoComercial();
                codigoComercial = parseCodigoComercial(codigoComercialNode);
                codigosComerciales.add(codigoComercial);
            }
        } else if (!lineaDetalleNode.path("CodigoComercial").isMissingNode()) {
            CodigoComercial codigoComercial = new CodigoComercial();
            codigoComercial = parseCodigoComercial(lineaDetalleNode.path("CodigoComercial"));
            codigosComerciales.add(codigoComercial);
        }

        String cantidad = lineaDetalleNode.path("Cantidad").asText();
        String unidadMedida = lineaDetalleNode.path("UnidadMedida").asText();
        String unidadMedidaComercial = lineaDetalleNode.path("UnidadMedidaComercial").asText();
        String detalle = lineaDetalleNode.path("Detalle").asText();
        String precioUnitario =  lineaDetalleNode.path("PrecioUnitario").asText();
        String montoTotal = lineaDetalleNode.path("MontoTotal").asText();
        String subTotal =  lineaDetalleNode.path("SubTotal").asText();
        Impuesto impuesto = new Impuesto();
        List<Descuento> descuentos = new ArrayList<>();

        // Parse Impuesto if present
        if (!lineaDetalleNode.path("Impuesto").isMissingNode()) {
            impuesto = parseImpuesto(lineaDetalleNode.path("Impuesto"));
        }

        // Parse Descuento if present
        if (lineaDetalleNode.path("Descuento").isArray()) {
            for (JsonNode DescuentoNode : lineaDetalleNode.path("Descuento")){
                Descuento descuento = new Descuento();
                descuento = parseDescuento(DescuentoNode);
                descuentos.add(descuento);
            }
        } else if (!lineaDetalleNode.path("Descuento").isMissingNode()){
            Descuento descuento = new Descuento();
            descuento = parseDescuento(lineaDetalleNode.path("Descuento"));
            descuentos.add(descuento);
        }

        String montoTotalLinea = lineaDetalleNode.path("MontoTotalLinea").asText();

        lineaDetalle.setNumeroLinea(numeroLinea);
        lineaDetalle.setCodigo(codigo);
        lineaDetalle.setCodigosComerciales(codigosComerciales);
        lineaDetalle.setCantidad(cantidad);
        lineaDetalle.setUnidadMedida(unidadMedida);
        lineaDetalle.setUnidadMedidaComercial(unidadMedidaComercial);
        lineaDetalle.setDetalle(detalle);
        lineaDetalle.setPrecioUnitario(precioUnitario);
        lineaDetalle.setMontoTotal(montoTotal);
        lineaDetalle.setDescuentos(descuentos);
        lineaDetalle.setSubTotal(subTotal);
        lineaDetalle.setImpuesto(impuesto);
        lineaDetalle.setMontoTotalLinea(montoTotalLinea);
                
        return lineaDetalle;
    }


    private CodigoComercial parseCodigoComercial(JsonNode codigoComercialNode) {
        String tipo = codigoComercialNode.path("Tipo").asText();
        String codigo = codigoComercialNode.path("Codigo").asText();
        
        CodigoComercial codigoComercial = new CodigoComercial();
        codigoComercial.setTipoCodigo(tipo);
        codigoComercial.setCodigoComercial(codigo);
        
        return codigoComercial;
    }


    private Impuesto parseImpuesto(JsonNode impuestoNode) {
        String codigo = impuestoNode.path("Codigo").asText();
        String codigoTarifa = impuestoNode.path("CodigoTarifa").asText();
        String tarifa = impuestoNode.path("Tarifa").asText();
        String monto = impuestoNode.path("Monto").asText();
        
        Impuesto impuesto = new Impuesto();
        impuesto.setCodigoImpuesto(codigo);
        impuesto.setCodigoTarifa(codigoTarifa);
        impuesto.setTarifa(tarifa);
        impuesto.setMonto(monto);
        
        return impuesto;
    }

    private ResumenFactura parseResumenFactura(JsonNode resumenFacturaNode) {
        String codigoMoneda = resumenFacturaNode.path("CodigoMoneda").asText();
        String tipoCambio = resumenFacturaNode.path("TipoCambio").asText();
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
        
        ResumenFactura resumenFactura = new ResumenFactura();
        resumenFactura.setCodigoMoneda(codigoMoneda);
        resumenFactura.setTipoCambio(tipoCambio);
        resumenFactura.setTotalServGravados(totalServiciosGravados);
        resumenFactura.setTotalServExentos(totalServiciosExentos);
        resumenFactura.setTotalServExonerado(totalServiciosExonerados);
        resumenFactura.setTotalMercanciasGravadas(totalMercanciasGravadas);
        resumenFactura.setTotalMercanciasExentas(totalMercanciasExentas);
        resumenFactura.setTotalMercExonerada(totalMercanciaExonerada);
        resumenFactura.setTotalGravado(totalGravado);
        resumenFactura.setTotalExento(totalExento);
        resumenFactura.setTotalExonerado(totalExonerado);
        resumenFactura.setTotalVenta(totalVenta);
        resumenFactura.setTotalDescuentos(totalDescuentos);
        resumenFactura.setTotalVentaNeta(totalVentaNeta);
        resumenFactura.setTotalImpuesto(totalImpuesto);
        resumenFactura.setTotalIVADevuelto(totalIVADevuelto);
        resumenFactura.setTotalOtrosCargos(totalOtrosCargos);
        resumenFactura.setTotalComprobante(totalComprobante);
        
        return resumenFactura;
    }

    private Descuento parseDescuento(JsonNode descuentoNode) {
        
        String montoDescuento = descuentoNode.path("MontoDescuento").asText();
        String naturalezaDescuento = descuentoNode.path("NaturalezaDescuento").asText();
        
        Descuento descuento = new Descuento();
        descuento.setMonto(montoDescuento);
        descuento.setNaturalezaDescuento(naturalezaDescuento);
        
        return descuento;
    }
}
