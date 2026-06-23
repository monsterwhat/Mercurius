package Services.Facturas;

import Models.Detalles.Impuesto;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 *
 * @author Al
 */

@ApplicationScoped
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
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "ImpuestoService.create()", null, e.getMessage());
        }
    }
    
}
