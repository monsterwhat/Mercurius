package Services;

import Models.TipoCambio;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@Transactional
public class TipoCambioService extends GService<TipoCambio> {

    @Override
    protected Class<TipoCambio> getEntityClass() {
        return TipoCambio.class;
    }

    public void getTipoCambioFromApi() {
        
        // Get the current date
        LocalDateTime currentDateTime = LocalDateTime.now();

        // Check if a TipoCambio record already exists for the current date
        if (tipoCambioExistsForDate(currentDateTime)) {
            return; // Exit the method if a record already exists
        }

        // Proceed with fetching data from the API if no record exists for today
        Client client = ClientBuilder.newClient();
        Response response = client
            .target("https://api.hacienda.go.cr/indicadores/tc/dolar")
            .request(MediaType.APPLICATION_JSON)
            .get();

        if (response.getStatus() == Response.Status.OK.getStatusCode()) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode rootNode = mapper.readTree(response.readEntity(String.class));
                JsonNode ventaNode = rootNode.get("venta");
                JsonNode compraNode = rootNode.get("compra");

                // Parse date and time components separately
                String fechaVentaDate = ventaNode.get("fecha").asText().substring(0, 10); // Extract date part
                String fechaVentaTime = ventaNode.get("fecha").asText().substring(11); // Extract time part
                LocalDateTime fechaVenta = LocalDateTime.parse(fechaVentaDate + "T" + fechaVentaTime);

                double valorVenta = ventaNode.get("valor").asDouble();
                valorVenta = Math.floor(valorVenta);
                double valorCompra = compraNode.get("valor").asDouble();
                valorCompra = Math.floor(valorCompra);

                TipoCambio tipoCambio = new TipoCambio();
                tipoCambio.setFecha(fechaVenta);
                tipoCambio.setValorCompra(valorCompra);
                tipoCambio.setValorVenta(valorVenta);

                create(tipoCambio);
            } catch (JsonProcessingException e) {
                System.err.println("Error parsing JSON response: " + e.getMessage());
            }
        } else {
            System.err.println("Failed to retrieve TipoCambio data from API. Response code: " + response.getStatus());
        }
    }

    private boolean tipoCambioExistsForDate(LocalDateTime date) {
        try {
            // Query the database to check if a TipoCambio record exists for the given date
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(t) FROM TipoCambio t WHERE FUNCTION('DATE', t.fecha) = :date", Long.class);
            query.setParameter("date", date);
            Long count = query.getSingleResult();
            return count > 0;
        } catch (Exception e) {
            System.err.println("Error checking TipoCambio existence for date: " + e.getMessage());
            return false;
        }
    }
    
    public TipoCambio getNewestTipoCambio() {
        try {
                getTipoCambioFromApi();
                
                TypedQuery<TipoCambio> query = em.createQuery("SELECT t FROM TipoCambio t ORDER BY t.id DESC", TipoCambio.class);
                query.setMaxResults(1);
                List<TipoCambio> results = query.getResultList();
                
                results = query.getResultList();
                if (!results.isEmpty()) {
                    return results.get(0);
                } else {
                    return null;
                }
        } catch (Exception e) {
            System.err.println("Error retrieving newest TipoCambio: " + e.getMessage());
            return null;
        }
    }
    
}
