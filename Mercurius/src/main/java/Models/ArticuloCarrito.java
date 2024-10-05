package Models;

import java.math.BigDecimal;
import lombok.Data;

//En Array representa el carrito con sus cantidades.

@Data
public class ArticuloCarrito {

    private Articulos articulo;

    private Double cantidad;
    
    private BigDecimal precioConDescuento;
    
    private boolean isPromo;

    public ArticuloCarrito(Articulos articulo, Double cantidad) {
        this.articulo = articulo;
        this.cantidad = cantidad;
    }

    public ArticuloCarrito() {
        this.cantidad = 1.0;
    }
    
    
}
