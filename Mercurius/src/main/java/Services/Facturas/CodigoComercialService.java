package Services.Facturas;

import Models.Comprobantes.Detalles.CodigoComercial;
import Services.GService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 *
 * @author Al
 */
public class CodigoComercialService extends GService<CodigoComercial>{
    
     @PersistenceContext EntityManager em;

    @Override
    protected Class<CodigoComercial> getEntityClass() {
        return CodigoComercial.class;
    }
    
    @Override
    public void create(CodigoComercial codigoComercial) {
        try {
            em.persist(codigoComercial);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
}
