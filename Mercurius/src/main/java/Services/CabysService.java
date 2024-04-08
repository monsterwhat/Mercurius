package Services;

import Models.CaByS;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;


/**
 *
 * @author Al
 */

@Named
public class CabysService extends GService<CaByS>{
    @Override
    protected Class<CaByS> getEntityClass() {
        return CaByS.class;
    }

    @PostConstruct
    public void init() {
    }
    
    @Override
    public void create(CaByS entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(CaByS entity) {
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
    
}
