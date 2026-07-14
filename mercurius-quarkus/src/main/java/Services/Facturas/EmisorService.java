
package Services.Facturas;

import Models.Encabezado.Emisor;
import Services.GService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
public class EmisorService extends GService<Emisor> {
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<Emisor> getEntityClass() {
        return Emisor.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(@Nonnull Emisor entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "EmisorService.create()", null, e.getMessage());
        }
    }

    @Override
    public void delete(@Nonnull Emisor entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for delete", null, 0, "EmisorService.delete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "EmisorService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(@Nonnull Emisor entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "EmisorService.update()", null, e.getMessage());
        }
    }

    @Override
    @Nullable
    public List<Emisor> listAll() {
        try {
            TypedQuery<Emisor> query = em.createQuery("SELECT d FROM Emisor d", Emisor.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "EmisorService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public Emisor findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "EmisorService.findById()", null, e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public List<Emisor> ListAllEnabled() {
        try {
            TypedQuery<Emisor> query = em.createQuery("SELECT a FROM Emisor", Emisor.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "EmisorService.ListAllEnabled()", null, e.getMessage());
            return null;
        }
    }

    @Nullable
    public Emisor createIfNotExist(@Nonnull Emisor emisor) {
        try {
            // Correct query to join identificacion and check the identificacion.numero field
            TypedQuery<Emisor> query = em.createQuery(
                "SELECT e FROM Emisor e JOIN e.identificacion i WHERE i.numero = :identificacionNumero", Emisor.class);
            query.setParameter("identificacionNumero", emisor.getIdentificacion().getNumero());
            List<Emisor> existingEmisors = query.getResultList();

// If no Emisor with the same identification number exists, create a new one
            if (existingEmisors.isEmpty()) {
                em.persist(emisor);
                em.flush(); // Ensure the entity gets an ID
                em.refresh(emisor); // Refresh to get the generated ID
                return emisor;
            } else {
                // If an Emisor with the same identification number already exists, return it
                return existingEmisors.get(0);
            }
        } catch (PersistenceException e) {
            // Catch the database constraint violation exception
            alertasService.registrarAlerta("Error", "Error creating or retrieving Emisor: " + e.getMessage(), null, 0, "EmisorService.createIfNotExist()", null, e.getMessage());
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }



}
