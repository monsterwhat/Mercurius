package Models;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    public BigDecimal getTotalArticulos() {
        var articulo = this.getArticulo();
        var precioArticulo = articulo.getLastPrecio().getPrecioConUtilidad();
        BigDecimal total;

        if (this.isPromo) {
            // Use BigDecimal.ZERO to avoid potential nulls for descuento
            BigDecimal discount = this.getDescuento() != null ? this.getDescuento() : BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.valueOf(articulo.getCodigoCabys().getImpuesto());

            // Calculate the discount amount
            BigDecimal cantidadDescuento = precioArticulo.multiply(discount).divide(BigDecimal.valueOf(100));
            BigDecimal precioConDescuento = precioArticulo.subtract(cantidadDescuento);

            // Calculate the tax amount
            BigDecimal cantidadImpuesto = precioConDescuento.multiply(tax).divide(BigDecimal.valueOf(100));
            BigDecimal precioFinal = precioConDescuento.add(cantidadImpuesto);

            // Calculate the total based on quantity
            total = precioFinal.multiply(BigDecimal.valueOf(this.getCantidad()));
        } else {
            // For non-promo items, calculate total based on quantity directly
            total = precioArticulo.multiply(BigDecimal.valueOf(this.getCantidad()));
        }

        return total.setScale(2, RoundingMode.HALF_UP); // Ensuring 2 decimal places for monetary values
    }

    
    public BigDecimal getArticuloConDescuento() {
        // Get the Articulo and necessary values
        var Articulo = this.articulo;
        var descuento = this.descuento;
        var precioConUtilidad = Articulo.getLastPrecio().getPrecioConUtilidad();
        double tax = Articulo.getCodigoCabys().getImpuesto();

        // Calculate the tax percentage and discount percentage
        var taxPercentage = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));
       
        BigDecimal applicableTax, precioFinal;
        
        if(descuento != null){
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));

            // Calculate the total discount and new price after discount
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);

            var newPrecio = precioConUtilidad.subtract(descuentoTotal); // Subtract discount
            
            // Calculate the applicable tax on the new price after discount
            applicableTax = newPrecio.multiply(taxPercentage);
            
            // Calculate the final price
            precioFinal = newPrecio.add(applicableTax);
        }else{
            // Calculate the applicable tax on the new price after discount
            applicableTax = precioConUtilidad.multiply(taxPercentage);
            // Calculate the final price
            precioFinal = precioConUtilidad.add(applicableTax);
        }
        
        return precioFinal;
    }
    
    public BigDecimal getTotalDescuento() {
        if(this.descuento == null){
            return BigDecimal.ZERO;
        }
        // Obtener el Articulo y los valores necesarios
        var Articulo = this.articulo;
        var descuento = this.descuento;
        var precioConUtilidad = Articulo.getLastPrecio().getPrecioConUtilidad();

        // Calcular el porcentaje de descuento
        var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));

        // Calcular el descuento total
        var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);

        return descuentoTotal; // Retornar solo el total del descuento
    }

    public BigDecimal getTotalImpuesto() {
        // Obtener el Articulo y los valores necesarios
        var Articulo = this.articulo;
        var descuento = this.descuento;
        
        var precioConUtilidad = Articulo.getLastPrecio().getPrecioConUtilidad();
        double tax = Articulo.getCodigoCabys().getImpuesto();
        
        var applicableTax = BigDecimal.ZERO;
        
        // Calcular el porcentaje de impuesto
        var taxPercentage = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));
        if(descuento != null){
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));

            // Calcular el descuento total
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);

            // Calcular el nuevo precio después del descuento
            var totalConDescuento = precioConUtilidad.subtract(descuentoTotal);
            
            applicableTax = totalConDescuento.multiply(taxPercentage);

        }else{
            // Calcular el impuesto aplicable sobre el nuevo precio después del descuento
            applicableTax = precioConUtilidad.multiply(taxPercentage);
        }

        return applicableTax; // Retornar solo el total del impuesto
    }
    
    public BigDecimal calculateTotalCarrito(List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var articulo = item.getArticulo();
                var cantidad = item.getCantidad();
                var isPromo = item.isPromo();
                var tax = articulo.getCodigoCabys().getImpuesto();
                var taxDecimal = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

                BigDecimal precioFinal;
                BigDecimal precioUnidad = articulo.getLastPrecio().getPrecioConUtilidad();
                BigDecimal cantidadDecimal = BigDecimal.valueOf(cantidad);

                // Determine final price based on promotional status
                if (isPromo) {
                    precioFinal = item.getArticuloConDescuento();  // Price after discount INCLUDES TAXES...
                } else {
                    precioFinal = precioUnidad;  // Regular price
                    // Calculate total tax based on the final price after discount
                    var totalImpuestos = precioFinal.multiply(taxDecimal);

                    // Add tax to the final price to get the total price for the item
                    precioFinal = precioFinal.add(totalImpuestos);
                }                

                // Calculate subtotal for this item based on quantity
                BigDecimal subtotal = precioFinal.multiply(cantidadDecimal);

                // Add subtotal to the overall total
                total = total.add(subtotal);
            }
        }
        return total;
    }
    
    /**
     * Calculates the total tax for all unique tax rates present in the cart items.
     *
     * @param carrito the list of items in the cart
     * @return a map containing total taxes for each unique tax rate
     */
    public static Map<Integer, BigDecimal> calculateTotalTaxForUniqueRates(List<ArticuloCarrito> carrito) {
        Map<Integer, BigDecimal> taxTotals = new HashMap<>();

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                int taxRate = item.articulo.getCodigoCabys().getImpuesto(); // Get tax rate from the item

                // Calculate the tax for the current item
                BigDecimal itemTaxTotal = item.getTotalImpuesto().multiply(BigDecimal.valueOf(item.cantidad));

                // Sum the tax for this rate
                taxTotals.merge(taxRate, itemTaxTotal, BigDecimal::add);
            }
        }

        // Round the total tax amounts to 2 decimal places
        taxTotals.replaceAll((rate, total) -> total.setScale(2, RoundingMode.HALF_UP));

        return taxTotals;
    }
    
    
}
