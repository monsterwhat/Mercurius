package Services;
 
import Models.Articulos.Carrito.ArticuloCarrito;
import jakarta.annotation.PostConstruct;
import jakarta.ejb.Stateless; 
import jakarta.inject.Named; 

@Named
@Stateless
public class ArticuloCarritoService extends GService<ArticuloCarrito> {

    @Override
    protected Class<ArticuloCarrito> getEntityClass() {
        return ArticuloCarrito.class;
    }

    @PostConstruct
    public void init() {
    }
      
}
