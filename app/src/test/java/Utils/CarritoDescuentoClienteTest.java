package Utils;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Cabys;
import Services.ComprobanteService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Descuento del cliente aplicado POR LINEA (como el sistema viejo), no al
 * total de la factura. La linea reduce su base, el IVA se recalcula sobre la
 * base neta y el resumen no vuelve a restar el descuento dos veces.
 */
@QuarkusTest
class CarritoDescuentoClienteTest {

    @Inject
    ComprobanteService comprobanteService;

    private static ArticuloCarrito linea(int descuento) {
        Cabys cabys = new Cabys();
        cabys.setCodigo("2449001000000");
        cabys.setImpuesto("13");

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setPrecioConUtilidad(new BigDecimal("1000"));
        precio.setPrecioFinal(new BigDecimal("1130"));

        Articulos articulo = new Articulos();
        articulo.setCodigo(999001L);
        articulo.setCodigoBarra("999001");
        articulo.setNombre("ARTICULO DE PRUEBA");
        articulo.setCodigoCabys(cabys);
        articulo.setPrecios(List.of(precio));

        ArticuloCarrito item = new ArticuloCarrito();
        item.setArticulo(articulo);
        item.setCantidad(BigDecimal.ONE);
        item.setDescuento(descuento > 0 ? BigDecimal.valueOf(descuento) : BigDecimal.ZERO);
        return item;
    }

    @Test
    void laLineaConDescuentoReduceBaseEImpuesto() {
        ArticuloCarrito item = linea(50);

        // Base 1000 - 50% = 500; IVA 13% = 65; total 565.
        assertThat(CarritoCalculations.getTotalArticulos(item))
                .isEqualByComparingTo("565.00");
        assertThat(CarritoCalculations.getTotalDescuento(item))
                .isEqualByComparingTo("500.00");
        assertThat(CarritoCalculations.calculateTotalCarrito(List.of(item)))
                .isEqualByComparingTo("565.00");
    }

    @Test
    void elResumenSumaLaBaseAntesDelDescuentoYNoLoRestaDosVeces() {
        var resumen = comprobanteService.resumenComprobante(List.of(linea(50)));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("1000");       // antes
        assertThat(resumen.getTotalDescuentos()).isEqualByComparingTo("500");
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo("500");    // una sola resta
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("65");      // 13% de 500
        assertThat(resumen.getTotalGravado()).isEqualByComparingTo("500");
    }

    @Test
    void sinDescuentoElResumenQuedaIgualQueSiempre() {
        var resumen = comprobanteService.resumenComprobante(List.of(linea(0)));

        assertThat(resumen).isNotNull();
        assertThat(resumen.getTotalVenta()).isEqualByComparingTo("1000");
        assertThat(resumen.getTotalDescuentos()).isEqualByComparingTo("0");
        assertThat(resumen.getTotalVentaNeta()).isEqualByComparingTo("1000");
        assertThat(resumen.getTotalImpuesto()).isEqualByComparingTo("130");
    }
}
