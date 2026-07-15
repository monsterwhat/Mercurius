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
            alertasService.registrarAlerta("Error", "Error creating Entity!", null, 0, "DescuentoService.create()", null, e.getMessage());
        }
    }
    
}
