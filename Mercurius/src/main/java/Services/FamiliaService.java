package Services;

import Models.Familia;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Stateless;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.Date;
import java.util.List;

@Named
@Stateless
public class FamiliaService extends GService<Familia> {

    @Override
    protected Class<Familia> getEntityClass() {
        return Familia.class;
    }

    @PostConstruct
    public void init() {
    }
    
    public Long countActivas() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            System.out.println("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage());
            return null;
        }
    }
    
    public Long countInactivas() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            System.out.println("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage());
            return null;
        }
    }

    @Override
    public void create(Familia entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }
    
    public boolean createIfNotExists(Familia entity) {
        try {
            String queryStr = "SELECT COUNT(f) FROM Familia f WHERE f.nombre = :nombre";
            Long count = em.createQuery(queryStr, Long.class)
                           .setParameter("nombre", entity.getNombre())
                           .getSingleResult();

            if (count > 0) {
                return false;
            } else {
                em.persist(entity);
                return true;
            }

        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
            return false;
        }
    }


    @Override
    public void delete(Familia entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    @Override
    public void update(Familia entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Familia> listAll() {
        try {
            TypedQuery<Familia> query = em.createQuery("SELECT f FROM Familia f", Familia.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }

    public Familia findById(Integer id) {
    try {
            return em.find(getEntityClass(), id);
        } catch (Exception e) {
            System.out.println("Error finding entity by ID: " + e.toString());
            return null;
        }
    }
    
    public void updateAndDisable(Familia entity) {
        try {
            // Find the existing item by its ID
            Familia existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Disable the existing item if enabled
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                }
                em.merge(existingItem);
                
                //If we are updating an existing disabled record we need to re-enable it...
                if(!entity.getStatus()){
                    entity.setStatus(true);
                }
                em.persist(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    public void softDelete(Familia entity) {
        try {
            // Find the item by its ID
            Familia existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Se desactivo la familia!", null));
                }else{
                    existingItem.setStatus(true);
                    FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_WARN, "Se activo la familia!", null));
                }
                em.merge(existingItem);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting entity: " + e.toString());
        }
    }
    
    public List<Familia> findFamiliasAfterDate(Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Familia> cq = cb.createQuery(Familia.class);
        Root<Familia> familia = cq.from(Familia.class);

        Predicate datePredicate = cb.greaterThan(familia.get("fecha"), fecha);
        cq.where(datePredicate);

        TypedQuery<Familia> query = em.createQuery(cq);
        return query.getResultList();
    }

    
}
