package Services.Facturas;

import Models.Encabezado.MedioPago;
import Services.GService;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class MedioPagoService extends GService<MedioPago>  {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(MedioPagoService.class.getName());
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<MedioPago> getEntityClass() {
        return MedioPago.class;
    }
    
    @Transactional
    @Override
    public void create(@Nonnull MedioPago medioPago) {
        try {
            em.persist(medioPago);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating Entity!" + " | source=" + "MedioPagoService.create()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
}
