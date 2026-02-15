package Services.Facturas;

import Models.Detalles.CodigoComercial;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped; 

/**
 *
 * @author Al
 */

@ApplicationScoped
public class CodigoComercialService extends GService<CodigoComercial>{
      
    @Override
    protected Class<CodigoComercial> getEntityClass() {
        return CodigoComercial.class;
    }
    
    @Override
    public void create(CodigoComercial codigoComercial) {
        try {
            this.em.persist(codigoComercial);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
}
