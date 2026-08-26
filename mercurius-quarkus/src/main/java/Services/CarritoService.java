package Services;

import Controllers.ArticulosController;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Carrito.CartOperationResult;
import Models.Articulos.Carrito.CartSessionContext;
import Models.Articulos.Promocion;
import Models.Clients;
import Models.Inventario;
import Models.Users;
import Models.Registros.Alertas;
import Services.AlertasService;
import Services.InventarioService; 
import Utils.CarritoCalculations; 
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.Data;

/**
 *
 * @author OIJ
 */
@Named("carritoService")
@Data
@ApplicationScoped
public class CarritoService implements Serializable {

    @Inject
    private @Nonnull ArticulosController articuloController;
    @Inject
    private @Nonnull AlertasService alertasService;
    @Inject
    private @Nonnull InventarioService inventario;

    /**
     * Contexto puente SOLO para los llamadores legacy que aún no reciben un
     * {@link CartSessionContext} propio (ConsultasController.clonarFacturaACarrito,
     * fuera del alcance de archivos de T5). Justificación escrita: T5 prohíbe
     * editar ConsultasController, pero el código debe seguir compilando y la
     * función clonar-factura-a-carrito debe seguir operando; antes el estado
     * vivía por vista, ahora este puente es único por aplicación, delta
     * semántico documentado en .omo/evidence/t5/call-site-translation.md.
     * Eliminar cuando ConsultasController migre (T30/T37) pasando su propio
     * contexto.
     */
    private final CartSessionContext legacyShimContext = new CartSessionContext();

    public void addArticulo(@Nonnull CartSessionContext ctx, @Nonnull Articulos articulo, @Nonnull BigDecimal cantidad) {
        try {
            boolean found = false;
            for (ArticuloCarrito item : ctx.getCarrito()) {
                if (Objects.equals(item.getArticulo().getCodigo(), articulo.getCodigo()) && !item.isPromo()) {
                    item.setCantidad(item.getCantidad().add(cantidad));
                    found = true;
                    break;
                }
            }
            if (!found) {
                ArticuloCarrito ac = new ArticuloCarrito();
                ac.setArticulo(articulo);
                ac.setCantidad(cantidad);
                ctx.getCarrito().add(ac);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    /**
     * @deprecated Puente legacy para ConsultasController (no puede recibir un
     * CartSessionContext hasta su migración); usa el contexto puente interno.
     * Use {@link #addArticulo(CartSessionContext, Articulos, BigDecimal)}.
     */
    @Deprecated
    public void addArticulo(@Nonnull Articulos articulo, @Nonnull BigDecimal cantidad) {
        addArticulo(legacyShimContext, articulo, cantidad);
    }

    public void removeArticulo2(@Nonnull CartSessionContext ctx, @Nonnull ArticuloCarrito articulo) {
        ctx.getCarrito().removeIf(a -> a.equals(articulo));
    }

    public @Nullable CartOperationResult revisarCarrito(@Nonnull CartSessionContext ctx) {
        try {
            if (!ctx.getCarrito().isEmpty()) {
                return CartOperationResult.script(CartOperationResult.Status.PAGO_DIALOG_LISTO, "PF('PagoDialog').show();");
            } else {
                return CartOperationResult.message(CartOperationResult.Status.CARRITO_VACIO,
                    CartOperationResult.Severity.WARN, "Carrito vacío", "Agregue artículos al carrito antes de continuar");
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return CartOperationResult.silent(CartOperationResult.Status.FALLA_INTERNA);
        }
    }

    public void removeArticulo(@Nonnull CartSessionContext ctx, @Nonnull ArticuloCarrito articulo, @Nonnull Users currentUser) {
        try {
            if (ctx.getCarrito() != null) {
                Iterator<ArticuloCarrito> iterator = ctx.getCarrito().iterator();
                boolean removed = false;
                while (iterator.hasNext() && !removed) {
                    ArticuloCarrito articuloCarrito = iterator.next();
                    if (articuloCarrito.equals(articulo)) {
                        alertasService.registrarAlerta("Modificacion Carrito", "Cajero " + currentUser.getUsername() + " elimino articulo de carrito", currentUser, 0, "CrearTiqueteController.removeArticulo", articulo.toString(), null);
                        iterator.remove();
                        removed = true;
                    }
                }
                procesarPromocionesCarrito(ctx);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public @Nullable BigDecimal calcularTotal(@Nonnull CartSessionContext ctx) {
        try {
            BigDecimal total = BigDecimal.ZERO;
            for (ArticuloCarrito item : ctx.getCarrito()) {
                BigDecimal precio = item.isPromo() ? item.getArticuloConDescuento() : item.getPrecioEfectivo();
                String impuestoStr = item.getArticulo().getCodigoCabys().getImpuesto();
                BigDecimal impuestoPct = BigDecimal.ZERO;
                if (impuestoStr != null && !impuestoStr.isEmpty()) {
                    try {
                        impuestoPct = new BigDecimal(impuestoStr);
                    } catch (NumberFormatException ignored) {
                    }
                }
                precio = precio.add(precio.multiply(impuestoPct.divide(BigDecimal.valueOf(100))));
                total = total.add(precio.multiply(item.getCantidad()));
            }
            return total;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return null;
        }
    }

    public void clear(@Nonnull CartSessionContext ctx) {
        ctx.getCarrito().clear();
    }

    /**
     * @deprecated Puente legacy para ConsultasController (no puede recibir un
     * CartSessionContext hasta su migración); usa el contexto puente interno.
     * Use {@link #clear(CartSessionContext)}.
     */
    @Deprecated
    public void clear() {
        clear(legacyShimContext);
    }

    private void procesarPromocionesCarrito(@Nonnull CartSessionContext ctx) {
        try {
            List<ArticuloCarrito> listaArticulos = new ArrayList<>(ctx.getCarrito()); // Crear una copia para evitar ConcurrentModificationException
            List<ArticuloCarrito> articulosPromocionales = new ArrayList<>(); // Lista para almacenar artículos promocionales

            // Paso 1: Verificar y aplicar promociones aplicables
            for (ArticuloCarrito articulo : listaArticulos) {
                List<Promocion> promocionesActivas = articulo.getPromocionesActivas();

                for (Promocion promocion : promocionesActivas) {
                    boolean promocionAplicada = promocionAplica(promocion, listaArticulos, articulosPromocionales);
                }
            }

            ctx.getCarrito().addAll(articulosPromocionales);

            // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
            verificarPromocionesCarrito(ctx);

            // Paso 3: Eliminar artículos con cantidad 0
            ctx.getCarrito().removeIf(articulo -> articulo.getCantidad().compareTo(BigDecimal.ZERO) <= 0);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public boolean promocionAplica(@Nonnull Promocion promocion, @Nonnull List<ArticuloCarrito> articulos, @Nonnull List<ArticuloCarrito> articulosPromocionales) {
        try {
            // Verificamos si todos los artículos requeridos por la promoción están en el carrito
            boolean aplicable = promocion.getArticulosCarrito().stream()
                    .allMatch(itemPromocion -> articulos.stream()
                    .anyMatch(itemCarrito -> itemCarrito.equals(itemPromocion)
                    && itemCarrito.getCantidad().compareTo(itemPromocion.getCantidad()) >= 0 // Verifica si hay suficiente cantidad
                    && !itemCarrito.isPromo() // Verifica que no esté ya en otra promoción
                    )
                    );

            if (aplicable) {
                // Aplicamos las modificaciones a los artículos en el carrito
                for (ArticuloCarrito itemPromocion : promocion.getArticulosCarrito()) {
                    BigDecimal cantidadRequerida = itemPromocion.getCantidad();
                    BigDecimal cantidadTotalDisponible = articulos.stream()
                            .filter(itemCarrito -> itemCarrito.equals(itemPromocion) && !itemCarrito.isPromo())
                            .map(ArticuloCarrito::getCantidad)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    // Solo aplica la promoción si hay suficiente cantidad
                    while (cantidadTotalDisponible.compareTo(cantidadRequerida) >= 0) {
                        // Crear nuevo artículo de promoción con la cantidad requerida
                        ArticuloCarrito itemPromocionAplicado = new ArticuloCarrito();
                        itemPromocionAplicado = itemPromocion;
                        itemPromocionAplicado.setCantidad(cantidadRequerida);
                        itemPromocionAplicado.setPromo(true); // Marcamos el artículo como parte de una promoción
                        List<Promocion> promociones = new ArrayList<>();
                        promociones.add(promocion);
                        itemPromocionAplicado.setPromociones(promociones);
                        itemPromocionAplicado.setDescuento(promocion.getDescuento()); // Establecemos el descuento
                        articulosPromocionales.add(itemPromocionAplicado); // Añadimos el artículo promocionado a la lista temporal

                        // Restar cantidad utilizada de los artículos en el carrito
                        BigDecimal cantidadRestante = cantidadRequerida; // Inicializamos la cantidad que se necesita restar
                        for (ArticuloCarrito itemCarrito : articulos) {
                            // Verificamos que el artículo del carrito sea el mismo que el de la promoción y que no esté ya en otra promoción
                            if (itemCarrito.equals(itemPromocion) && !itemCarrito.isPromo()) {
                                BigDecimal cantidadActual = itemCarrito.getCantidad(); // Obtenemos la cantidad actual del artículo en el carrito

                                if (cantidadActual.compareTo(cantidadRestante) >= 0) {
                                    // Si hay suficiente cantidad en el artículo del carrito
                                    itemCarrito.setCantidad(cantidadActual.subtract(cantidadRestante)); // Restamos la cantidad requerida
                                    break; // Ya hemos consumido la cantidad necesaria, salimos del bucle
                                } else {
                                    // Si no hay suficiente cantidad, consumimos todo lo que hay
                                    cantidadRestante = cantidadRestante.subtract(cantidadActual); // Reducimos la cantidad que aún necesitamos restar
                                    itemCarrito.setCantidad(BigDecimal.ZERO); // Marcamos el artículo como completamente consumido
                                }
                            }
                        }
                        // Actualizamos la cantidad total disponible restando la cantidad requerida para esta iteración
                        cantidadTotalDisponible = cantidadTotalDisponible.subtract(cantidadRequerida);
                    }
                }
            }
            return aplicable;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return false;
        }
    }

    public void verificarPromocionesCarrito(@Nonnull CartSessionContext ctx) {
        try {
            // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
            List<Promocion> promocionesAplicadas = obtenerPromocionesAplicadas(ctx); // Método que retorna todas las promociones aplicadas

            for (Promocion promocion : promocionesAplicadas) {
                boolean promocionSigueValida = promocionSigueSiendoValida(ctx, promocion);

                if (!promocionSigueValida) {
                    // Si la promoción ya no es válida, revertimos los descuentos aplicados
                    revertirPromocion(promocion, ctx.getCarrito());
                    alertasService.registrarAlerta("Info", "Promocion revertida: " + promocion.getNombre(), null, 0, "CarritoService.verificarPromocionesCarrito()", null, null);
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public boolean promocionSigueSiendoValida(@Nonnull CartSessionContext ctx, @Nonnull Promocion promocion) {
        try {
            // Verificar si la promoción sigue siendo válida con la situación actual del carrito
            return promocion.getArticulosCarrito().stream()
                    .allMatch(itemPromocion -> ctx.getCarrito().stream()
                    .anyMatch(itemCarrito -> itemCarrito.equals(itemPromocion)
                    && itemCarrito.getCantidad().compareTo(itemPromocion.getCantidad()) >= 0
                    && itemCarrito.isPromo() // Aquí solo se verifican los artículos ya marcados como promociones
                    )
                    );
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return false;
        }
    }

    public void revertirPromocion(@Nonnull Promocion promocion, @Nonnull List<ArticuloCarrito> carrito) {
        try {
            for (ArticuloCarrito articulo : carrito) {
                List<Promocion> promociones = articulo.getPromociones();
                if (promociones != null && promociones.contains(promocion)) {
                    promociones.remove(promocion);
                    articulo.setDescuento(BigDecimal.ZERO);
                    articulo.setPromo(false);
                }
            }

            // También es buena idea remover los artículos de la promoción para mantener la bidireccionalidad
            List<ArticuloCarrito> articulosPromo = promocion.getArticulosCarrito();
            if (articulosPromo != null) {
                articulosPromo.removeAll(carrito);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    // Método auxiliar para obtener todas las promociones aplicadas actualmente en el carrito
    public @Nullable List<Promocion> obtenerPromocionesAplicadas(@Nonnull CartSessionContext ctx) {
        try {
            return ctx.getCarrito().stream()
                    .filter(ArticuloCarrito::isPromo)
                    .flatMap(articulo -> {
                        List<Promocion> promociones = articulo.getPromociones();
                        return promociones != null ? promociones.stream() : Stream.empty();
                    })
                    .distinct()
                    .collect(Collectors.toList());
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return null;
        }
    }

    public void calcularVuelto(@Nonnull CartSessionContext ctx, @Nonnull BigDecimal tipoCambioValue) {
        try {
            BigDecimal totalFactura = calculateTotalCarrito(ctx);
            ctx.setPago(ctx.getTotalPagado() != null ? ctx.getTotalPagado() : BigDecimal.ZERO);
            BigDecimal montoAPagar = totalFactura.subtract(
                ctx.getDescuentoPuntos() != null ? ctx.getDescuentoPuntos() : BigDecimal.ZERO);
            ctx.setVuelto(ctx.getPago().subtract(montoAPagar).setScale(0, RoundingMode.HALF_UP));
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        } 
    }

    public @Nonnull String getVueltoString(@Nonnull CartSessionContext ctx) {
        try {
            if (ctx.getVuelto() == null) {
                return "";
            }

            if (ctx.getVuelto().signum() > 0) {
                return "Vuelto: " + ctx.getVuelto() + " colones";
            } else if (ctx.getVuelto().signum() < 0) {
                return "Faltante: " + ctx.getVuelto().abs() + " colones";
            } else {
                return "Vuelto: 0 colones";
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return "Error";
        }
    }

    public @Nonnull BigDecimal calculateTotalCarrito(@Nonnull CartSessionContext ctx) {
        return CarritoCalculations.calculateTotalCarrito(ctx.getCarrito());
    }

    public @Nonnull BigDecimal calculateTotalCarritoDescuento(@Nonnull CartSessionContext ctx) {
        return CarritoCalculations.calculateTotalDescuento(ctx.getCarrito());
    }

    public @Nonnull BigDecimal calculateTotalCarritoImpuesto(@Nonnull CartSessionContext ctx) {
        return CarritoCalculations.calculateTotalImpuesto(ctx.getCarrito());
    }

    public void ajustarInventario(@Nonnull CartSessionContext ctx, @Nonnull Users currentUser) {
        try {
            for (ArticuloCarrito articulo : ctx.getCarrito()) {
                var Articulo = articulo;
                var Cantidad = articulo.getCantidad();

                Inventario movimiento = new Inventario();
                movimiento.setArticulo(Articulo.getArticulo());
                movimiento.setCantidad(Cantidad.negate());
                movimiento.setFechaMovimiento(new Date());
                movimiento.setNotas("Articulo Vendido");
                movimiento.setProcessed(Boolean.TRUE);
                movimiento.setStatus(Boolean.TRUE);
                movimiento.setTipoMovimiento("Venta");
                movimiento.setUnidadesRecomendadasFactura(Cantidad.negate());
                movimiento.setUsuario(currentUser);

                inventario.update(movimiento);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    /**
     * Trigger stock alert checking after inventory adjustment
     */
    @Inject
    private @Nonnull Services.StockAlertService stockAlertService;

    public void checkStockAlertsAfterSale() {
        try {
            stockAlertService.checkAndCreateStockAlerts();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error Stock", "Error checking stock alerts: " + e.getMessage(), null, 0, "CarritoService.checkStockAlertsAfterSale()", null, e.getMessage());
        }
    }
 
    public @Nullable CartOperationResult cancel(@Nonnull CartSessionContext ctx, @Nonnull Users currentUser) {
        try {
            String cajero = currentUser.getUsername();
            Alertas alerta = new Alertas();
            StringBuilder antesBuilder = new StringBuilder();

            antesBuilder.append("Items en Carrito: ");
            if (ctx.getCarrito().isEmpty()) {
                antesBuilder.append("Carrito vacío");
            } else {
                for (ArticuloCarrito articulo : ctx.getCarrito()) {
                    antesBuilder.append("[Artículo: ")
                            .append(articulo.getArticulo().getNombre())
                            .append(", Cantidad: ")
                            .append(articulo.getCantidad());

                    if (articulo.isPromo()) {
                        List<Promocion> promociones = articulo.getPromociones();
                        if (promociones != null && !promociones.isEmpty()) {
                            antesBuilder.append(", Promociones: ");
                            for (Promocion promo : promociones) {
                                antesBuilder.append(promo.getNombre());
                                if (promo.getDescuento() != null) {
                                    antesBuilder.append(" (Descuento: ").append(promo.getDescuento()).append(")");
                                }
                                antesBuilder.append("; ");
                            }
                        } else {
                            antesBuilder.append(", Sin promoción");
                        }
                    } else {
                        antesBuilder.append(", Sin promoción");
                    }

                    antesBuilder.append("], ");
                }
            }

            antesBuilder.append("\nCliente: ").append(ctx.getSelectedClient() != null ? ctx.getSelectedClient().getName() : "Ninguno");
            antesBuilder.append("\nCantidad Articulo: ").append(ctx.getCantidadArticulo());
            antesBuilder.append("\nCódigo Barra: ").append(ctx.getCodigoBarra());

            alerta.setMensaje("Eliminacion Articulo en Carrito - Cajero: " + cajero);
            alerta.setTipo("facturacion");
            alerta.setAntes(antesBuilder.toString());
            alerta.setDespues("Empty");
            alerta.setVista(false);

            alertasService.create(alerta);

            // Reset state
            ctx.setResetFlag(!ctx.isResetFlag());
            ctx.setCodigoBarra("");
            ctx.setCantidadArticulo(BigDecimal.ONE);
            ctx.setSelectedClient(new Clients());
            ctx.setCarrito(new ArrayList<>());

            return CartOperationResult.script(CartOperationResult.Status.CANCELADO, "window.close();");
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return CartOperationResult.silent(CartOperationResult.Status.FALLA_INTERNA);
        }
    }

    public @Nullable CartOperationResult processCodigoBarra(@Nonnull CartSessionContext ctx) {
        try {
            String codigo = ctx.getCodigoBarra();
            BigDecimal cantidad = ctx.getCantidadArticulo();

            if (codigo != null && !codigo.isBlank()) {
                Articulos articulo = articuloController.findArticuloByBarCode(codigo);

                if (articulo != null) {
                    if (cantidad.compareTo(BigDecimal.ZERO) == 1) {
                        ArticuloCarrito articuloCarrito = new ArticuloCarrito();
                        articuloCarrito.setArticulo(articulo);
                        articuloCarrito.setCantidad(cantidad);
                        boolean found = false;

                        // Recorremos el carrito para ver si ya existe el artículo Y No es una promo...
                        for (ArticuloCarrito item : ctx.getCarrito()) {
                            if (item.getArticulo().getCodigo() == articulo.getCodigo() && !item.isPromo()) {
                                item.setCantidad(item.getCantidad().add(cantidad)); // Sumamos la cantidad existente con la nueva
                                found = true;
                                break;
                            }
                        }

                        // Si no lo encontró en el carrito, lo agrega con la cantidad especificada
                        if (!found) {
                            ctx.getCarrito().add(articuloCarrito); // Add to carrito first
                        }

                        // Check for active promotions in the entire cart
                        procesarPromocionesCarrito(ctx);

                        // Limpiamos los campos
                        ctx.setCodigoBarra("");
                        ctx.setCantidadArticulo(BigDecimal.ONE);
                        ctx.setResetFlag(!ctx.isResetFlag()); // Toggle el reset flag

                        return CartOperationResult.message(CartOperationResult.Status.ARTICULO_AGREGADO,
                                CartOperationResult.Severity.INFO, "Artículo agregado",
                                "El artículo fue agregado al carrito");
                    } else {
                        return CartOperationResult.message(CartOperationResult.Status.CANTIDAD_INVALIDA,
                                CartOperationResult.Severity.ERROR, "No hay cantidad",
                                "La cantidad es inválida");
                    }
                } else {
                    return CartOperationResult.message(CartOperationResult.Status.ARTICULO_NO_ENCONTRADO,
                            CartOperationResult.Severity.ERROR, "Artículo no encontrado",
                            "El código de barra no corresponde a un artículo válido");
                }
            } else {
                return CartOperationResult.message(CartOperationResult.Status.CODIGO_VACIO,
                        CartOperationResult.Severity.ERROR, "Código de barra vacío o nulo",
                        "El código de barra no corresponde a un artículo válido");
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return CartOperationResult.silent(CartOperationResult.Status.FALLA_INTERNA);
        }
    }
}
