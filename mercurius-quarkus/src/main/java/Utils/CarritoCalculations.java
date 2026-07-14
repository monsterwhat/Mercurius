package Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
 
import Models.Articulos.Carrito.ArticuloCarrito;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CarritoCalculations {

    private static double getImpuestoRate(ArticuloCarrito item) {
        if (item == null || item.getArticulo() == null || item.getArticulo().getCodigoCabys() == null) {
            return 0.0;
        }
        return item.getArticulo().getCodigoCabys().getImpuesto();
    }

    @Nonnull
    public static BigDecimal getTotalArticulo(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        if (articuloCarrito.isPromo()) {
            var discount = articuloCarrito.getDescuento() != null ? articuloCarrito.getDescuento() : BigDecimal.ZERO;
            var tax = BigDecimal.valueOf(getImpuestoRate(articuloCarrito));
            var precioArticulo = articuloCarrito.getPrecioEfectivo();
            var cantidadDescuento = precioArticulo.multiply(discount.divide(BigDecimal.valueOf(100)));
            var precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            var cantidadImpuesto = precioConDescuento.multiply(tax.divide(BigDecimal.valueOf(100)));
            var precioFinal = precioConDescuento.add(cantidadImpuesto);
            return precioFinal;
        } else {
            var precioArticulo = articuloCarrito.getPrecioEfectivo();
            return precioArticulo;
        }
    }

    @Nonnull
    public static BigDecimal getTotalArticulos(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var precioArticulo = articuloCarrito.getPrecioEfectivo();
        BigDecimal total;

        if (articuloCarrito.isPromo()) {
            BigDecimal discount = articuloCarrito.getDescuento() != null ? articuloCarrito.getDescuento() : BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.valueOf(getImpuestoRate(articuloCarrito));
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

    @Nonnull
    public static BigDecimal getArticuloConDescuento(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getPrecioEfectivo();
        double tax = getImpuestoRate(articuloCarrito);
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

    @Nonnull
    public static BigDecimal getTotalDescuento(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null || articuloCarrito.getDescuento() == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getPrecioEfectivo();
        var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100));
        var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage);
        return descuentoTotal;
    }

    @Nonnull
    public static BigDecimal getTotalImpuesto(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getPrecioEfectivo();
        double tax = getImpuestoRate(articuloCarrito);
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

    @Nonnull
    public static BigDecimal calculateTotalCarrito(@Nullable List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                var cantidad = item.getCantidad();
                var isPromo = item.isPromo();
                var tax = getImpuestoRate(item);
                var taxDecimal = BigDecimal.valueOf(tax).divide(BigDecimal.valueOf(100));

                BigDecimal precioFinal;
                BigDecimal precioUnidad = item != null ? item.getPrecioEfectivo() : BigDecimal.ZERO;
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

    @Nonnull
    public static BigDecimal calculateTotalDescuento(@Nullable List<ArticuloCarrito> carrito) {
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

    @Nonnull
    public static BigDecimal calculateTotalImpuesto(@Nullable List<ArticuloCarrito> carrito) {
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

    @Nonnull
    public static Map<Integer, BigDecimal> calculateTotalTaxByRate(@Nullable List<ArticuloCarrito> carrito) {
        Map<Integer, BigDecimal> taxTotals = new HashMap<>();

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                int taxRate = (int) getImpuestoRate(item);
                BigDecimal itemTaxTotal = getTotalImpuesto(item).multiply(item.getCantidad());
                taxTotals.merge(taxRate, itemTaxTotal, BigDecimal::add);
            }
        }

        taxTotals.replaceAll((rate, total) -> total.setScale(2, RoundingMode.HALF_UP));

        return taxTotals;
    }

    @Nonnull
    public static BigDecimal calculateTotalPromo(@Nullable List<ArticuloCarrito> lista, @Nullable BigDecimal descuento) {
        try {
            BigDecimal totalPromo = BigDecimal.ZERO;

            if (lista != null) {
                for (ArticuloCarrito articulo : lista) {
                    if (articulo == null) continue;
                    BigDecimal precioConUtilidad = articulo.getPrecioEfectivo();

                    BigDecimal porcentajeDescuento = (descuento != null ? descuento : BigDecimal.ZERO).divide(BigDecimal.valueOf(100));

                    BigDecimal descuentoPromo = precioConUtilidad.multiply(porcentajeDescuento);
                    BigDecimal precioFinal = precioConUtilidad.subtract(descuentoPromo);

                    BigDecimal porcentajeImpuesto = BigDecimal.valueOf(getImpuestoRate(articulo)).divide(BigDecimal.valueOf(100));
                    BigDecimal iva = precioFinal.multiply(porcentajeImpuesto);

                    BigDecimal precioConUtilidadEIVA = precioFinal.add(iva);

                    totalPromo = totalPromo.add(precioConUtilidadEIVA.multiply(articulo.getCantidad()));
                }

                return totalPromo;
            }
            return BigDecimal.ZERO;

        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }
}
