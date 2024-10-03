package Utils.Parsers;

import Controllers.SessionController;
import Models.Comprobantes.ComprobantesRecibidos;
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
import Services.FacturaService;
import Services.Facturas.CodigoComercialService;
import Services.Facturas.DescuentoService;
import Services.Facturas.DetalleServicioService;
import Services.Facturas.EmisorService;
import Services.Facturas.EncabezadoService;
import Services.Facturas.ImpuestoService;
import Services.Facturas.LineaDetalleService;
import Services.Facturas.ReceptorService;
import Services.Facturas.ResumenFacturaService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Al
 */

@RequestScoped
public class Parser {
    
    @Inject DetalleServicioService detalleServicioService;    
    @Inject EmisorService emisorService;
    @Inject FacturaService facturaService;
    @Inject ImpuestoService impuestoService;
    @Inject DescuentoService descuentoService;
    @Inject CodigoComercialService codigoComercialService;
    @Inject ReceptorService receptorService;
    @Inject ResumenFacturaService resumenFacturaService;
    @Inject EncabezadoService encabezadoService;
    @Inject SessionController currentSession;
    @Inject LineaDetalleService lineaDetalleService;
    
    public LocalDateTime parseFechaEmision(String fechaEmision) {
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

    public Emisor parseEmisor(JsonNode emisorNode) {
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

    public Receptor parseReceptor(JsonNode receptorNode) {
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

    public Ubicacion parseUbicacion(JsonNode ubicacionNode) {
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

    public Telefono parseTelefono(JsonNode telefonoNode) {
        try {
            String codigoPais = telefonoNode.path("CodigoPais").asText();
            String numTelefono = telefonoNode.path("NumTelefono").asText();

            Telefono telefono = new Telefono();
            telefono.setCodigoPais(codigoPais);
            telefono.setNumeroTelefono(numTelefono);

            return telefono;
        } catch (Exception e) {
            System.out.println("Error parsing telefono: " + e.getLocalizedMessage());
            return null;
        }
    }

    public Fax parseFax(JsonNode faxNode) {        
        try {
            String codigoPais = faxNode.path("CodigoPais").asText();
            String numTelefono = faxNode.path("NumFax").asText();

            Fax fax = new Fax();

            if (!codigoPais.isEmpty()) {
                fax.setCodigoPais(codigoPais);
            } else {
                fax.setCodigoPais(null);
            }

            if (!numTelefono.isEmpty()) {
                fax.setNumeroFax(numTelefono);
            } else {
                fax.setNumeroFax(null);
            }

            return fax;
        } catch (Exception e) {
            System.out.println("Error parsing fax: " + e.getLocalizedMessage());
            return null;
        }
    }

    
    public List<LineaDetalle> parseDetalleServicio(JsonNode detalleServicio) {
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
    
    public List<MedioPago> parseMedioPago(JsonNode medioPagoNode, Encabezado comprobante){
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
        
    
    public LineaDetalle parseLineaDetalle(JsonNode lineaDetalleNode){
        try {
            LineaDetalle lineaDetalle = new LineaDetalle();

            int numeroLinea = lineaDetalleNode.path("NumeroLinea").asInt();
            String codigo = lineaDetalleNode.path("Codigo").asText();

            List<CodigoComercial> codigosComerciales = new ArrayList<>();
            // Parse multiple CodigoComercial if present
            if (lineaDetalleNode.path("CodigoComercial").isArray()) {
                for (JsonNode codigoComercialNode : lineaDetalleNode.path("CodigoComercial")) {
                    CodigoComercial codigoComercial = new CodigoComercial();
                    codigoComercial.setLineaDetalle(lineaDetalle);
                    codigoComercial = parseCodigoComercial(codigoComercialNode);
                    if(codigoComercial != null){
                        codigosComerciales.add(codigoComercial);
                    }
                }
            } else if (!lineaDetalleNode.path("CodigoComercial").isMissingNode()) {
                CodigoComercial codigoComercial = new CodigoComercial();
                codigoComercial.setLineaDetalle(lineaDetalle);
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

    public CodigoComercial parseCodigoComercial(JsonNode codigoComercialNode) {
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

    public Impuesto parseImpuesto(JsonNode impuestoNode) {
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

    public ResumenFactura parseResumenFactura(JsonNode resumenFacturaNode) {
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
    
    public CodigoTipoMoneda parseCodigoMoneda(String codigo, String tipo){
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

    public Descuento parseDescuento(JsonNode descuentoNode) {
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
    
    
    public double parseUnidadComercial(String unidad, String unidadComercial) {
        return switch (unidad) {
            case "Otros" -> parseUnidadComercial(unidadComercial);
            case "Unid" -> parseUnidadComercial(unidadComercial);
            case "g" -> parseUnidadComercial(unidadComercial);
            default -> 0;
        }; // Default to 0 if no specific conversion found
    }

    public double parseUnidadComercial(String unidadComercial) {
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
    
    @Transactional
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
                FacesMessage message = new FacesMessage("Factura Duplicada","Ya existe la factura: " + numeroConsecutivo);
                FacesContext.getCurrentInstance().addMessage(null, message);
                return;
            }
            
            Encabezado encabezado = new Encabezado();
            DetalleServicio detalles = new DetalleServicio();
            
            Emisor emisor = parseEmisor(rootNode.path("Emisor"));
            if (emisor == null) {
                return;
            }

            Receptor receptor = parseReceptor(rootNode.path("Receptor"));
            if (receptor == null) {
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

            ResumenFactura resumenFactura = parseResumenFactura(rootNode.path("ResumenFactura"));
            if(resumenFactura == null){
                return;
            }
            
            List<LineaDetalle> lineas = parseDetalleServicio(rootNode.path("DetalleServicio"));
            if(lineas == null){
                return;
            }
            
            for(LineaDetalle linea : lineas){
                linea.setDetalleServicio(detalles);
            }
            
            detalles.setLineasDetalle(lineas);
            
            encabezado.setCodigoActividad(codigoActividad);
            encabezado.setNumeroConsecutivo(numeroConsecutivo);
            encabezado.setFechaEmision(localDateTime);
            encabezado.setCondicionVenta(condicionVenta);
            encabezado.setPlazoCredito(plazoCredito);
            encabezado.setMedioPago(medioPago);
            encabezado.setClave(clave);
            
            Emisor persistedEmisor = emisorService.createIfNotExist(emisor);
            Receptor persistedReceptor = receptorService.createIfNotExist(receptor);
            
            if(persistedEmisor != null){
                encabezado.setEmisor(persistedEmisor);
            }
            if(persistedReceptor != null){
                encabezado.setReceptor(persistedReceptor);
            }
            
            detalleServicioService.create(detalles);
            resumenFacturaService.create(resumenFactura);
            encabezadoService.create(encabezado);
            
            ComprobantesRecibidos factura = new ComprobantesRecibidos();
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
}
