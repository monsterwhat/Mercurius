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

    /**
     * Base sin IVA ANTES de cualquier descuento, en colones (2 decimales).
     * Es el precio de catalogo que ve el cajero.
     */
    @Nonnull
    public static BigDecimal getBaseUnitariaSinDescuento(@Nullable ArticuloCarrito item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        return item.getPrecioEfectivo().setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Base sin IVA que realmente paga el cliente, ya con el descuento aplicado.
     * Se deriva del precio final redondeado para que base + IVA sea exactamente
     * el monto cobrado (misma regla que el ajuste manual de precio).
     */
    @Nonnull
    public static BigDecimal getBaseNetaUnitaria(@Nullable ArticuloCarrito item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        if (item.hasPrecioAjustado() && !item.isPromo()) {
            return item.getPrecioEfectivo().setScale(2, RoundingMode.HALF_UP);
        }
        return getPrecioBaseDesdeFinal(item, getPrecioUnitarioFinal(item));
    }

    /**
     * Precio final unitario que paga el cliente (IVA incluido, 2 decimales).
     * Para una linea ajustada devuelve el monto exacto que fijo el cajero.
     */
    @Nonnull
    public static BigDecimal getPrecioFinalCobrado(@Nullable ArticuloCarrito item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        if (item.hasPrecioAjustado() && !item.isPromo()) {
            return item.getPrecioAjustadoFinal().setScale(2, RoundingMode.HALF_UP);
        }
        return getPrecioUnitarioFinal(item);
    }

    /** IVA contenido en el precio final unitario (2 decimales). */
    @Nonnull
    public static BigDecimal getImpuestoUnitario(@Nullable ArticuloCarrito item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        return getPrecioFinalCobrado(item).subtract(getBaseNetaUnitaria(item));
    }

    @Nonnull
    public static BigDecimal getTotalArticulo(@Nullable ArticuloCarrito articuloCarrito) {
        return getPrecioFinalCobrado(articuloCarrito);
    }

    /**
     * Precio unitario FINAL (IVA incluido) de una linea, aplicando el descuento
     * de promo/cliente cuando corresponda. Es el numero que ve el cajero y el
     * que se cobra al cliente.
     *
     * <p>La fuente de verdad es {@code articuloprecio.precioFinal} (el precio
     * de etiqueta ya redondeado a colones). Recalcularlo desde la base de 2
     * decimales hacia que 1106.19 x 1.13 = 1249.9947 y el total derivara un
     * centavo por unidad respecto de la factura original, que usa 1250.00.</p>
     */
    @Nonnull
    public static BigDecimal getPrecioUnitarioFinal(@Nullable ArticuloCarrito item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        if (item.hasPrecioAjustado() && !item.isPromo()) {
            return item.getPrecioAjustadoFinal().setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal finalSistema = null;
        if (item.getArticulo() != null) {
            var last = item.getArticulo().getLastPrecio();
            if (last != null && last.getPrecioFinal() != null) {
                finalSistema = last.getPrecioFinal();
            }
        }
        if (finalSistema == null) {
            finalSistema = getPrecioFinalDesdeBase(item, item.getPrecioEfectivo());
        }
        BigDecimal descuento = capDiscount(item.getDescuento());
        if (descuento.signum() > 0) {
            finalSistema = finalSistema.subtract(
                    finalSistema.multiply(descuento.divide(BigDecimal.valueOf(100), MC), MC), MC);
        }
        return finalSistema.setScale(2, RoundingMode.HALF_UP);
    }

    /** Aplica la tarifa de IVA del CABYS a una base sin impuesto. */
    @Nonnull
    public static BigDecimal getPrecioFinalDesdeBase(
            @Nullable ArticuloCarrito item, @Nullable BigDecimal baseSinIva) {
        if (item == null || baseSinIva == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = getImpuestoRate(item).divide(BigDecimal.valueOf(100), MC);
        return baseSinIva.add(baseSinIva.multiply(rate, MC))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Base sin IVA equivalente a un precio final. Se usa en el ajuste manual:
     * el cajero escribe el precio de la etiqueta (IVA incluido) y la linea
     * guarda la base que consume la matematica fiscal existente.
     *
     * <p>La base se redondea a centavos a proposito. El IVA de una linea
     * ajustada se calcula como {@code final - base} (ver
     * {@link #getTotalImpuesto(ArticuloCarrito)}), no relanzando la tasa: asi
     * {@code base + IVA} es EXACTAMENTE el precio que el cajero digito. Una
     * base con mas decimales hacia que el total de la factura derivara unas
     * diezmilesimas del monto realmente cobrado.</p>
     */
    @Nonnull
    public static BigDecimal getPrecioBaseDesdeFinal(
            @Nullable ArticuloCarrito item, @Nullable BigDecimal precioFinal) {
        if (item == null || precioFinal == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = getImpuestoRate(item).divide(BigDecimal.valueOf(100), MC);
        BigDecimal factor = BigDecimal.ONE.add(rate, MC);
        if (factor.compareTo(BigDecimal.ZERO) <= 0) {
            return precioFinal.setScale(2, RoundingMode.HALF_UP);
        }
        return precioFinal.divide(factor, 2, RoundingMode.HALF_UP);
    }

    /** Total de la linea (IVA incluido) para la vista del carrito. */
    @Nonnull
    public static BigDecimal getTotalLineaFinal(@Nullable ArticuloCarrito item) {
        if (item == null || item.getCantidad() == null) {
            return BigDecimal.ZERO;
        }
        return getPrecioFinalCobrado(item)
                .multiply(item.getCantidad(), MC)
                .setScale(2, RoundingMode.HALF_UP);
    }

    @Nonnull
    public static BigDecimal getTotalArticulos(@Nullable ArticuloCarrito articuloCarrito) {
        return getTotalLineaFinal(articuloCarrito);
    }

    @Nonnull
    public static BigDecimal getArticuloConDescuento(@Nullable ArticuloCarrito articuloCarrito) {
        return getPrecioFinalCobrado(articuloCarrito);
    }

    @Nonnull
    public static BigDecimal getTotalDescuento(@Nullable ArticuloCarrito articuloCarrito) {
        if (articuloCarrito == null) {
            return BigDecimal.ZERO;
        }
        return getBaseUnitariaSinDescuento(articuloCarrito)
                .subtract(getBaseNetaUnitaria(articuloCarrito));
    }

    @Nonnull
    public static BigDecimal getTotalImpuesto(@Nullable ArticuloCarrito articuloCarrito) {
        return getImpuestoUnitario(articuloCarrito);
    }

    @Nonnull
    public static BigDecimal calculateTotalCarrito(@Nullable List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;
        if (carrito != null) {
            for (ArticuloCarrito item : carrito) {
                total = total.add(getTotalLineaFinal(item));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    @Nonnull
    public static BigDecimal calculateTotalDescuento(@Nullable List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;
        if (carrito != null) {
            for (ArticuloCarrito item : carrito) {
                total = total.add(getTotalDescuento(item).multiply(item.getCantidad(), MC));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    @Nonnull
    public static BigDecimal calculateTotalImpuesto(@Nullable List<ArticuloCarrito> carrito) {
        BigDecimal total = BigDecimal.ZERO;
        if (carrito != null) {
            for (ArticuloCarrito item : carrito) {
                total = total.add(getTotalImpuesto(item).multiply(item.getCantidad(), MC));
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
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
