package Controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
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
        try (InputStream inputStream = uploadedFile.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
            parseXML(br);
        } catch (IOException e) {
            System.out.println("Error" + e.getLocalizedMessage());
        }
    }

    public void parseXML(BufferedReader br) {
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
    
}
