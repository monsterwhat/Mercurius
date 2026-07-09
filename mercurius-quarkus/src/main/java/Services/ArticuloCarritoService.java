package Services;
 
import Models.Articulos.Carrito.ArticuloCarrito;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named; 

@Named
@ApplicationScoped
public class ArticuloCarritoService extends GService<ArticuloCarrito> {

    @Override
    protected @Nonnull Class<ArticuloCarrito> getEntityClass() {
        return ArticuloCarrito.class;
    }

    @PostConstruct
    public void init() {
    }
      
}
