package Services.Facturas;

import Models.Encabezado.MedioPago;
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
public class MedioPagoService extends GService<MedioPago>  {
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<MedioPago> getEntityClass() {
        return MedioPago.class;
    }
    
    @Override
    public void create(@Nonnull MedioPago medioPago) {
        try {
            em.persist(medioPago);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "MedioPagoService.create()", null, e.getMessage());
        }
    }
    
}
