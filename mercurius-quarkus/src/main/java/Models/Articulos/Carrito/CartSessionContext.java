package Models.Articulos.Carrito;

import Models.Clients;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.Dependent;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    private Clients selectedClient;
    @Nonnull
    private BigDecimal cantidadArticulo = BigDecimal.ONE;
    @Nullable
    private String codigoBarra;
    private boolean resetFlag;

    // --- Líneas del carrito ---
    @Nonnull
    private List<ArticuloCarrito> carrito = new ArrayList<>();

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
