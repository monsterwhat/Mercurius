package Models;

import java.math.BigDecimal;
import java.util.Objects;
import lombok.Data;

//En Array representa el carrito con sus cantidades.

@Data
public class ArticuloCarrito {

    private Articulos articulo;

    private Double cantidad;
    
    private BigDecimal descuento;
    
    private boolean isPromo;
    
    private Promocion promocion;

    public ArticuloCarrito(Articulos articulo, Double cantidad) {
        this.articulo = articulo;
        this.cantidad = cantidad;
        this.isPromo = false; // Inicializamos isPromo en false por defecto
    }

    public ArticuloCarrito() {
        this.cantidad = 1.0;
        this.isPromo = false; // Inicializamos isPromo en false por defecto
    }
    
    @Override
    public String toString(){
        return "Art: " + articulo.getNombre() + " , Cant: " + cantidad + " , isPromo:" + isPromo;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ArticuloCarrito that = (ArticuloCarrito) o;
        return isPromo == that.isPromo && // Comparar también isPromo
               Objects.equals(articulo, that.articulo) &&
               Objects.equals(cantidad, that.cantidad);  // Compare both articulo and cantidad
    }

    @Override
    public int hashCode() {
        return Objects.hash(articulo, cantidad, isPromo);  // Asegúrate de que el hash esté basado en articulo, cantidad e isPromo
    }
    
    public BigDecimal getTotalArticulo(){
        if(this.isPromo){
            var discount = this.getDescuento() != null ? this.getDescuento() : BigDecimal.ZERO;
            var articulo = this.getArticulo();
            var tax = BigDecimal.valueOf(this.getArticulo().getCodigoCabys().getImpuesto());
            
            var precioArticulo = articulo.getLastPrecio().getPrecioConUtilidad();
            var cantidadDescuento = precioArticulo.multiply(discount.divide(BigDecimal.valueOf(100)));
            
            var precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            
            var cantidadImpuesto = precioConDescuento.multiply(tax.divide(BigDecimal.valueOf(100)));
            var precioFinal = precioConDescuento.add(cantidadImpuesto);
            
            return precioFinal;
            
        }else{
            var articulo = this.getArticulo();
            var precioArticulo = articulo.getLastPrecio().getPrecioConUtilidad();
            return precioArticulo;
        }
    }
    
    public BigDecimal getTotalArticulos(){
        if(this.isPromo){
            var discount = this.getDescuento() != null ? this.getDescuento() : BigDecimal.ZERO;
            var articulo = this.getArticulo();
            var tax = BigDecimal.valueOf(this.getArticulo().getCodigoCabys().getImpuesto());
            
            var precioArticulo = articulo.getLastPrecio().getPrecioConUtilidad();
            var cantidadDescuento = precioArticulo.multiply(discount.divide(BigDecimal.valueOf(100)));
            
            var precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            
            var cantidadImpuesto = precioConDescuento.multiply(tax.divide(BigDecimal.valueOf(100)));
            var precioFinal = precioConDescuento.add(cantidadImpuesto);
            
            return precioFinal;
            
        }else{
            var articulo = this.getArticulo();
            var precioArticulo = articulo.getLastPrecio().getPrecioConUtilidad();
            return precioArticulo.multiply(BigDecimal.valueOf(this.getCantidad()));
        }
    }
    
}
