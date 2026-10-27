package Models.Articulos;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** El precio visible no debe lanzar NPE cuando el artículo no tiene historial. */
class ArticulosTest {

    @Test
    void lastPrecioArticuloEsNuloSinPrecios() {
        Articulos articulo = new Articulos();
        assertThat(articulo.getLastPrecioArticulo()).isNull();
    }

    @Test
    void lastPrecioArticuloDevuelveElUltimoPrecioFinal() {
        ArticuloPrecio anterior = new ArticuloPrecio();
        anterior.setPrecioFinal(new BigDecimal("10.00"));
        ArticuloPrecio ultimo = new ArticuloPrecio();
        ultimo.setPrecioFinal(new BigDecimal("12.50"));

        Articulos articulo = new Articulos();
        articulo.setPrecios(List.of(anterior, ultimo));

        assertThat(articulo.getLastPrecioArticulo()).isEqualByComparingTo("12.50");
    }
}
