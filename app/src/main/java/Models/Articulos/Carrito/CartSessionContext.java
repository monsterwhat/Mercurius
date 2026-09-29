package Models.Articulos.Carrito;

import Models.Clientes;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.Data;

/**
 * Estado mutable de un carrito POS.
 * <p>
 * Extracción de T5 (plan mercurius-jsf-to-api-migration): antes estas doce
 * campos vivían como estado de instancia en {@code Services.CarritoService}
 * (un bean @ViewScoped). Ahora el estado viaja en esta clase @Dependent que el
 * llamador posee (p.ej. CrearTiqueteController, o un store por sesión en el
 * futuro POS REST), y {@code CarritoService} queda apátrida (@ApplicationScoped).
 * <p>
 * Mapeo completo de campos en .omo/evidence/t5/field-mapping.md.
 */
@Data
@Dependent
public class CartSessionContext implements Serializable {

    private static final long serialVersionUID = 1L;

    // --- Cliente y captura de artículos ---
    @Nullable
    private Clientes selectedClient;
    @Nonnull
    private BigDecimal cantidadArticulo = BigDecimal.ONE;
    @Nullable
    private String codigoBarra;
    private boolean resetFlag;

    // --- Líneas del carrito ---
    //
    // CopyOnWriteArrayList, not ArrayList, and the setter below re-wraps any
    // list it is given. One cashier's HTMX requests can interleave on these
    // lines (double-clicked "Agregar", a scan racing a quantity edit): with a
    // plain ArrayList that meant lost lines or ConcurrentModificationException
    // mid-iteration in CarritoService. COW makes every add/remove/clear atomic
    // and every iteration snapshot-consistent. It does NOT make compound
    // check-then-act sequences atomic — two truly simultaneous scans of the
    // same article can still produce two lines instead of one merged line.
    // That leftover is cosmetic (same total, an extra row) and confined to
    // cart building; the sale itself is serialized per cashier by
    // PosResource.doFacturar's monitor plus the idempotency stamp, so money
    // movement is unaffected. CopyOnWriteArrayList is Serializable, so the
    // class's Serializable contract is unchanged.
    @Nonnull
    private List<ArticuloCarrito> carrito = new CopyOnWriteArrayList<>();

    /**
     * Replaces the cart lines, re-wrapping in the thread-safe implementation.
     *
     * <p>Explicit to override Lombok's {@code @Data} setter: callers like
     * {@code CarritoService.cancel} pass a plain {@code ArrayList}, which
     * would silently downgrade the field back to a non-thread-safe list.
     * Accepts {@code null} as "empty" to match the previous leniency.</p>
     */
    public void setCarrito(@Nullable List<ArticuloCarrito> carrito) {
        this.carrito = carrito == null
                ? new CopyOnWriteArrayList<>()
                : new CopyOnWriteArrayList<>(carrito);
    }

    // --- Totales y pago ---
    @Nullable
    private BigDecimal totalCarrito;
    @Nullable
    private BigDecimal colones;
    @Nullable
    private BigDecimal dolares;
    @Nullable
    private BigDecimal vuelto;
    @Nullable
    private BigDecimal pago;
    @Nonnull
    private BigDecimal totalPagado = BigDecimal.ZERO;
    @Nonnull
    private BigDecimal descuentoPuntos = BigDecimal.ZERO;
}
