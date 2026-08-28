package Services;

import Models.Registros.Alertas;
import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class AlertasService extends GService<Alertas> {

    private static final Logger LOG = Logger.getLogger(AlertasService.class.getName());

    @Override
    protected @Nonnull Class<Alertas> getEntityClass() {
        return Alertas.class;
    }

    @PostConstruct
    public void init() {
        
    }
    
    @Override
    @Transactional
    public void create(@Nonnull Alertas alerta) {
        // Dual write: quarkus file log (logs/mercurius.log) + DB for backward compat during migration.
        // File log is the source of truth per quarkus logging migration.
        LOG.log(java.util.logging.Level.INFO, String.format("ALERT [%s] %s | user=%s | source=%s | antes=%s | despues=%s",
                alerta.getTipo(), alerta.getMensaje(),
                alerta.getUser() != null ? alerta.getUser().getUsername() : "Sistema",
                alerta.getSource(), alerta.getAntes(), alerta.getDespues()));
        try {
            em.persist(alerta);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error creating Alertas entity: " + e.getMessage());
        }
    }
    
    @Transactional
    public void registrarAlerta(@Nonnull String tipo, @Nonnull String Mensaje, @Nullable Users user, int codigo, @Nonnull String source, @Nullable String antes, @Nullable String despues){
            // Quarkus file log (logs/mercurius.log via quarkus.log.file.*) is now primary; DB kept for UI compat.
            String userStr = user != null ? user.getUsername() : "Sistema";
            java.util.logging.Level lvl = "Error".equalsIgnoreCase(tipo) ? java.util.logging.Level.WARNING : java.util.logging.Level.INFO;
            LOG.log(lvl, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    tipo, Mensaje, userStr, codigo, source, antes, despues));
            Alertas alerta = new Alertas();
            alerta.setTipo(tipo);
            alerta.setMensaje(Mensaje);
            alerta.setTimestamp(java.time.LocalDateTime.now());
            alerta.setUser(user);
            alerta.setVista(false);
            alerta.setCodigo(codigo);
            alerta.setSource(source);
            alerta.setAntes(antes);
            alerta.setDespues(despues);
            create(alerta);
    }  
    
    @Transactional
    public void toggleVista(@Nonnull Alertas alerta) {
        try {
            alerta.setVista(!alerta.isVista());
            em.merge(alerta);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error toggling Alertas vista: " + e.getMessage());
        }
    }

    @Transactional
    @Nonnull
    public List<Alertas> findFiltered(@Nullable Date fechaDesde, @Nullable Date fechaHasta,
                                       @Nullable Users user, @Nullable String tipo,
                                       @Nullable String source) {
        try {
            CriteriaBuilder cb = em.getCriteriaBuilder();
            CriteriaQuery<Alertas> cq = cb.createQuery(Alertas.class);
            Root<Alertas> alerta = cq.from(Alertas.class);
            List<Predicate> predicates = new ArrayList<>();

            if (fechaDesde != null) {
                predicates.add(cb.greaterThanOrEqualTo(alerta.get("timestamp"), fechaDesde.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()));
            }
            if (fechaHasta != null) {
                predicates.add(cb.lessThanOrEqualTo(alerta.get("timestamp"), fechaHasta.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()));
            }
            if (user != null) {
                predicates.add(cb.equal(alerta.get("user"), user));
            }
            if (tipo != null && !tipo.trim().isEmpty()) {
                predicates.add(cb.equal(alerta.get("tipo"), tipo));
            }
            if (source != null && !source.trim().isEmpty()) {
                predicates.add(cb.like(cb.lower(alerta.get("source")), "%" + source.toLowerCase() + "%"));
            }

            if (!predicates.isEmpty()) {
                cq.where(cb.and(predicates.toArray(new Predicate[0])));
            }
            cq.orderBy(cb.desc(alerta.get("timestamp")));

            TypedQuery<Alertas> query = em.createQuery(cq);
            query.setMaxResults(500);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error filtering Alertas: " + e.getMessage());
            return List.of();
        }
    }

    @Transactional
    @Nonnull
    public List<String> findDistinctTipos() {
        try {
            TypedQuery<String> query = em.createQuery(
                "SELECT DISTINCT a.tipo FROM Alertas a WHERE a.tipo IS NOT NULL ORDER BY a.tipo", String.class);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error finding distinct tipos: " + e.getMessage());
            return List.of();
        }
    }

    @Transactional
    @Nonnull
    public List<String> findDistinctSources() {
        try {
            TypedQuery<String> query = em.createQuery(
                "SELECT DISTINCT a.source FROM Alertas a WHERE a.source IS NOT NULL ORDER BY a.source", String.class);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.severe("Error finding distinct sources: " + e.getMessage());
            return List.of();
        }
    }
}