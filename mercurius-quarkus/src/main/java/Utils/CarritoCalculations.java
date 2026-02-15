package Utils;
 
import Models.Articulos.Carrito.ArticuloCarrito;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CarritoCalculations {

    public static BigDecimal getTotalArticulo(ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        if (articuloCarrito.isPromo()) {
            var discount = articuloCarrito.getDescuento() != null ? articuloCarrito.getDescuento() : BigDecimal.ZERO;
            var tax = BigDecimal.valueOf(articuloCarrito.getArticulo().getCodigoCabys().getImpuesto());
            var precioArticulo = articuloCarrito.getArticulo().getLastPrecio().getPrecioConUtilidad();
            var cantidadDescuento = precioArticulo.multiply(discount.divide(BigDecimal.valueOf(100)));
            var precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            var cantidadImpuesto = precioConDescuento.multiply(tax.divide(BigDecimal.valueOf(100)));
            var precioFinal = precioConDescuento.add(cantidadImpuesto);
            return precioFinal;
        } else {
            var precioArticulo = articuloCarrito.getArticulo().getLastPrecio().getPrecioConUtilidad();
            return precioArticulo;
        }
    }

    public static BigDecimal getTotalArticulos(ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var precioArticulo = articuloCarrito.getArticulo().getLastPrecio().getPrecioConUtilidad();
        BigDecimal total;

        if (articuloCarrito.isPromo()) {
            BigDecimal discount = articuloCarrito.getDescuento() != null ? articuloCarrito.getDescuento() : BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.valueOf(articuloCarrito.getArticulo().getCodigoCabys().getImpuesto());
            BigDecimal cantidadDescuento = precioArticulo.multiply(discount).divide(BigDecimal.valueOf(100));
            BigDecimal precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            BigDecimal cantidadImpuesto = precioConDescuento.multiply(tax).divide(BigDecimal.valueOf(100));
            BigDecimal precioFinal = precioConDescuento.add(cantidadImpuesto);
            total = precioFinal.multiply(articuloCarrito.getCantidad());
        } else {
            total = precioArticulo.multiply(articuloCarrito.getCantidad());
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal getArticuloConDescuento(ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getArticulo().getLastPrecio().getPrecioConUtilidad();
        double tax = articuloCarrito.getArticulo().getCodigoCabys().getImpuesto();
        var taxPercentage = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

        BigDecimal applicableTax, precioFinal;

        if (descuento != null) {
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);
            var newPrecio = precioConUtilidad.subtract(descuentoTotal);
            applicableTax = newPrecio.multiply(taxPercentage);
            precioFinal = newPrecio.add(applicableTax);
        } else {
            applicableTax = precioConUtilidad.multiply(taxPercentage);
            precioFinal = precioConUtilidad.add(applicableTax);
        }

        return precioFinal;
    }

    public static BigDecimal getTotalDescuento(ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null || articuloCarrito.getDescuento() == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getArticulo().getLastPrecio().getPrecioConUtilidad();
        var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));
        var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);
        return descuentoTotal;
    }

    public static BigDecimal getTotalImpuesto(ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getArticulo().getLastPrecio().getPrecioConUtilidad();
        double tax = articuloCarrito.getArticulo().getCodigoCabys().getImpuesto();
        var taxPercentage = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

        BigDecimal applicableTax;

        if (descuento != null) {
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);
            var totalConDescuento = precioConUtilidad.subtract(descuentoTotal);
            applicableTax = totalConDescuento.multiply(taxPercentage);
        } else {
            applicableTax = precioConUtilidad.multiply(taxPercentage);
        }

        return applicableTax;
    }

    public static BigDecimal calculateTotalCarrito(List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var articulo = item;
                var cantidad = item.getCantidad();
                var isPromo = item.isPromo();
                var tax = articulo.getArticulo().getCodigoCabys().getImpuesto();
                var taxDecimal = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

                BigDecimal precioFinal;
                BigDecimal precioUnidad = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();
                BigDecimal cantidadDecimal = cantidad;

                if (isPromo) {
                    precioFinal = getArticuloConDescuento(item);
                } else {
                    precioFinal = precioUnidad;
                    var totalImpuestos = precioFinal.multiply(taxDecimal);
                    precioFinal = precioFinal.add(totalImpuestos);
                }

                BigDecimal subtotal = precioFinal.multiply(cantidadDecimal);
                total = total.add(subtotal);
            }
        }
        return total;
    }

    public static BigDecimal calculateTotalDescuento(List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var totalItem = getTotalDescuento(item);
                var cantidad = item.getCantidad();
                BigDecimal subtotal = totalItem.multiply(cantidad);
                total = total.add(subtotal);
            }
        }
        return total;
    }

    public static BigDecimal calculateTotalImpuesto(List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var totalItem = getTotalImpuesto(item);
                var cantidad = item.getCantidad();
                BigDecimal subtotal = totalItem.multiply(cantidad);
                total = total.add(subtotal);
            }
        }
        return total;
    }

    public static Map<Integer, BigDecimal> calculateTotalTaxByRate(List<ArticuloCarrito> carrito) {
        Map<Integer, BigDecimal> taxTotals = new HashMap<>();

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                int taxRate = item.getArticulo().getCodigoCabys().getImpuesto();
                BigDecimal itemTaxTotal = getTotalImpuesto(item).multiply(item.getCantidad());
                taxTotals.merge(taxRate, itemTaxTotal, BigDecimal::add);
            }
        }

        taxTotals.replaceAll((rate, total) -> total.setScale(2, RoundingMode.HALF_UP));

        return taxTotals;
    }

    public static BigDecimal calculateTotalPromo(List<ArticuloCarrito> lista, BigDecimal descuento) {
        try {
            BigDecimal totalPromo = BigDecimal.ZERO;

            if (lista != null) {
                for (ArticuloCarrito articulo : lista) {
                    BigDecimal precioConUtilidad = articulo.getArticulo().getLastPrecio().getPrecioConUtilidad();

                    BigDecimal porcentajeDescuento = (descuento != null ? descuento : BigDecimal.ZERO).divide(BigDecimal.valueOf(100));

                    BigDecimal descuentoPromo = precioConUtilidad.multiply(porcentajeDescuento);
                    BigDecimal precioFinal = precioConUtilidad.subtract(descuentoPromo);

                    BigDecimal porcentajeImpuesto = BigDecimal.valueOf(articulo.getArticulo().getCodigoCabys().getImpuesto()).divide(BigDecimal.valueOf(100));
                    BigDecimal iva = precioFinal.multiply(porcentajeImpuesto);

                    BigDecimal precioConUtilidadEIVA = precioFinal.add(iva);

                    totalPromo = totalPromo.add(precioConUtilidadEIVA.multiply(articulo.getCantidad()));
                }

                return totalPromo;
            }
            return BigDecimal.ZERO;

        } catch (Exception e) {
            System.err.println("Error calculating totalPromo: " + e.getMessage());
            return BigDecimal.ZERO;
        }
    }
}
