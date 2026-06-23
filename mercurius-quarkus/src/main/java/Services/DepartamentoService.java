package Services;

import Models.Departamento; 
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
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
public class DepartamentoService extends GService<Departamento> {

    @Override
    protected Class<Departamento> getEntityClass() {
        return Departamento.class;
    }

    @PostConstruct
    public void init() {
    }
    
    public Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "DepartamentoService.countActivos()", null, e.getMessage());
            return null;
        }
    }
    
    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM " + getEntityClass().getSimpleName() + " e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage(), null, 0, "DepartamentoService.countActivos()", null, e.getMessage());
            return null;
        }
    }

    /**
     * Find department by name
     */
    public Departamento findByName(String nombre) {
        try {
            String jpql = "SELECT d FROM Departamento d WHERE d.nombre = :nombre AND d.status = true";
            TypedQuery<Departamento> query = em.createQuery(jpql, Departamento.class)
                    .setParameter("nombre", nombre);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding department by name: " + e.getLocalizedMessage(), null, 0, "DepartamentoService.findByName()", null, e.getMessage());
            return null;
        }
    }

    @Override
    public void create(Departamento entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), null, 0, "DepartamentoService.create()", null, e.getMessage());
        }
    }

    @Override
    public void delete(Departamento entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "DepartamentoService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "DepartamentoService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(Departamento entity) {
        try {
            if(!entity.getStatus()){
                entity.setStatus(true);
            }
            em.merge(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), null, 0, "DepartamentoService.update()", null, e.getMessage());
        }
    }

    @Override
    public List<Departamento> listAll() {
        try {
            TypedQuery<Departamento> query = em.createQuery("SELECT d FROM Departamento d", Departamento.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "DepartamentoService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public Departamento findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding entity by ID: " + e.getMessage(), null, 0, "DepartamentoService.findById()", null, e.getMessage());
            return null;
        }
    }
    
    public void softDelete(Departamento entity) {
        try {
            // Find the item by its ID
            Departamento existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                        FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se desactivo el departamento!", null));
                }else{
                    existingItem.setStatus(true);
                        FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_INFO, "Se activo el departamento!", null));
                }
                em.merge(existingItem);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found", null, 0, "DepartamentoService.softDelete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "DepartamentoService.softDelete()", null, e.getMessage());
        }
    }
    
    public Departamento createIfNotExist(Departamento departamento) {
        try {
            TypedQuery<Departamento> query = em.createQuery("SELECT e FROM Departamento e WHERE e.nombre = :nombre", Departamento.class);
            query.setParameter("nombre", departamento.getNombre());
            List<Departamento> existingEmisors = query.getResultList();

            if (existingEmisors.isEmpty()) {
                em.persist(departamento);
                return departamento;
            } else {
                return existingEmisors.get(0);
            }
        } catch (PersistenceException e) {
            // Catch the database constraint violation exception
            alertasService.registrarAlerta("Error", "Error creating or retrieving Departamento: " + e.getMessage(), null, 0, "DepartamentoService.createIfNotExist()", null, e.getMessage());
            // Handle the error gracefully, maybe log it or notify the user
            return null;
        }
    }
    
    public List<Departamento> findDepartamentosAfterDate(Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Departamento> cq = cb.createQuery(Departamento.class);
        Root<Departamento> departamento = cq.from(Departamento.class);

        Predicate datePredicate = cb.greaterThan(departamento.get("fecha"), fecha);
        cq.where(datePredicate);

        TypedQuery<Departamento> query = em.createQuery(cq);
        return query.getResultList();
    }


}
