package Services;

import Models.AppSettings;
import Models.Clients;
import Models.PuntosTransaccion;
import Models.Users;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Service for managing customer loyalty points system
 */
@Named
@ApplicationScoped
public class LoyaltyService extends GService<PuntosTransaccion> {

    @Inject
    private EntityManager em;

    @Inject
    private ClientService clientService;

    @Inject
    private AppSettingsService appSettingsService;

    @Override
    protected Class<PuntosTransaccion> getEntityClass() {
        return PuntosTransaccion.class;
    }

    /**
     * Calculate points earned based on purchase amount and current cashback percentage
     */
    public BigDecimal calculatePointsEarned(BigDecimal purchaseAmount, BigDecimal cashbackPercentage) {
        return purchaseAmount.multiply(cashbackPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
    }

    /**
     * Earn points for a customer from a purchase
     */
    @Transactional
    public void earnPoints(Clients client, BigDecimal purchaseAmount, String facturaId, Users currentUser) {
        AppSettings settings = appSettingsService.returnCurrent();
        if (settings == null || settings.getCashbackPercentage() == null) {
            return;
        }

        BigDecimal pointsEarned = calculatePointsEarned(purchaseAmount, settings.getCashbackPercentage());
        
        // Update client's total points
        client.setPuntosAcumulados(client.getPuntosAcumulados().add(pointsEarned));
        client.setLastPurchaseDate(new Date());
        client.setStatusPuntos("active");
        
        // Create transaction record
        PuntosTransaccion transaccion = new PuntosTransaccion();
        transaccion.setCliente(client);
        transaccion.setTipoTransaccion("earn");
        transaccion.setPuntos(pointsEarned);
        transaccion.setSaldoPuntos(client.getPuntosAcumulados());
        transaccion.setDescripcion("Puntos ganados por compra");
        transaccion.setFacturaId(facturaId);
        
        // Persist everything
        em.merge(client);
        em.persist(transaccion);
    }

    /**
     * Redeem points for a discount on purchase
     */
    @Transactional
    public BigDecimal redeemPoints(Clients client, BigDecimal pointsToRedeem) {
        if (pointsToRedeem.compareTo(client.getPuntosAcumulados()) > 0) {
            return BigDecimal.ZERO; // Cannot redeem more than available
        }

        // Update client's points
        BigDecimal newBalance = client.getPuntosAcumulados().subtract(pointsToRedeem);
        client.setPuntosAcumulados(newBalance);
        
        // Create transaction record
        PuntosTransaccion transaccion = new PuntosTransaccion();
        transaccion.setCliente(client);
        transaccion.setTipoTransaccion("redeem");
        transaccion.setPuntos(pointsToRedeem.negate()); // Negative for redemption
        transaccion.setSaldoPuntos(newBalance);
        transaccion.setDescripcion("Puntos utilizados como descuento");
        
        em.merge(client);
        em.persist(transaccion);
        
        return pointsToRedeem;
    }

    /**
     * Check and expire points for inactive customers
     */
    @Transactional
    public void checkAndExpireInactivePoints() {
        AppSettings settings = appSettingsService.returnCurrent();
        if (settings == null || settings.getPuntosInactivityMonths() == null) {
            return;
        }

        int inactivityMonths = settings.getPuntosInactivityMonths();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -inactivityMonths);
        Date inactivityDate = cal.getTime();

        // Find inactive customers
        String jpql = "SELECT c FROM Clients c WHERE c.lastPurchaseDate < :inactivityDate AND c.statusPuntos = 'active'";
        TypedQuery<Clients> query = em.createQuery(jpql, Clients.class)
                .setParameter("inactivityDate", inactivityDate);
        List<Clients> inactiveCustomers = query.getResultList();

        for (Clients client : inactiveCustomers) {
            // Expire points
            BigDecimal expiredPoints = client.getPuntosAcumulados();
            client.setPuntosAcumulados(BigDecimal.ZERO);
            client.setStatusPuntos("expired");

            // Create expiration transaction
            PuntosTransaccion transaccion = new PuntosTransaccion();
            transaccion.setCliente(client);
            transaccion.setTipoTransaccion("expire");
            transaccion.setPuntos(expiredPoints.negate());
            transaccion.setSaldoPuntos(BigDecimal.ZERO);
            transaccion.setDescripcion("Puntos expirados por inactividad");

            em.merge(client);
            em.persist(transaccion);
        }
    }

    /**
     * Get customer's point transaction history
     */
    public List<PuntosTransaccion> getCustomerPointsHistory(Clients client) {
        String jpql = "SELECT pt FROM PuntosTransaccion pt WHERE pt.cliente.code = :clientId ORDER BY pt.fechaCreacion DESC";
        TypedQuery<PuntosTransaccion> query = em.createQuery(jpql, PuntosTransaccion.class)
                .setParameter("clientId", client.getCode());
        return query.getResultList();
    }

    /**
     * Get customers with highest points balances
     */
    public List<Clients> getTopLoyaltyCustomers(int limit) {
        String jpql = "SELECT c FROM Clients c WHERE c.puntosAcumulados > 0 ORDER BY c.puntosAcumulados DESC";
        TypedQuery<Clients> query = em.createQuery(jpql, Clients.class)
                .setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Calculate customer tier based on total spending (internal tracking)
     */
    public String calculateCustomerTier(BigDecimal totalSpent) {
        // Internal tier calculation (not shown to customers)
        if (totalSpent.compareTo(BigDecimal.valueOf(500000)) >= 0) { // 500,000+ colones
            return "Gold";
        } else if (totalSpent.compareTo(BigDecimal.valueOf(200000)) >= 0) { // 200,000+ colones
            return "Silver";
        } else if (totalSpent.compareTo(BigDecimal.valueOf(50000)) >= 0) { // 50,000+ colones
            return "Bronze";
        }
        return "Basic";
    }

    /**
     * Get available points balance for a customer
     */
    public BigDecimal getAvailablePoints(Clients client) {
        if (client.getPuntosAcumulados() == null) {
            return BigDecimal.ZERO;
        }
        return client.getPuntosAcumulados();
    }
}