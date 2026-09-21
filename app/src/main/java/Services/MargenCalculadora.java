package Services;

import Models.Articulos.Articulos;
import Models.ConfiguracionMargen;
import Models.Enums.TipoRefrigeracion;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.jboss.logging.Logger;

/**
 * Calculadora centralizada de márgenes de ganancia.
 *
 * Resuelve el porcentaje de utilidad aplicable a un artículo basándose en:
 *   1) Configuración global (margen base + ajustes por tipo de refrigeración)
 *   2) Tipo de refrigeración del producto (NINGUNA / REFRIGERADO / CONGELADO)
 *
 * Ejemplo con base=25%, ref=+5%, cong=+10%:
 *   - Sin refrigeración: 25%
 *   - Refrigerado:       30%
 *   - Congelado:         35%
 */
@ApplicationScoped
public class MargenCalculadora {

    private static final Logger LOG = Logger.getLogger(MargenCalculadora.class);

    @Inject
    ConfiguracionMargenService configuracionMargenService;

    /**
     * Calcula el margen (%) para un artículo según su tipo de refrigeración
     * y la configuración global.
     */
    @Nonnull
    public BigDecimal calcularMargenPorcentaje(@Nonnull Articulos articulo) {
        ConfiguracionMargen config = configuracionMargenService.findOrCreateDefault();
        TipoRefrigeracion tipo = articulo.getTipoRefrigeracion();

        BigDecimal margen = config.getMargenBase();
        if (margen == null) {
            margen = BigDecimal.ZERO;
        }

        if (tipo == null || tipo == TipoRefrigeracion.NINGUNA) {
            return margen;
        }
        if (tipo == TipoRefrigeracion.REFRIGERADO) {
            return margen.add(safeAjuste(config.getAjusteRefrigerado()));
        }
        if (tipo == TipoRefrigeracion.CONGELADO) {
            return margen.add(safeAjuste(config.getAjusteCongelado()));
        }
        return margen;
    }

    private BigDecimal safeAjuste(@Nullable BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Calcula el precio con utilidad: costo * (1 + margen/100), CEILING a 0dp.
     * Reproduce la lógica legacy de {@code calcularPrecioConUtilidad(Edit)}.
     */
    @Nonnull
    public BigDecimal calcularPrecioConUtilidad(@Nullable BigDecimal costo, @Nullable BigDecimal margenPorcentaje) {
        if (costo == null || margenPorcentaje == null) {
            return BigDecimal.ZERO;
        }
        if (costo.compareTo(BigDecimal.ZERO) < 0 || margenPorcentaje.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal factorUtilidad = margenPorcentaje.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        BigDecimal utilidad = costo.multiply(factorUtilidad);
        return costo.add(utilidad).setScale(0, RoundingMode.CEILING);
    }

    /**
     * Calcula el precio final: precioConUtilidad * (1 + impuesto/100), CEILING a 0dp.
     * Reproduce la lógica legacy de {@code calcularPrecioConIVA(Edit)}.
     */
    @Nonnull
    public BigDecimal calcularPrecioFinal(@Nullable BigDecimal precioConUtilidad, @Nullable BigDecimal impuestoPorcentaje) {
        if (precioConUtilidad == null || precioConUtilidad.compareTo(BigDecimal.ZERO) <= 0) {
            return precioConUtilidad == null ? BigDecimal.ZERO : precioConUtilidad.setScale(0, RoundingMode.CEILING);
        }
        if (impuestoPorcentaje == null) {
            return precioConUtilidad.setScale(0, RoundingMode.CEILING);
        }
        BigDecimal factorIVA = impuestoPorcentaje.divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        BigDecimal iva = precioConUtilidad.multiply(factorIVA);
        return precioConUtilidad.add(iva).setScale(0, RoundingMode.CEILING);
    }

    /**
     * Margen real (análisis): (precioVenta - costo) / precioVenta * 100.
     */
    @Nonnull
    public BigDecimal calcularMargenReal(@Nullable BigDecimal costo, @Nullable BigDecimal precioVenta) {
        if (costo == null || precioVenta == null || precioVenta.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return precioVenta.subtract(costo)
                .divide(precioVenta, 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }
}
