package Services;

import Models.Departamento;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
public class DepartamentoService extends GService<Departamento> {

    @Override
    protected Class<Departamento> getEntityClass() {
        return Departamento.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Departamento entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
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
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    @Override
    public void update(Departamento entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Departamento> listAll() {
        try {
            TypedQuery<Departamento> query = em.createQuery("SELECT d FROM Departamento d", Departamento.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public Departamento findById(Integer id) {
    try {
        return em.find(getEntityClass(), id);
    } catch (Exception e) {
        System.out.println("Error finding entity by ID: " + e.toString());
        return null;
    }
}

}
