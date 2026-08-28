package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.TipoCambio;
import com.fasterxml.jackson.core.JsonProcessingException; 
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(TipoCambioService.class.getName());


    @Override
    protected Class<TipoCambio> getEntityClass() {
        return TipoCambio.class;
    }

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
                                LOG.log(java.util.logging.Level.WARNING, "Failed to retrieve TipoCambio data from API. Response code: " + response.getStatus() + " | source=" + "TipoCambioService.getTipoCambioFromApi()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
            }
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error fetching TipoCambio from API: " + e.getMessage() + " | source=" + "TipoCambioService.getTipoCambioFromApi()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
            BigDecimal valorVenta = new BigDecimal(ventaNode.get("valor").asText()).setScale(5, RoundingMode.HALF_UP);
            BigDecimal valorCompra = new BigDecimal(compraNode.get("valor").asText()).setScale(5, RoundingMode.HALF_UP);

            TipoCambio tipoCambio = new TipoCambio();
            tipoCambio.setFecha(fechaVenta);
            tipoCambio.setValorCompra(valorCompra);
            tipoCambio.setValorVenta(valorVenta);
            
            return tipoCambio;
        } catch (JsonProcessingException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error parsing JSON response: " + e.getMessage() + " | source=" + "TipoCambioService.parseTipoCambio()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.log(java.util.logging.Level.WARNING, "Error checking TipoCambio existence for date: " + e.getMessage() + " | source=" + "TipoCambioService.tipoCambioExistsForDate()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.log(java.util.logging.Level.WARNING, "Error retrieving newest TipoCambio: " + e.getMessage() + " | source=" + "TipoCambioService.getNewestTipoCambio()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
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
                LOG.log(java.util.logging.Level.WARNING, "FALLBACK: Returning cached TipoCambio data due to API failure" + " | source=" + "TipoCambioService.getNewestTipoCambioFallback()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
        return getNewestTipoCambioFromDb();
    }
    
}
