package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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

    @Inject @Nonnull AlertasService alertasService;

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
                alertasService.registrarAlerta("Error", "Failed to retrieve TipoCambio data from API. Response code: " + response.getStatus(), null, 0, "TipoCambioService.getTipoCambioFromApi()", null, null);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error fetching TipoCambio from API: " + e.getMessage(), null, 0, "TipoCambioService.getTipoCambioFromApi()", null, e.getMessage());
        }
    }

    @Nullable
    private TipoCambio parseTipoCambio(@Nonnull String jsonResponse) {
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
            alertasService.registrarAlerta("Error", "Error parsing JSON response: " + e.getMessage(), null, 0, "TipoCambioService.parseTipoCambio()", null, e.getMessage());
            return null;
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void saveTipoCambio(@Nonnull TipoCambio tipoCambio) {
        create(tipoCambio);
    }

    @Nonnull
    private LocalDateTime parseFechaVenta(@Nonnull JsonNode ventaNode) {
        String fecha = ventaNode.get("fecha").asText();
        // API may return "2026-07-15" (10 chars) or "2026-07-15 00:00:00" (19 chars) or ISO format
        if (fecha.length() <= 10) {
            return LocalDateTime.parse(fecha + "T00:00:00");
        }
        String datePart = fecha.substring(0, 10);
        String timePart = fecha.substring(11).replace(" ", "T");
        return LocalDateTime.parse(datePart + "T" + timePart);
    }

    private boolean tipoCambioExistsForDate(@Nonnull LocalDateTime date) {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(t) FROM TipoCambio t WHERE CAST(t.fecha AS date) = :date", Long.class);
            query.setParameter("date", java.sql.Date.valueOf(date.toLocalDate()));
            return query.getSingleResult() > 0;
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error checking TipoCambio existence for date: " + e.getMessage(), null, 0, "TipoCambioService.tipoCambioExistsForDate()", null, e.getMessage());
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
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error retrieving newest TipoCambio: " + e.getMessage(), null, 0, "TipoCambioService.getNewestTipoCambio()", null, e.getMessage());
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
        alertasService.registrarAlerta("Error", "FALLBACK: Returning cached TipoCambio data due to API failure", null, 0, "TipoCambioService.getNewestTipoCambioFallback()", null, null);
        return getNewestTipoCambioFromDb();
    }
    
}
