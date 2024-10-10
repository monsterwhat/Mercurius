package Services;

import Models.Registros.Alertas;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;

/**
 *
 * @author Al
 */

@Named
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
