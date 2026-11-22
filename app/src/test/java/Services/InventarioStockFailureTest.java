package Services;

import Models.Articulos.Articulos;
import Models.Inventario;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * El fallo de una actualizacion de stock no puede dejar la venta registrada.
 * Esta prueba usa transacciones reales (no @TestTransaction) para observar el
 * rollback del interceptor de {@link InventarioService#createWithStock}.
 */
@QuarkusTest
@Tag("integration-services")
class InventarioStockFailureTest {

    private static final AtomicInteger SUFIJOS = new AtomicInteger();

    @Inject
    InventarioService inventarioService;

    @Inject
    ArticulosService articulosService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Test
    void movimientoSinBarcodeNoDejaLaVentaRegistrada() {
        Long codigo = null;
        try {
            Articulos articulo = new Articulos();
            articulo.setNombre("IT Stock Failure " + SUFIJOS.incrementAndGet());
            articulo.setUnidadMedida("Unidad");
            articulo.setStatus(true);
            articulo.setProcessed(true);
            articulo.setPrecios(new ArrayList<>());
            articulosService.create(articulo);
            codigo = articulo.getCodigo();

            Inventario movimiento = new Inventario();
            movimiento.setArticulo(articulo);
            movimiento.setCantidad(BigDecimal.ONE);
            movimiento.setTipoMovimiento("Venta");
            movimiento.setFechaMovimiento(new Date());
            movimiento.setStatus(Boolean.TRUE);
            movimiento.setProcessed(Boolean.TRUE);

            assertThrows(IllegalArgumentException.class,
                    () -> inventarioService.createWithStock(movimiento),
                    "sin barcode el stock no se puede actualizar");

            assertEquals(0L, contarMovimientos(codigo),
                    "la transaccion debe revertir el movimiento, no solo fallar el stock");
        } finally {
            limpiar(codigo);
        }
    }

    private long contarMovimientos(Long codigo) {
        return em.createQuery("SELECT COUNT(i) FROM Inventario i WHERE i.articulo.codigo = :codigo",
                        Long.class)
                .setParameter("codigo", codigo)
                .getSingleResult();
    }

    private void limpiar(Long codigo) {
        if (codigo == null) {
            return;
        }
        try {
            utx.begin();
            em.createQuery("DELETE FROM Inventario i WHERE i.articulo.codigo = :codigo")
                    .setParameter("codigo", codigo)
                    .executeUpdate();
            Articulos articulo = em.find(Articulos.class, codigo);
            if (articulo != null) {
                em.remove(articulo);
            }
            utx.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Limpieza de fixture fallida", e);
        }
    }
}
