package Services.Facturas;

import Models.Comprobantes.Detalles.Impuesto;
import Services.GService;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 *
 * @author Al
 */

@Stateless
public class ImpuestoService extends GService<Impuesto>{
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<Impuesto> getEntityClass() {
        return Impuesto.class;
    }
    
    @Override
    public void create(Impuesto impuesto) {
        try {
            em.persist(impuesto);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
}
