package Services;

import Models.Validacion.PrevalidationConfig;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.List;

/**
 * Service for managing PrevalidationConfig (DB-based pre-validation settings).
 * Falls back to sensible defaults if no config exists in the database.
 */
@Named
@ApplicationScoped
@Transactional
public class PrevalidationConfigService {

    @PersistenceContext
    @Nonnull
    EntityManager em;

    @Inject
    @Nonnull
    AlertasService alertasService;

    // ─── Defaults ───────────────────────────────────────────────────

    private static final boolean DEFAULT_CABYS_STRICT_MODE = true;
    private static final BigDecimal DEFAULT_TAX_TOLERANCE = new BigDecimal("0.01");
    private static final int DEFAULT_MAX_CORRECTION_ATTEMPTS = 3;

    @PostConstruct
    public void init() {
        // Seed a default config if none exists
        long count = count();
        if (count == 0) {
            PrevalidationConfig defaultConfig = new PrevalidationConfig();
            defaultConfig.setCabysStrictMode(DEFAULT_CABYS_STRICT_MODE);
            defaultConfig.setTaxTolerance(DEFAULT_TAX_TOLERANCE);
            defaultConfig.setMaxCorrectionAttempts(DEFAULT_MAX_CORRECTION_ATTEMPTS);
            defaultConfig.setWarnOnRounding(false);
            defaultConfig.setActive(true);
            defaultConfig.setProfileName("default");
            em.persist(defaultConfig);
            alertasService.registrarAlerta("Info",
                "Default PrevalidationConfig seeded", null, 0,
                "PrevalidationConfigService.init()", null, null);
        }
    }

    // ─── Queries ────────────────────────────────────────────────────

    /**
     * Returns the single active config, or a hardcoded fallback if none is
     * active or the query fails (e.g. table doesn't exist yet).
     */
    @Nonnull
    public PrevalidationConfig getActiveConfig() {
        try {
            TypedQuery<PrevalidationConfig> query = em.createQuery(
                "SELECT c FROM PrevalidationConfig c WHERE c.isActive = true",
                PrevalidationConfig.class);
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            // No active config — try to return any config
            try {
                TypedQuery<PrevalidationConfig> fallback = em.createQuery(
                    "SELECT c FROM PrevalidationConfig c ORDER BY c.id ASC",
                    PrevalidationConfig.class);
                fallback.setMaxResults(1);
                return fallback.getSingleResult();
            } catch (NoResultException e2) {
                // Truly empty — return in-code defaults
                return createDefaultConfig();
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                "Error reading PrevalidationConfig: " + e.getMessage(), null, 0,
                "PrevalidationConfigService.getActiveConfig()", null, e.getMessage());
            return createDefaultConfig();
        }
    }

    /**
     * Creates an in-memory default config (not persisted).
     */
    private PrevalidationConfig createDefaultConfig() {
        PrevalidationConfig cfg = new PrevalidationConfig();
        cfg.setCabysStrictMode(DEFAULT_CABYS_STRICT_MODE);
        cfg.setTaxTolerance(DEFAULT_TAX_TOLERANCE);
        cfg.setMaxCorrectionAttempts(DEFAULT_MAX_CORRECTION_ATTEMPTS);
        cfg.setWarnOnRounding(false);
        cfg.setActive(true);
        cfg.setProfileName("default");
        return cfg;
    }

    @Nonnull
    public List<PrevalidationConfig> listAll() {
        try {
            TypedQuery<PrevalidationConfig> query = em.createQuery(
                "SELECT c FROM PrevalidationConfig c ORDER BY c.id ASC",
                PrevalidationConfig.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                "Error listing PrevalidationConfig: " + e.getMessage(), null, 0,
                "PrevalidationConfigService.listAll()", null, e.getMessage());
            return List.of();
        }
    }

    @Nullable
    public PrevalidationConfig findById(@Nonnull Long id) {
        try {
            return em.find(PrevalidationConfig.class, id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                "Error finding PrevalidationConfig by ID: " + e.getMessage(), null, 0,
                "PrevalidationConfigService.findById()", null, e.getMessage());
            return null;
        }
    }

    public long count() {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(c) FROM PrevalidationConfig c", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                "PrevalidationConfig count failed: " + e.getMessage(), null, 0,
                "PrevalidationConfigService.count()", null, e.getMessage());
            return 0;
        }
    }

    // ─── Mutations ──────────────────────────────────────────────────

    public void save(@Nonnull PrevalidationConfig config) {
        try {
            if (config.getId() == null) {
                em.persist(config);
            } else {
                em.merge(config);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                "Error saving PrevalidationConfig: " + e.getMessage(), null, 0,
                "PrevalidationConfigService.save()", null, e.getMessage());
        }
    }

    /**
     * Sets the given config as active and deactivates all others.
     */
    public void setActive(@Nonnull Long id) {
        try {
            // Deactivate all
            em.createQuery("UPDATE PrevalidationConfig c SET c.isActive = false")
                .executeUpdate();
            // Activate the target
            PrevalidationConfig target = em.find(PrevalidationConfig.class, id);
            if (target != null) {
                target.setActive(true);
                em.merge(target);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                "Error setting active PrevalidationConfig: " + e.getMessage(), null, 0,
                "PrevalidationConfigService.setActive()", null, e.getMessage());
        }
    }
}
