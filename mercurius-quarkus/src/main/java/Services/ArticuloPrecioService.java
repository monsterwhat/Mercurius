package Services;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
public class ArticuloPrecioService extends GService<ArticuloPrecio> {

    @Override
    protected Class<ArticuloPrecio> getEntityClass() {
        return ArticuloPrecio.class;
    }

    @PostConstruct
    public void init() {
    }
    
    @Override
    public List<ArticuloPrecio> listAll() {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a", ArticuloPrecio.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ArticuloPrecioService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public ArticuloPrecio findByArticulo(Articulos articulo) {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a WHERE a.articulo = :articulo", ArticuloPrecio.class);
            query.setParameter("articulo", articulo);
            List<ArticuloPrecio> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(resultList.size() - 1);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error " + e.getLocalizedMessage(), null, 0, "ArticuloPrecioService.method()", null, e.getMessage());
            return null;
        }    
    }
    
    public List<ArticuloPrecio> findAllByArticulo(Articulos articulo) {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a WHERE a.articulo = :articulo", ArticuloPrecio.class);
            query.setParameter("articulo", articulo);
            List<ArticuloPrecio> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error " + e.getLocalizedMessage(), null, 0, "ArticuloPrecioService.method()", null, e.getMessage());
            return null;
        }    
    }

}
