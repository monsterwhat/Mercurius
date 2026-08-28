package Services.Facturas;

import Models.Encabezado.Receptor;
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
public class ReceptorService extends GService<Receptor> {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ReceptorService.class.getName());

    @PersistenceContext @Nonnull EntityManager em;

    @Override
    protected @Nonnull Class<Receptor> getEntityClass() {
        return Receptor.class;
    }

    @PostConstruct
    public void init() {
    }

    @Transactional
    @Override
    public void create(@Nonnull Receptor entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=" + "ReceptorService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    @Override
    public void delete(@Nonnull Receptor entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                                LOG.info("Entity not found for delete" + " | source=" + "ReceptorService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "ReceptorService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    @Override
    public void update(@Nonnull Receptor entity) {
        try {
            em.merge(entity);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=" + "ReceptorService.update()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public @Nullable List<Receptor> listAll() {
        try {
            TypedQuery<Receptor> query = em.createQuery("SELECT d FROM Receptor d", Receptor.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=" + "ReceptorService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public @Nullable Receptor findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding entity by ID: " + e.getMessage() + " | source=" + "ReceptorService.findById()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
    
    public @Nullable List<Receptor> ListAllEnabled() {
        try {
            TypedQuery<Receptor> query = em.createQuery("SELECT a FROM Receptor", Receptor.class);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=" + "ReceptorService.ListAllEnabled()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Transactional
    public @Nullable Receptor createIfNotExist(@Nonnull Receptor receptor) {
        try {
            // Check if a Receptor with the same identification number already exists
            TypedQuery<Receptor> query = em.createQuery(
                "SELECT r FROM Receptor r JOIN r.identificacion i WHERE i.numero = :identificacionNumero", 
                Receptor.class
            );
            query.setParameter("identificacionNumero", receptor.getIdentificacion().getNumero());
            List<Receptor> existingReceptors = query.getResultList();

// If no Receptor with the same identification number exists, create a new one
            if (existingReceptors.isEmpty()) {
                em.persist(receptor);
                em.flush(); // Ensure the entity gets an ID
                em.refresh(receptor); // Refresh to get the generated ID
                return receptor;
            } else {
                // If a Receptor with the same identification number already exists, return it
                return existingReceptors.get(0);
            }
        } catch (PersistenceException e) {
            // Catch the database constraint violation exception
                        LOG.log(java.util.logging.Level.WARNING, "Error creating or retrieving Receptor: " + e.getMessage() + " | source=" + "ReceptorService.createIfNotExist()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }



}
