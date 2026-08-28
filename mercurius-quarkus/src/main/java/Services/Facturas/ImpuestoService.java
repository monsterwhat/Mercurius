package Services.Facturas;

import Models.Detalles.Impuesto;
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
public class ImpuestoService extends GService<Impuesto>{

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ImpuestoService.class.getName());
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<Impuesto> getEntityClass() {
        return Impuesto.class;
    }
    
    @Override
    @Transactional
    public void create(@Nonnull Impuesto impuesto) {
        try {
            em.persist(impuesto);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating Entity!" + " | source=" + "ImpuestoService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
}
