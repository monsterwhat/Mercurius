package Services;

import Models.ProductoExoneracion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;

@Named
@ApplicationScoped
public class ProductoExoneracionService {
    
    @PersistenceContext
    EntityManager em;
    
    @Transactional
    public ProductoExoneracion save(ProductoExoneracion exoneracion) {
        if (exoneracion.getId() == null) {
            em.persist(exoneracion);
            return exoneracion;
        } else {
            return em.merge(exoneracion);
        }
    }
    
    public ProductoExoneracion findByArticuloCodigo(String codigo) {
        if (codigo == null || codigo.isEmpty()) {
            return null;
        }
        try {
            return em.createQuery("SELECT e FROM ProductoExoneracion e WHERE e.articuloCodigo = :codigo", ProductoExoneracion.class)
                .setParameter("codigo", codigo)
                .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            return null;
        } catch (jakarta.persistence.NonUniqueResultException e) {
            return em.createQuery("SELECT e FROM ProductoExoneracion e WHERE e.articuloCodigo = :codigo", ProductoExoneracion.class)
                .setParameter("codigo", codigo)
                .setMaxResults(1)
                .getSingleResult();
        }
    }
    
    @Transactional
    public void delete(Long id) {
        ProductoExoneracion exoneracion = em.find(ProductoExoneracion.class, id);
        if (exoneracion != null) {
            em.remove(exoneracion);
        }
    }
}
