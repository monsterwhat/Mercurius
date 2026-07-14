package Services.Facturas;

import Models.Detalles.Impuesto;
import Services.GService;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class ImpuestoService extends GService<Impuesto>{
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<Impuesto> getEntityClass() {
        return Impuesto.class;
    }
    
    @Override
    public void create(@Nonnull Impuesto impuesto) {
        try {
            em.persist(impuesto);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "ImpuestoService.create()", null, e.getMessage());
        }
    }
    
}
