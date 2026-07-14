package Services.Facturas;

import Models.Detalles.CodigoComercial;
import Services.GService;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException; 

/**
 *
 * @author Al
 */

@ApplicationScoped
public class CodigoComercialService extends GService<CodigoComercial>{
      
    @Override
    @Nonnull
    protected Class<CodigoComercial> getEntityClass() {
        return CodigoComercial.class;
    }
    
    @Override
    public void create(@Nonnull CodigoComercial codigoComercial) {
        try {
            this.em.persist(codigoComercial);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "CodigoComercialService.create()", null, e.getMessage());
        }
    }
    
}
