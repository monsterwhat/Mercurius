package Services;

import Models.Articulos.Articulos;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Date;
import java.util.List;

@Named
@ApplicationScoped
public class ArticulosService extends GService<Articulos> {

    @Override
    protected @Nonnull Class<Articulos> getEntityClass() {
        return Articulos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(@Nonnull Articulos entity) {
        try {
            em.persist(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "ArticulosService.create()", null, e.getMessage());
        }
    }

    @Override
    public void delete(@Nonnull Articulos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "ArticulosService.method()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "ArticulosService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(@Nonnull Articulos entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
        }
    }
    
    @Override
    public @Nullable List<Articulos> listAll() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ArticulosService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public void updateAndDisable(@Nonnull Articulos entity) {
        try {
            // Find the existing item by its ID
            Articulos existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Disable the existing item
                existingItem.setStatus(false);
                existingItem.setProcessed(true);
                em.merge(existingItem);

                // Create a new item with the updated information
                em.persist(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "ArticulosService.method()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
        }
    }

    public @Nullable List<Articulos> ListAllEnabled() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.status = true", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
            return null;
        }
    }

    public void softDelete(@Nonnull Articulos entity) {
        try {
            // Find the item by its ID
            Articulos existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "ArticulosService.method()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "ArticulosService.softDelete()", null, e.getMessage());
        }
    }
    
    public @Nullable Articulos findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "ArticulosService.findById()", null, e.getMessage());
            return null;
        }
    }

    public @Nullable Articulos findByName(@Nonnull String name) {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.nombre = :name", Articulos.class);
            query.setParameter("name", name);
            List<Articulos> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(0);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error " + e.getLocalizedMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
            return null;
        }
    }

    public @Nullable Articulos findByBarCode(@Nonnull String barcode) {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.codigoBarra = :barcode", Articulos.class);
            query.setParameter("barcode", barcode);
            List<Articulos> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(0);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error " + e.getLocalizedMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
            return null;
        }    
    }

    public @Nullable List<Articulos> listAllSinProcesar() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.processed = false AND a.status = true", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
            return null;
        }    
    }

    public @Nullable List<Articulos> listAllActivosYProcesados() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.processed = true AND a.status = true", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
            return null;
        }     
    }
    
    public @Nullable List<Articulos> listAllInactivos() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.status = false", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "ArticulosService.method()", null, e.getMessage());
            return null;
        }     
    }
    
    public @Nonnull List<Articulos> findArticulosAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Articulos> cq = cb.createQuery(Articulos.class);
        Root<Articulos> articulos = cq.from(Articulos.class);

        Predicate datePredicate = cb.greaterThan(articulos.get("fecha"), fecha);
        cq.where(datePredicate);

        TypedQuery<Articulos> query = em.createQuery(cq);
        return query.getResultList();
    }

    public @Nonnull Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Articulos e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "ArticulosService.count()", null, e.getMessage());
            return 0l;
        }
    }
    
    public @Nonnull Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Articulos e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "ArticulosService.count()", null, e.getMessage());
            return 0l;
        }
    }
    
    public @Nonnull Long countPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Articulos e WHERE e.status = true AND e.processed = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "ArticulosService.count()", null, e.getMessage());
            return 0l;
        }
    }
    

}
