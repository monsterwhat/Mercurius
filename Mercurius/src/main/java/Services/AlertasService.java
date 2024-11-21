package Services;

import Models.Registros.Alertas;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Stateless; 
import jakarta.inject.Named;

/**
 *
 * @author Al
 */

@Named
@Stateless
public class AlertasService extends GService<Alertas> {

    @Override
    protected Class<Alertas> getEntityClass() {
        return Alertas.class;
    }

    @PostConstruct
    public void init() {
        
    }
    
    @Override
    public void create(Alertas alerta) {
        try {
            em.persist(alerta);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
    
}
