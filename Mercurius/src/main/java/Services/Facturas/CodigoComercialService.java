package Services.Facturas;

import Models.ComprobantesV44.Detalles.CodigoComercial;
import Services.GService;
import jakarta.ejb.Stateless; 

/**
 *
 * @author Al
 */

@Stateless
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
