package Services;

import Models.Familia;
import Models.Enums.Tipo_SoftDelete;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Date;
import java.util.List;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class FamiliaService extends GService<Familia> {

    private static final Logger LOG = Logger.getLogger(FamiliaService.class);

    @Override
    protected @Nonnull Class<Familia> getEntityClass() {
        return Familia.class;
    }

    @PostConstruct
    public void init() {
    }
    
    public @Nullable Long countActivas() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=FamiliaService.countActivas() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nullable Long countInactivas() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=FamiliaService.countActivas() | despues=" + e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public void create(@Nonnull Familia entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=FamiliaService.create() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public boolean createIfNotExists(@Nonnull Familia entity) {
        try {
            String queryStr = "SELECT COUNT(f) FROM Familia f WHERE f.nombre = :nombre";
            Long count = em.createQuery(queryStr, Long.class)
                           .setParameter("nombre", entity.getNombre())
                           .getSingleResult();

            if (count > 0) {
                return false;
            } else {
                em.persist(entity);
            em.flush();
                return true;
            }

        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=FamiliaService.create() | despues=" + e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional
    public void delete(@Nonnull Familia entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                LOG.info("Entity not found | source=FamiliaService.method()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=FamiliaService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull Familia entity) {
        try {
            em.merge(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=FamiliaService.method() | despues=" + e.getMessage());
        }
    }

    @Override
    public @Nullable List<Familia> listAll() {
        try {
            TypedQuery<Familia> query = em.createQuery("SELECT f FROM Familia f", Familia.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=FamiliaService.listAll() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable Familia findById(@Nonnull Integer id) {
    try {
            return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            LOG.warn("Error finding entity by ID: " + e.getMessage() + " | source=FamiliaService.findById() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nullable Familia findByNombre(@Nonnull String nombre) {
        try {
            TypedQuery<Familia> query = em.createQuery("SELECT f FROM Familia f WHERE f.nombre = :nombre", Familia.class);
            query.setParameter("nombre", nombre);
            List<Familia> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
            LOG.warn("Error finding familia by nombre: " + e.getMessage() + " | source=FamiliaService.findByNombre() | despues=" + e.getMessage());
            return null;
        }
    }
    
    @Transactional
    public void updateAndDisable(@Nonnull Familia entity) {
        try {
            // Find the existing item by its ID
            Familia existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Disable the existing item if enabled
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                }
                em.merge(existingItem);
            em.flush();
                
                //If we are updating an existing disabled record we need to re-enable it...
                if(!entity.getStatus()){
                    entity.setStatus(true);
                }
                em.persist(entity);
            em.flush();
            } else {
                LOG.info("Entity not found | source=FamiliaService.method()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=FamiliaService.method() | despues=" + e.getMessage());
        }
    }

    @Transactional
    @Nullable
    public Tipo_SoftDelete softDelete(@Nonnull Familia entity) {
        try {
            // Find the item by its ID
            Familia existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                Tipo_SoftDelete result;
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                    result = Tipo_SoftDelete.DEACTIVATED;
                }else{
                    existingItem.setStatus(true);
                    result = Tipo_SoftDelete.ACTIVATED;
                }
                em.merge(existingItem);
            em.flush();
                return result;
            } else {
                LOG.info("Entity not found | source=FamiliaService.method()");
                return null;
            }
        } catch (PersistenceException e) {
            LOG.warn("Error soft deleting entity: " + e.getMessage() + " | source=FamiliaService.softDelete() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nonnull List<Familia> findFamiliasAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Familia> cq = cb.createQuery(Familia.class);
        Root<Familia> familia = cq.from(Familia.class);

        Predicate datePredicate = cb.greaterThan(familia.get("fecha"), fecha);
        cq.where(datePredicate);

        TypedQuery<Familia> query = em.createQuery(cq);
        return query.getResultList();
    }

}
