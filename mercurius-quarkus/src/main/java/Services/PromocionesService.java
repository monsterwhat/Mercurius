package Services;

import Models.Articulos.Promocion;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.List;

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class PromocionesService extends GService<Promocion> {

    private static final Logger LOG = Logger.getLogger(PromocionesService.class);

    @Override
    protected @Nonnull Class<Promocion> getEntityClass() {
        return Promocion.class;
    }

    @PostConstruct
    public void init() {
        
    }
    
    public @Nullable Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.activa = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=" + "PromocionesService.countActivos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public @Nullable Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.activa = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=" + "PromocionesService.countActivos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Override
    @Transactional
    public void create(Promocion entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
                        LOG.warn("Error creating entity: " + e.getMessage() + " | source=" + "PromocionesService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void delete(Promocion entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "PromocionesService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "PromocionesService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void update(Promocion entity) {
        try {
            if(!entity.isActiva()){
                entity.setActiva(true);
            }
            em.merge(entity);
            em.flush();
        } catch (PersistenceException e) {
                        LOG.warn("Error updating entity: " + e.getMessage() + " | source=" + "PromocionesService.update()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public List<Promocion> listAll() {
        try {
            TypedQuery<Promocion> query = em.createQuery("SELECT d FROM Promocion d", Promocion.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.warn("Error listing all entities: " + e.getMessage() + " | source=" + "PromocionesService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public @Nullable Promocion findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.warn("Error finding entity by ID: " + e.getMessage() + " | source=" + "PromocionesService.findById()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
}
