package Services.Facturas;

import Models.Comprobantes.Encabezado.MedioPago;
import Services.GService;
import jakarta.ejb.Stateless;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 *
 * @author Al
 */

@Stateless
public class MedioPagoService extends GService<MedioPago>  {
    
    @PersistenceContext EntityManager em;

    @Override
    protected Class<MedioPago> getEntityClass() {
        return MedioPago.class;
    }
    
    @Override
    public void create(MedioPago medioPago) {
        try {
            em.persist(medioPago);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
}
