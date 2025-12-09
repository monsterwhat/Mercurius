package Services;
 
import Models.Articulos.Carrito.ArticuloCarrito;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named; 

@Named
@ApplicationScoped
public class ArticuloCarritoService extends GService<ArticuloCarrito> {

    @Override
    protected Class<ArticuloCarrito> getEntityClass() {
        return ArticuloCarrito.class;
    }

    @PostConstruct
    public void init() {
    }
      
}
