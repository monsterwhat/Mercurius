package Services.Facturas;

import Models.Detalles.Descuento;
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
public class DescuentoService extends GService<Descuento>{

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DescuentoService.class.getName());
    
    @PersistenceContext @Nonnull EntityManager em;

    @Override
    @Nonnull
    protected Class<Descuento> getEntityClass() {
        return Descuento.class;
    }
    
    @Override
    @Transactional
    public void create(@Nonnull Descuento descuento) {
        try {
            em.persist(descuento);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating Entity!" + " | source=" + "DescuentoService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
}
