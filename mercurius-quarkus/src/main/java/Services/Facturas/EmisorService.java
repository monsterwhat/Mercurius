
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
import jakarta.transaction.Transactional;
import java.util.List;

@Named
@ApplicationScoped
public class EmisorService extends GService<Emisor> {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(EmisorService.class.getName());
    
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
    @Transactional
    public void create(@Nonnull Emisor entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=" + "EmisorService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    @Transactional
    public void delete(@Nonnull Emisor entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                                LOG.info("Entity not found for delete" + " | source=" + "EmisorService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "EmisorService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull Emisor entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=" + "EmisorService.update()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    @Nullable
    public List<Emisor> listAll() {
        try {
            TypedQuery<Emisor> query = em.createQuery("SELECT d FROM Emisor d", Emisor.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=" + "EmisorService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    @Nullable
    public Emisor findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding entity by ID: " + e.getMessage() + " | source=" + "EmisorService.findById()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    @Nullable
    public List<Emisor> ListAllEnabled() {
        try {
            TypedQuery<Emisor> query = em.createQuery("SELECT a FROM Emisor", Emisor.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=" + "EmisorService.ListAllEnabled()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Nullable
    @Transactional
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
                        LOG.log(java.util.logging.Level.WARNING, "Error creating or retrieving Emisor: " + e.getMessage() + " | source=" + "EmisorService.createIfNotExist()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }



}
