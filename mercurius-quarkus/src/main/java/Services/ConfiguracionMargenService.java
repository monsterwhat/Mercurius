package Services;

import Models.ConfiguracionMargen;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Servicio para la configuración global de márgenes de ganancia.
 *
 * PATRÓN: La configuración ACTUAL es siempre la de mayor ID
 * (ORDER BY id DESC LIMIT 1). Cada guardado crea una NUEVA fila —
 * nunca se actualiza una fila existente.
 */
@Named
@ApplicationScoped
public class ConfiguracionMargenService extends GService<ConfiguracionMargen> {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(ConfiguracionMargenService.class);

    @Override
    protected @Nonnull Class<ConfiguracionMargen> getEntityClass() {
        return ConfiguracionMargen.class;
    }

    /**
     * Obtiene la configuración actual: la última por ID.
     * Retorna null solo si la tabla está vacía.
     */
    @Nullable
    public ConfiguracionMargen getConfiguracionActual() {
        try {
            TypedQuery<ConfiguracionMargen> query = em.createQuery(
                "SELECT c FROM ConfiguracionMargen c ORDER BY c.id DESC", ConfiguracionMargen.class);
            query.setMaxResults(1);
            List<ConfiguracionMargen> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
            LOG.warn("Error getting current margin config: " + e.getMessage()
                + " | source=ConfiguracionMargenService.getConfiguracionActual()");
            return null;
        }
    }

    /**
     * Obtiene la configuración actual, o crea una con valores por defecto
     * si la tabla está vacía.
     */
    @Nonnull
    public ConfiguracionMargen findOrCreateDefault() {
        ConfiguracionMargen existente = getConfiguracionActual();
        if (existente != null) {
            return existente;
        }
        ConfiguracionMargen defaults = new ConfiguracionMargen();
        defaults.setMargenBase(new BigDecimal("25.00"));
        defaults.setAjusteRefrigerado(new BigDecimal("5.00"));
        defaults.setAjusteCongelado(new BigDecimal("10.00"));
        em.persist(defaults);
        em.flush();
        LOG.info("Created default margin configuration: base=25%, ref=+5%, cong=+10%");
        return defaults;
    }

    /**
     * Lista todos los registros ordenados por ID descendente (historial).
     */
    @Nullable
    public List<ConfiguracionMargen> listAllOrderById() {
        try {
            TypedQuery<ConfiguracionMargen> query = em.createQuery(
                "SELECT c FROM ConfiguracionMargen c ORDER BY c.id DESC", ConfiguracionMargen.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all margin configs: " + e.getMessage());
            return Collections.emptyList();
        }
    }
}
