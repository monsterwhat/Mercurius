package Services.Facturas;

import Models.Encabezado.MedioPago;
import Services.GService;
import org.jboss.logging.Logger;
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

    private static final Logger LOG = Logger.getLogger(MedioPagoService.class);
    
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
                        LOG.warn("Error creating Entity!" + " | source=" + "MedioPagoService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
}
