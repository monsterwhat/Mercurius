package Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
 
import Models.Articulos.Carrito.ArticuloCarrito;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CarritoCalculations {

    /**
     * MathContext for all financial divisions — DECIMAL128 prevents
     * ArithmeticException on non-terminating decimals like 1/3.
     */
    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * Returns the IVA tax rate as a BigDecimal percentage (e.g. 13 for 13%, 0.5 for 0.5%).
     * Returns ZERO if the rate is missing, empty, or unparseable.
     */
    @Nonnull
    private static BigDecimal getImpuestoRate(ArticuloCarrito item) {
        if (item == null || item.getArticulo() == null || item.getArticulo().getCodigoCabys() == null
                || item.getArticulo().getCodigoCabys().getImpuesto() == null
                || item.getArticulo().getCodigoCabys().getImpuesto().isEmpty()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(item.getArticulo().getCodigoCabys().getImpuesto());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Caps a discount percentage at 100 — prevents negative prices.
     */
    @Nonnull
    private static BigDecimal capDiscount(@Nullable BigDecimal discount) {
        if (discount == null || discount.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        return discount.min(BigDecimal.valueOf(100));
    }

    @Nonnull
    public static BigDecimal getTotalArticulo(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        if (articuloCarrito.isPromo()) {
            var discount = capDiscount(articuloCarrito.getDescuento());
            var tax = getImpuestoRate(articuloCarrito);
            var precioArticulo = articuloCarrito.getPrecioEfectivo();
            var cantidadDescuento = precioArticulo.multiply(discount.divide(BigDecimal.valueOf(100), MC), MC);
            var precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            var cantidadImpuesto = precioConDescuento.multiply(tax.divide(BigDecimal.valueOf(100), MC), MC);
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
            BigDecimal discount = capDiscount(articuloCarrito.getDescuento());
            BigDecimal tax = getImpuestoRate(articuloCarrito);
            BigDecimal cantidadDescuento = precioArticulo.multiply(discount, MC).divide(BigDecimal.valueOf(100), MC);
            BigDecimal precioConDescuento = precioArticulo.subtract(cantidadDescuento);
            BigDecimal cantidadImpuesto = precioConDescuento.multiply(tax, MC).divide(BigDecimal.valueOf(100), MC);
            BigDecimal precioFinal = precioConDescuento.add(cantidadImpuesto);
            total = precioFinal.multiply(articuloCarrito.getCantidad(), MC);
        } else {
            total = precioArticulo.multiply(articuloCarrito.getCantidad(), MC);
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    @Nonnull
    public static BigDecimal getArticuloConDescuento(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var descuento = capDiscount(articuloCarrito.getDescuento());
        var precioConUtilidad = articuloCarrito.getPrecioEfectivo();
        BigDecimal tax = getImpuestoRate(articuloCarrito);
        var taxPercentage = tax.divide(BigDecimal.valueOf(100), MC);

        BigDecimal applicableTax, precioFinal;

        if (descuento.compareTo(BigDecimal.ZERO) > 0) {
            var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100), MC);
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage, MC);
            var newPrecio = precioConUtilidad.subtract(descuentoTotal);
            applicableTax = newPrecio.multiply(taxPercentage, MC);
            precioFinal = newPrecio.add(applicableTax);
        } else {
            applicableTax = precioConUtilidad.multiply(taxPercentage, MC);
            precioFinal = precioConUtilidad.add(applicableTax);
        }

        return precioFinal;
    }

    @Nonnull
    public static BigDecimal getTotalDescuento(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null || articuloCarrito.getDescuento() == null) {
            return BigDecimal.ZERO;
        }
        var descuento = capDiscount(articuloCarrito.getDescuento());
        var precioConUtilidad = articuloCarrito.getPrecioEfectivo();
        var descuentoPercentage = descuento.divide(BigDecimal.valueOf(100), MC);
        var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage, MC);
        return descuentoTotal;
    }

    @Nonnull
    public static BigDecimal getTotalImpuesto(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        var descuento = articuloCarrito.getDescuento();
        var precioConUtilidad = articuloCarrito.getPrecioEfectivo();
        BigDecimal tax = getImpuestoRate(articuloCarrito);
        var taxPercentage = tax.divide(BigDecimal.valueOf(100), MC);

        BigDecimal applicableTax;

        if (descuento != null && descuento.compareTo(BigDecimal.ZERO) > 0) {
            var cappedDiscount = capDiscount(descuento);
            var descuentoPercentage = cappedDiscount.divide(BigDecimal.valueOf(100), MC);
            var descuentoTotal = precioConUtilidad.multiply(descuentoPercentage, MC);
            var totalConDescuento = precioConUtilidad.subtract(descuentoTotal);
            applicableTax = totalConDescuento.multiply(taxPercentage, MC);
        } else {
            applicableTax = precioConUtilidad.multiply(taxPercentage, MC);
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
                var taxDecimal = tax.divide(BigDecimal.valueOf(100), MC);

                BigDecimal precioFinal;
                BigDecimal precioUnidad = item != null ? item.getPrecioEfectivo() : BigDecimal.ZERO;
                BigDecimal cantidadDecimal = cantidad;

                if (isPromo) {
                    precioFinal = getArticuloConDescuento(item);
                } else {
                    precioFinal = precioUnidad;
                    var totalImpuestos = precioFinal.multiply(taxDecimal, MC);
                    precioFinal = precioFinal.add(totalImpuestos);
                }

                BigDecimal subtotal = precioFinal.multiply(cantidadDecimal, MC);
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
                BigDecimal subtotal = totalItem.multiply(cantidad, MC);
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
                BigDecimal subtotal = totalItem.multiply(cantidad, MC);
                total = total.add(subtotal);
            }
        }
        return total;
    }

    /**
     * Groups total tax by rate. Uses BigDecimal keys to support fractional rates like 0.5%.
     */
    @Nonnull
    public static Map<BigDecimal, BigDecimal> calculateTotalTaxByRate(@Nullable List<ArticuloCarrito> carrito) {
        Map<BigDecimal, BigDecimal> taxTotals = new HashMap<>();

        if (carrito != null && !carrito.isEmpty()) {
            for (ArticuloCarrito item : carrito) {
                BigDecimal taxRate = getImpuestoRate(item);
                BigDecimal itemTaxTotal = getTotalImpuesto(item).multiply(item.getCantidad(), MC);
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

                    BigDecimal porcentajeDescuento = capDiscount(descuento).divide(BigDecimal.valueOf(100), MC);

                    BigDecimal descuentoPromo = precioConUtilidad.multiply(porcentajeDescuento, MC);
                    BigDecimal precioFinal = precioConUtilidad.subtract(descuentoPromo);

                    BigDecimal porcentajeImpuesto = getImpuestoRate(articulo).divide(BigDecimal.valueOf(100), MC);
                    BigDecimal iva = precioFinal.multiply(porcentajeImpuesto, MC);

                    BigDecimal precioConUtilidadEIVA = precioFinal.add(iva);

                    totalPromo = totalPromo.add(precioConUtilidadEIVA.multiply(articulo.getCantidad(), MC));
                }

                return totalPromo;
            }
            return BigDecimal.ZERO;

        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }
}
