package Services.Facturas;

import Models.ComprobantesV44.Encabezado.Encabezado;
import Services.GService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 *
 * @author Al
 */

@ApplicationScoped
public class EncabezadoService extends GService<Encabezado> {
    
    @PersistenceContext EntityManager em;
    
    @Override
    protected Class<Encabezado> getEntityClass() {
        return Encabezado.class;
    }
    
    @Override
    public void create(Encabezado encabezado) {
        try {
            em.persist(encabezado);
        } catch (Exception e) {
            System.out.println("Error creating Entity!");
        }
    }
    
}
