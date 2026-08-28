package Services.Facturas;

import Models.Detalles.CodigoComercial;
import Services.GService;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class CodigoComercialService extends GService<CodigoComercial>{

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(CodigoComercialService.class.getName());
      
    @Override
    @Nonnull
    protected Class<CodigoComercial> getEntityClass() {
        return CodigoComercial.class;
    }
    
    @Override
    @Transactional
    public void create(@Nonnull CodigoComercial codigoComercial) {
        try {
            this.em.persist(codigoComercial);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error creating Entity!" + " | source=" + "CodigoComercialService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
}
