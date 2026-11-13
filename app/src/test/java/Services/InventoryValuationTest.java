package Services;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.ArticuloStock;
import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Familia;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regresión del JPQL de valoración: el query anterior usaba `a.lastPrecio`
 * (no es propiedad mapeada) y `JOIN Articulos a ON ...` (JPQL no lo permite),
 * así que el catch devolvía siempre una lista vacía.
 */
@QuarkusTest
@TestTransaction
class InventoryValuationTest {

    @Inject
    EntityManager em;

    @Inject
    ArticulosService articulosService;

    @Inject
    PublicInvoiceService publicInvoiceService;

    @Test
    void valuationIncludesStockAndLatestPrice() {
        String barcode = "IT-VAL-" + UUID.randomUUID().toString().substring(0, 8);

        Articulos articulo = new Articulos();
        articulo.setNombre("IT Valuacion " + barcode);
        articulo.setCodigoBarra(barcode);
        articulo.setDescripcion("Fixture de valoracion");
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulo.setPrecios(new ArrayList<>());
        articulo.setDepartamento(em.createQuery(
                "SELECT d FROM Departamento d WHERE d.nombre = 'Departamento General'",
                Departamento.class).getSingleResult());
        articulo.setFamilia(em.createQuery(
                "SELECT f FROM Familia f WHERE f.nombre = 'Familia General'",
                Familia.class).getSingleResult());
        articulosService.create(articulo);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioFinal(new BigDecimal("2500"));
        precio.setPrecioCostoSinIVA(new BigDecimal("2000"));
        em.persist(precio);

        ArticuloStock stock = new ArticuloStock();
        stock.setCodigoBarra(barcode);
        stock.setStock(new BigDecimal("4"));
        em.persist(stock);
        em.flush();

        Map<String, Object> resultado = publicInvoiceService.getInventoryValuation();
        List<?> items = (List<?>) resultado.get("items");

        assertThat(items).isNotEmpty();
        assertThat(items).anyMatch(item ->
                barcode.equals(((Map<?, ?>) item).get("codigoBarra")));
        assertThat((BigDecimal) resultado.get("totalValue"))
                .isGreaterThan(BigDecimal.ZERO);
    }
}
