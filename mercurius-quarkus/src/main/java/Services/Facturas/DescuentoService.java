package Services.Facturas;

import Models.ComprobantesV44.Detalles.Descuento;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class DescuentoService extends GService<Descuento>{
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<Descuento> getEntityClass() {
        return Descuento.class;
    }
    
    @Override
    public void create(Descuento descuento) {
        try {
            em.persist(descuento);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
}
