package Services;

import Models.TipoCambio;
import com.fasterxml.jackson.core.JsonProcessingException; 
import java.time.LocalDateTime;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ejb.Stateless;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;

@Named 
@Stateless
public class TipoCambioService extends GService<TipoCambio> {

    @Override
    protected Class<TipoCambio> getEntityClass() {
        return TipoCambio.class;
    }

    public void getTipoCambioFromApi() {
        
        LocalDateTime currentDateTime = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        if (tipoCambioExistsForDate(currentDateTime)) {
            return; // Exit if a record exists for today
        }

        try (Client client = ClientBuilder.newClient()) {
            Response response = client
                .target("https://api.hacienda.go.cr/indicadores/tc/dolar")
                .request(MediaType.APPLICATION_JSON)
                .get();

            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                parseAndSaveTipoCambio(response.readEntity(String.class));
            } else {
                System.err.println("Failed to retrieve TipoCambio data from API. Response code: " + response.getStatus());
            }
        }
    }

    private void parseAndSaveTipoCambio(String jsonResponse) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(jsonResponse);
            JsonNode ventaNode = rootNode.get("venta");
            JsonNode compraNode = rootNode.get("compra");

            LocalDateTime fechaVenta = parseFechaVenta(ventaNode);
            double valorVenta = Math.floor(ventaNode.get("valor").asDouble());
            double valorCompra = Math.floor(compraNode.get("valor").asDouble());

            TipoCambio tipoCambio = new TipoCambio();
            tipoCambio.setFecha(fechaVenta);
            tipoCambio.setValorCompra(valorCompra);
            tipoCambio.setValorVenta(valorVenta);
            
            create(tipoCambio);
        } catch (JsonProcessingException e) {
            System.err.println("Error parsing JSON response: " + e.getMessage());
        }
    }

    private LocalDateTime parseFechaVenta(JsonNode ventaNode) {
        String fecha = ventaNode.get("fecha").asText();
        return LocalDateTime.parse(fecha.substring(0, 10) + "T" + fecha.substring(11));
    }

    private boolean tipoCambioExistsForDate(LocalDateTime date) {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(t) FROM TipoCambio t WHERE FUNCTION('DATE', t.fecha) = :date", Long.class);
            query.setParameter("date", date);
            return query.getSingleResult() > 0;
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
            return query.getResultList().stream().findFirst().orElse(null);
        } catch (Exception e) {
            System.err.println("Error retrieving newest TipoCambio: " + e.getMessage());
            return null;
        }
    }
    
}
