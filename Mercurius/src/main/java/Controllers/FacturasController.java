package Controllers;

import Models.Facturas.Telefono;
import Models.Facturas.Ubicacion;
import Services.Facturas.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.primefaces.model.file.UploadedFile;

/**
 *
 * @author Al
 */

@Named
@Data
@ViewScoped
public class FacturasController implements Serializable {
    
    @Inject CodigoComercialService CodigoComercialService;
    @Inject DetalleServicioService detalleServicioService;
    @Inject EmisorService emisorService;
    @Inject FacturaService facturaService;
    @Inject LineaDetalleService lineaDetalleService;
    @Inject ReceptorService receptorService;
    @Inject ResumenFacturaService resumenFacturaService;
    
    private List<UploadedFile> files;
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
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

    public void parseXMLOld(BufferedReader br) {
        try {
            StringBuilder xmlContent = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                xmlContent.append(line);
            }

            XmlMapper xmlMapper = new XmlMapper();
            JsonNode rootNode = xmlMapper.readTree(xmlContent.toString());
            
            // Traverse to the 'DetalleServicio' node
            JsonNode detalleServicioNode = rootNode.path("DetalleServicio");

            for (JsonNode lineaDetalleNode : detalleServicioNode.path("LineaDetalle")) {
            // Extract data for each line item
            String codigoCabys = lineaDetalleNode.path("Codigo").asText();
            //Puede que esto sea codigo de factura si es que el 3 es electronica y 4 nota de credito por el momento creo que 4 es codigoBarra necesito verificar
            String codigoProducto = extractCodigoComercial(lineaDetalleNode);
            String nombreProducto = lineaDetalleNode.path("Detalle").asText();
            double cantidad = lineaDetalleNode.path("Cantidad").asDouble();
            double precioUnitario = lineaDetalleNode.path("PrecioUnitario").asDouble();
            double montoTotal = lineaDetalleNode.path("MontoTotal").asDouble();
            String unidadMedida = lineaDetalleNode.path("UnidadMedida").asText();
            String unidadMedidaComercial = lineaDetalleNode.path("UnidadMedidaComercial").asText();
            double subTotal = lineaDetalleNode.path("SubTotal").asDouble();
            double impuestoMonto = lineaDetalleNode.path("Impuesto").path("Monto").asDouble();
            double impuestoNeto = lineaDetalleNode.path("ImpuestoNeto").asDouble();
            double montoTotalLinea = lineaDetalleNode.path("MontoTotalLinea").asDouble();

            // Process or store the extracted data as needed
            System.out.println("NombreProducto: " + nombreProducto);
            System.out.println("Codigo Cabys: " + codigoCabys);
            System.out.println("CodigoProducto: " + codigoProducto);
            System.out.println("UnidadMedida: " + unidadMedida);
            System.out.println("UnidadMedidaComercial: " + unidadMedidaComercial);
            System.out.println("Cantidad: " + cantidad);
            System.out.println("PrecioUnitario: " + precioUnitario);
            System.out.println("MontoTotal: " + montoTotal);
            System.out.println("SubTotal: " + subTotal);
            System.out.println("ImpuestoMonto: " + impuestoMonto);
            System.out.println("ImpuestoNeto: " + impuestoNeto);
            System.out.println("MontoTotalLinea: " + montoTotalLinea);
            System.out.println("-------------------------------------");
                        
            }
        } catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    public String extractValue(JsonNode node, String fieldName) {
        JsonNode valueNode = node.get(fieldName);
        return valueNode != null ? valueNode.asText() : "";
    }
    
    public String extractCodigoComercial(JsonNode node) {
        JsonNode codigoComercialNode = node.path("CodigoComercial");
        if (codigoComercialNode != null && codigoComercialNode.isArray()) {
            for (JsonNode codigoNode : codigoComercialNode) {
                String tipo = codigoNode.path("Tipo").asText();
                if ("03".equals(tipo)) {
                    String codigo = codigoNode.path("Codigo").asText();
                    return codigo;
                }
            }
        }
        return "";
    }

    public void processFacturas(){
        for (int i = 0; i < files.size(); i++) {
            parseXMLFromUploadedFile(files.get(i));
        }
        files.clear();
    }
    
    public void parseXML(InputStream inputStream) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder xmlContent = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                xmlContent.append(line);
            }

            XmlMapper xmlMapper = new XmlMapper();
            JsonNode rootNode = xmlMapper.readTree(xmlContent.toString());

            // Parse CodigoActividad
            String codigoActividad = rootNode.path("CodigoActividad").asText();
            System.out.println("CodigoActividad: " + codigoActividad);
            // Parse NumeroConsecutivo
            String numeroConsecutivo = rootNode.path("NumeroConsecutivo").asText();
            System.out.println("NumeroConsecutivo: " + numeroConsecutivo);
            // Parse FechaEmision
            String fechaEmision = rootNode.path("FechaEmision").asText();
            System.out.println("FechaEmision: " + fechaEmision);
            // Parse Emisor
            parseEmisor(rootNode.path("Emisor"));
            // Parse Receptor
            parseReceptor(rootNode.path("Receptor"));
            // Parse CondicionVenta
            String condicionVenta = rootNode.path("CondicionVenta").asText();
            System.out.println("CondicionVenta: " + condicionVenta);
            // Parse PlazoCredito
            String plazoCredito = rootNode.path("PlazoCredito").asText();
            System.out.println("PlazoCredito: " + plazoCredito);
            // Parse MedioPago
            String medioPago = rootNode.path("MedioPago").asText();
            System.out.println("MedioPago: " + medioPago);
            // Parse DetalleServicio
            parseDetalleServicio(rootNode.path("DetalleServicio"));
            // Parse ResumenFactura
            parseResumenFactura(rootNode.path("ResumenFactura"));

        } catch (Exception e) {
            System.out.println("Error ParsingXML to Object: " + e.getMessage());
        }
    }

    private void parseEmisor(JsonNode emisorNode) {
        //System.out.println("Emisor:");
        String nombre = emisorNode.path("Nombre").asText();
        String IdentificacionTipo = emisorNode.path("Identificacion").path("Tipo").asText();
        String IdentificacionNumero = emisorNode.path("Identificacion").path("Numero").asText();
        String NombreComercial = emisorNode.path("NombreComercial").asText();
        Ubicacion Ubicacion;
        Telefono Telefono;
        Telefono Fax;
        // Parse Ubicacion if present
        if (!emisorNode.path("Ubicacion").isMissingNode()) { 
            parseUbicacion(emisorNode.path("Ubicacion"));
        }
        // Parse Telefono if present
        if (!emisorNode.path("Telefono").isMissingNode()) {
            parseTelefono(emisorNode.path("Telefono"));
        }
        // Parse Fax if present
        if (!emisorNode.path("Fax").isMissingNode()) {
            parseFax(emisorNode.path("Fax"));
        }
        System.out.println("Correo Electronico: " + emisorNode.path("CorreoElectronico").asText());

    }

    private void parseReceptor(JsonNode receptorNode) {
        System.out.println("Receptor:");
        System.out.println("Nombre: " + receptorNode.path("Nombre").asText());
        System.out.println("Identificacion Tipo: " + receptorNode.path("Identificacion").path("Tipo").asText());
        System.out.println("Identificacion Numero: " + receptorNode.path("Identificacion").path("Numero").asText());
        System.out.println("Nombre Comercial: " + receptorNode.path("NombreComercial").asText());
         // Parse Telefono if present
        if (!receptorNode.path("Telefono").isMissingNode()) {
            parseTelefono(receptorNode.path("Telefono"));
        }
        System.out.println("Correo Electronico: " + receptorNode.path("CorreoElectronico").asText());
    }

    private void parseUbicacion(JsonNode ubicacionNode) {

        //System.out.println("Ubicacion:");
        String provincia = ubicacionNode.path("Provincia").asText();
        String Canton = ubicacionNode.path("Canton").asText();
        System.out.println("Distrito: " + ubicacionNode.path("Distrito").asText());
        System.out.println("Barrio: " + ubicacionNode.path("Barrio").asText());
        System.out.println("Otras Senas: " + ubicacionNode.path("OtrasSenas").asText());
        
        Ubicacion parsedUbicacion = new Ubicacion();
        parsedUbicacion.setProvincia(provincia);
    }

    private void parseTelefono(JsonNode telefonoNode) {
        System.out.println("Telefono:");
        System.out.println("Codigo Pais: " + telefonoNode.path("CodigoPais").asText());
        System.out.println("Numero Telefono: " + telefonoNode.path("NumTelefono").asText());
    }

    private void parseFax(JsonNode faxNode) {
        System.out.println("Fax:");
        System.out.println("Codigo Pais: " + faxNode.path("CodigoPais").asText());
        System.out.println("Numero Fax: " + faxNode.path("NumFax").asText());
    }

    private void parseDetalleServicio(JsonNode detalleServicioNode) {
        System.out.println("Detalle Servicio:");
        for (JsonNode lineaDetalleNode : detalleServicioNode.path("LineaDetalle")) {
            System.out.println("Numero Linea: " + lineaDetalleNode.path("NumeroLinea").asText());
            System.out.println("Codigo: " + lineaDetalleNode.path("Codigo").asText());

            // Parse multiple CodigoComercial if present
            if (lineaDetalleNode.path("CodigoComercial").isArray()) {
                for (JsonNode codigoComercialNode : lineaDetalleNode.path("CodigoComercial")) {
                    parseCodigoComercial(codigoComercialNode);
                }
            } else if (!lineaDetalleNode.path("CodigoComercial").isMissingNode()) {
                parseCodigoComercial(lineaDetalleNode.path("CodigoComercial"));
            }

            System.out.println("Cantidad: " + lineaDetalleNode.path("Cantidad").asText());
            System.out.println("Unidad Medida: " + lineaDetalleNode.path("UnidadMedida").asText());
            System.out.println("Detalle: " + lineaDetalleNode.path("Detalle").asText());
            System.out.println("Precio Unitario: " + lineaDetalleNode.path("PrecioUnitario").asText());
            System.out.println("Monto Total: " + lineaDetalleNode.path("MontoTotal").asText());
            System.out.println("SubTotal: " + lineaDetalleNode.path("SubTotal").asText());
            // Parse Impuesto if present
            if (!lineaDetalleNode.path("Impuesto").isMissingNode()) {
                parseImpuesto(lineaDetalleNode.path("Impuesto"));
            }
            System.out.println("Monto Total Linea: " + lineaDetalleNode.path("MontoTotalLinea").asText());
            System.out.println();
        }
    }


    private void parseCodigoComercial(JsonNode codigoComercialNode) {
        System.out.println("Codigo Comercial:");
        System.out.println("Tipo: " + codigoComercialNode.path("Tipo").asText());
        System.out.println("Codigo: " + codigoComercialNode.path("Codigo").asText());
    }


    private void parseImpuesto(JsonNode impuestoNode) {
        System.out.println("Impuesto:");
        System.out.println("Codigo: " + impuestoNode.path("Codigo").asText());
        System.out.println("Codigo Tarifa: " + impuestoNode.path("CodigoTarifa").asText());
        System.out.println("Tarifa: " + impuestoNode.path("Tarifa").asText());
        System.out.println("Monto: " + impuestoNode.path("Monto").asText());
    }

    private void parseResumenFactura(JsonNode resumenFacturaNode) {
        System.out.println("Resumen Factura:");
        //CodigoTipoMoneda, CodigoMoneda, TipoCambio
        System.out.println("CodigoTipoMoneda: " + resumenFacturaNode.path("CodigoTipoMoneda").asText());
        System.out.println("Total Servicios Gravados: " + resumenFacturaNode.path("TotalServGravados").asText());
        System.out.println("Total Servicios Exentos: " + resumenFacturaNode.path("TotalServExentos").asText());
        System.out.println("Total Servicios Exonerados: " + resumenFacturaNode.path("TotalServExonerado").asText());
        System.out.println("Total Mercancias Gravadas: " + resumenFacturaNode.path("TotalMercanciasGravadas").asText());
        System.out.println("Total Mercancias Exentas: " + resumenFacturaNode.path("TotalMercanciasExentas").asText());
        System.out.println("Total Mercancia Exonerada: " + resumenFacturaNode.path("TotalMercExonerada").asText());
        System.out.println("Total Gravado: " + resumenFacturaNode.path("TotalGravado").asText());
        System.out.println("Total Exento: " + resumenFacturaNode.path("TotalExento").asText());
        System.out.println("Total Exonerado: " + resumenFacturaNode.path("TotalExonerado").asText());
        System.out.println("Total Venta: " + resumenFacturaNode.path("TotalVenta").asText());
        System.out.println("Total Descuentos: " + resumenFacturaNode.path("TotalDescuentos").asText());
        System.out.println("Total Venta Neta: " + resumenFacturaNode.path("TotalVentaNeta").asText());
        System.out.println("Total Impuesto: " + resumenFacturaNode.path("TotalImpuesto").asText());
        System.out.println("Total Comprobante: " + resumenFacturaNode.path("TotalComprobante").asText());
    }
    
    //Need to implement all Services for Services.Facturas;
    
}
