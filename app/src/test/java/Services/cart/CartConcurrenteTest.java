package Services.cart;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Carrito.CartSessionContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The POS cart must survive interleaved requests from one cashier.
 *
 * <p>Behavioral pin for the thread-safety fix. {@code CartSessionContext.carrito}
 * and the staged payments were plain {@code ArrayList}s held in an
 * {@code @ApplicationScoped} store: an HTMX burst (double-clicked "Agregar", a
 * scan racing a quantity edit, a sale iterating the cart while a scan lands)
 * could lose lines or throw {@code ConcurrentModificationException}
 * mid-iteration. Both lists are now copy-on-write, so mutation is atomic and
 * iteration is snapshot-consistent.</p>
 *
 * <p>What this does NOT claim: compound check-then-act sequences (e.g. "merge
 * quantities if the article is already a line") are still individually racy —
 * two truly simultaneous scans can yield two lines instead of one merged line.
 * That leftover is cosmetic (same total, an extra row); money movement is
 * serialized separately by the sale monitor plus the idempotency stamp.</p>
 */
@DisplayName("Carrito: lineas concurrentes no se pierden ni lanzan")
class CartConcurrenteTest {

    private static final int HILOS = 8;
    private static final int POR_HILO = 200;

    @Test
    @DisplayName("agregados concurrentes no pierden lineas")
    void agregadosConcurrentesNoPierdenLineas() throws Exception {
        CartSessionStore store = new CartSessionStore();
        CartSessionStore.Entry entry = store.getOrCreate("cajero");
        List<ArticuloCarrito> carrito = entry.getCartContext().getCarrito();

        AtomicReference<Throwable> fallo = new AtomicReference<>();
        CountDownLatch listos = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(HILOS);
        List<Future<?>> futuros = new ArrayList<>();
        for (int h = 0; h < HILOS; h++) {
            futuros.add(pool.submit(() -> {
                try {
                    listos.await(10, TimeUnit.SECONDS);
                    for (int i = 0; i < POR_HILO; i++) {
                        carrito.add(new ArticuloCarrito());
                    }
                } catch (Throwable t) {
                    fallo.compareAndSet(null, t);
                }
                return null;
            }));
        }
        listos.countDown();
        for (Future<?> f : futuros) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(fallo.get())
                .as("ningun hilo debe fallar agregando lineas")
                .isNull();
        assertThat(carrito)
                .as("todas las lineas agregadas deben estar presentes")
                .hasSize(HILOS * POR_HILO);
    }

    @Test
    @DisplayName("iterar mientras se agrega no lanza")
    void iterarMientrasSeAgregaNoLanza() throws Exception {
        CartSessionStore.Entry entry = new CartSessionStore().getOrCreate("cajero");
        List<ArticuloCarrito> carrito = entry.getCartContext().getCarrito();
        for (int i = 0; i < 50; i++) {
            carrito.add(new ArticuloCarrito());
        }

        AtomicReference<Throwable> fallo = new AtomicReference<>();
        CountDownLatch listos = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> escritor = pool.submit(() -> {
            try {
                listos.await(10, TimeUnit.SECONDS);
                for (int i = 0; i < 500; i++) {
                    carrito.add(new ArticuloCarrito());
                }
            } catch (Throwable t) {
                fallo.compareAndSet(null, t);
            }
            return null;
        });
        Future<?> lector = pool.submit(() -> {
            try {
                listos.await(10, TimeUnit.SECONDS);
                long vistos = 0;
                for (int i = 0; i < 500; i++) {
                    for (ArticuloCarrito ignored : carrito) {
                        vistos++;
                    }
                }
                assertThat(vistos).isPositive();
            } catch (Throwable t) {
                fallo.compareAndSet(null, t);
            }
            return null;
        });
        listos.countDown();
        escritor.get(60, TimeUnit.SECONDS);
        lector.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(fallo.get())
                .as("iterar un carrito que crece no debe lanzar ConcurrentModificationException")
                .isNull();
    }

    @Test
    @DisplayName("setCarrito reenvuelve una lista comun")
    void setCarritoReenvuelve() {
        CartSessionContext ctx = new CartSessionContext();
        ctx.setCarrito(new ArrayList<>(List.of(new ArticuloCarrito())));

        assertThat(ctx.getCarrito())
                .as("un ArrayList pasado por setCarrito no debe degradar el campo")
                .isInstanceOf(CopyOnWriteArrayList.class)
                .hasSize(1);

        ctx.setCarrito(null);
        assertThat(ctx.getCarrito())
                .as("null se acepta como vacio sin romper el tipo")
                .isInstanceOf(CopyOnWriteArrayList.class)
                .isEmpty();
    }

    @Test
    @DisplayName("setPagos reenvuelve una lista comun")
    void setPagosReenvuelve() {
        CartSessionStore.Entry entry = new CartSessionStore().getOrCreate("cajero");
        entry.setPagos(new ArrayList<>());

        assertThat(entry.getPagos())
                .isInstanceOf(CopyOnWriteArrayList.class);
    }
}
