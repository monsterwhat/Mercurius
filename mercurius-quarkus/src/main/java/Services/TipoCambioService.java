package Services;

import Models.TipoCambio;
import com.fasterxml.jackson.core.JsonProcessingException; 
import java.time.LocalDateTime;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import java.time.temporal.ChronoUnit;

@Named 
@ApplicationScoped
public class TipoCambioService extends GService<TipoCambio> {

    @Inject AlertasService alertasService;

    @Override
    protected Class<TipoCambio> getEntityClass() {
        return TipoCambio.class;
    }

    @Transactional
    public void getTipoCambioFromApi() {
        
        LocalDateTime currentDateTime = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
        
        if (tipoCambioExistsForDate(currentDateTime)) {
            return;
        }

        try (Client client = ClientBuilder.newClient()) {
            Response response = client
                .target("https://api.hacienda.go.cr/indicadores/tc/dolar")
                .request(MediaType.APPLICATION_JSON)
                .get();

            if (response.getStatus() == Response.Status.OK.getStatusCode()) {
                TipoCambio tipoCambio = parseTipoCambio(response.readEntity(String.class));
                if (tipoCambio != null) {
                    saveTipoCambio(tipoCambio);
                }
            } else {
                System.err.println("Failed to retrieve TipoCambio data from API. Response code: " + response.getStatus());
            }
        } catch (Exception e) {
            System.err.println("Error fetching TipoCambio from API: " + e.getMessage());
            alertasService.registrarAlerta("Error Tipo de Cambio", "Error al obtener tipo de cambio de API: " + e.getMessage(), null, 0, "getTipoCambioFromApi()", null, e.getMessage());
        }
    }

    private TipoCambio parseTipoCambio(String jsonResponse) {
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
            
            return tipoCambio;
        } catch (JsonProcessingException e) {
            System.err.println("Error parsing JSON response: " + e.getMessage());
            alertasService.registrarAlerta("Error Tipo de Cambio", "Error al parsear JSON de tipo de cambio: " + e.getMessage(), null, 0, "parseTipoCambio()", null, e.getMessage());
            return null;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void saveTipoCambio(TipoCambio tipoCambio) {
        create(tipoCambio);
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
            alertasService.registrarAlerta("Error Tipo de Cambio", "Error al verificar existencia de tipo de cambio: " + e.getMessage(), null, 0, "tipoCambioExistsForDate()", null, e.getMessage());
            return false;
        }
    }
    
    @Timeout(value = 3, unit = ChronoUnit.SECONDS)
    @Retry(maxRetries = 2, delay = 1000, jitter = 500)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.5, delay = 30, delayUnit = ChronoUnit.SECONDS)
    @Fallback(fallbackMethod = "getNewestTipoCambioFallback")
    public TipoCambio getNewestTipoCambio() {
        try {
            getTipoCambioFromApi();
            return getNewestTipoCambioFromDb();
        } catch (Exception e) {
            System.err.println("Error retrieving newest TipoCambio: " + e.getMessage());
            alertasService.registrarAlerta("Error Tipo de Cambio", "Error al obtener el ultimo tipo de cambio: " + e.getMessage(), null, 0, "getNewestTipoCambio()", null, e.getMessage());
            throw e;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public TipoCambio getNewestTipoCambioFromDb() {
        TypedQuery<TipoCambio> query = em.createQuery("SELECT t FROM TipoCambio t ORDER BY t.id DESC", TipoCambio.class);
        query.setMaxResults(1);
        return query.getResultList().stream().findFirst().orElse(null);
    }
    
    public TipoCambio getNewestTipoCambioFallback() {
        System.err.println("FALLBACK: Returning cached TipoCambio data due to API failure");
        alertasService.registrarAlerta("Error Tipo de Cambio", "Fallo al obtener tipo de cambio, usando cache", null, 0, "getNewestTipoCambioFallback()", null, null);
        return getNewestTipoCambioFromDb();
    }
    
}
