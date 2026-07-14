package Services;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
public class ArticuloPrecioService extends GService<ArticuloPrecio> {

    @Override
    protected @Nonnull Class<ArticuloPrecio> getEntityClass() {
        return ArticuloPrecio.class;
    }

    @PostConstruct
    public void init() {
    }
    
    @Override
    public @Nullable List<ArticuloPrecio> listAll() {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a", ArticuloPrecio.class);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "ArticuloPrecioService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public @Nullable ArticuloPrecio findByArticulo(@Nonnull Articulos articulo) {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a WHERE a.articulo = :articulo", ArticuloPrecio.class);
            query.setParameter("articulo", articulo);
            List<ArticuloPrecio> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(resultList.size() - 1);
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error " + e.getLocalizedMessage(), null, 0, "ArticuloPrecioService.method()", null, e.getMessage());
            return null;
        }    
    }
    
    public @Nullable List<ArticuloPrecio> findAllByArticulo(@Nonnull Articulos articulo) {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a WHERE a.articulo = :articulo", ArticuloPrecio.class);
            query.setParameter("articulo", articulo);
            List<ArticuloPrecio> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList;
        } catch (jakarta.persistence.PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error " + e.getLocalizedMessage(), null, 0, "ArticuloPrecioService.method()", null, e.getMessage());
            return null;
        }    
    }

}
