package Services;

import Models.Articulos.Promocion;
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
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "PromocionesService.countActivos()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nullable Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.activa = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "PromocionesService.countActivos()", null, e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public void create(Promocion entity) {
        try {
            em.persist(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "PromocionesService.create()", null, e.getMessage());
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
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "PromocionesService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "PromocionesService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(Promocion entity) {
        try {
            if(!entity.isActiva()){
                entity.setActiva(true);
            }
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "PromocionesService.update()", null, e.getMessage());
        }
    }

    @Override
    public List<Promocion> listAll() {
        try {
            TypedQuery<Promocion> query = em.createQuery("SELECT d FROM Promocion d", Promocion.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "PromocionesService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nullable Promocion findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "PromocionesService.findById()", null, e.getMessage());
            return null;
        }
    }
    
}
