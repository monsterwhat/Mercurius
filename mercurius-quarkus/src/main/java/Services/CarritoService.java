package Services;

import Controllers.ArticulosController;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Promocion;
import Models.Clients;
import Models.Inventario;
import Models.Users;
import Models.Registros.Alertas;
import Services.AlertasService;
import Services.InventarioService; 
import Utils.CarritoCalculations; 
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
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
import org.primefaces.PrimeFaces;

/**
 *
 * @author OIJ
 */
@Named("carritoService")
@Data
@ViewScoped
public class CarritoService implements Serializable {

    @Inject
    private @Nonnull ArticulosController articuloController;
    @Inject
    private @Nonnull AlertasService alertasService;
    @Inject
    private @Nonnull InventarioService inventario;
    private Clients selectedClient;
    private BigDecimal cantidadArticulo = BigDecimal.ONE;
    private String codigoBarra;
    private boolean resetFlag;

    private List<ArticuloCarrito> carrito = new ArrayList<>();
    private BigDecimal totalCarrito, colones, dolares, vuelto, pago;
    private BigDecimal descuentoPuntos = BigDecimal.ZERO;

    public void addArticulo(@Nonnull Articulos articulo, @Nonnull BigDecimal cantidad) {
        try {
            boolean found = false;
            for (ArticuloCarrito item : carrito) {
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
                carrito.add(ac);
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public void removeArticulo2(@Nonnull ArticuloCarrito articulo) {
        carrito.removeIf(a -> a.equals(articulo));
    }

    public void revisarCarrito() {
        try {
            if (!carrito.isEmpty()) {
                PrimeFaces.current().executeScript("PF('PagoDialog').show();");
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Carrito vacío", "Agregue artículos al carrito antes de continuar"));
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public void removeArticulo(@Nonnull ArticuloCarrito articulo, @Nonnull Users currentUser) {
        try {
            if (carrito != null) {
                Iterator<ArticuloCarrito> iterator = carrito.iterator();
                boolean removed = false;
                while (iterator.hasNext() && !removed) {
                    ArticuloCarrito articuloCarrito = iterator.next();
                    if (articuloCarrito.equals(articulo)) {
                        alertasService.registrarAlerta("Modificacion Carrito", "Cajero " + currentUser.getUsername() + " elimino articulo de carrito", currentUser, 0, "CrearTiqueteController.removeArticulo", articulo.toString(), null);
                        iterator.remove();
                        removed = true;
                    }
                }
                procesarPromocionesCarrito();
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public @Nullable BigDecimal calcularTotal() {
        try {
            BigDecimal total = BigDecimal.ZERO;
            for (ArticuloCarrito item : carrito) {
                BigDecimal precio = item.isPromo() ? item.getArticuloConDescuento() : item.getPrecioEfectivo();
                precio = precio.add(precio.multiply(BigDecimal.valueOf(item.getArticulo().getCodigoCabys().getImpuesto()).divide(BigDecimal.valueOf(100))));
                total = total.add(precio.multiply(item.getCantidad()));
            }
            return total;
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return null;
        }
    }

    public List<ArticuloCarrito> getCarrito() {
        return carrito;
    }

    public void clear() {
        carrito.clear();
    }

    private void procesarPromocionesCarrito() {
        try {
            List<ArticuloCarrito> listaArticulos = new ArrayList<>(carrito); // Crear una copia para evitar ConcurrentModificationException
            List<ArticuloCarrito> articulosPromocionales = new ArrayList<>(); // Lista para almacenar artículos promocionales

            // Paso 1: Verificar y aplicar promociones aplicables
            for (ArticuloCarrito articulo : listaArticulos) {
                List<Promocion> promocionesActivas = articulo.getPromocionesActivas();

                for (Promocion promocion : promocionesActivas) {
                    boolean promocionAplicada = promocionAplica(promocion, listaArticulos, articulosPromocionales);
                }
            }

            carrito.addAll(articulosPromocionales);

            // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
            verificarPromocionesCarrito();

            // Paso 3: Eliminar artículos con cantidad 0
            carrito.removeIf(articulo -> articulo.getCantidad().compareTo(BigDecimal.ZERO) <= 0);
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

    public void verificarPromocionesCarrito() {
        try {
            // Paso 2: Verificar si las promociones aplicadas siguen siendo válidas
            List<Promocion> promocionesAplicadas = obtenerPromocionesAplicadas(); // Método que retorna todas las promociones aplicadas

            for (Promocion promocion : promocionesAplicadas) {
                boolean promocionSigueValida = promocionSigueSiendoValida(promocion);

                if (!promocionSigueValida) {
                    // Si la promoción ya no es válida, revertimos los descuentos aplicados
                    revertirPromocion(promocion, carrito);
                    alertasService.registrarAlerta("Info", "Promocion revertida: " + promocion.getNombre(), null, 0, "CarritoService.verificarPromocionesCarrito()", null, null);
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public boolean promocionSigueSiendoValida(@Nonnull Promocion promocion) {
        try {
            // Verificar si la promoción sigue siendo válida con la situación actual del carrito
            return promocion.getArticulosCarrito().stream()
                    .allMatch(itemPromocion -> carrito.stream()
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
    public @Nullable List<Promocion> obtenerPromocionesAplicadas() {
        try {
            return carrito.stream()
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

    public void calcularVuelto(@Nonnull BigDecimal tipoCambioValue) {
        try {
            BigDecimal cambio = tipoCambioValue;
            BigDecimal totalFactura = calculateTotalCarrito();

            BigDecimal pagoColones = colones != null ? colones : BigDecimal.ZERO;
            BigDecimal pagoDolares = dolares != null ? dolares.multiply(cambio) : BigDecimal.ZERO;

            // Pago total en colones
            pago = pagoColones.add(pagoDolares);

            // Diferencia total: puede ser negativa
            BigDecimal montoAPagar = totalFactura.subtract(
                descuentoPuntos != null ? descuentoPuntos : BigDecimal.ZERO);
            vuelto = pago.subtract(montoAPagar).setScale(0, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        } 
    }

    public @Nonnull String getVueltoString() {
        try {
            if (vuelto == null) {
                return "";
            }

            if (vuelto.signum() > 0) {
                return "Vuelto: " + vuelto + " colones";
            } else if (vuelto.signum() < 0) {
                return "Faltante: " + vuelto.abs() + " colones";
            } else {
                return "Vuelto: 0 colones";
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
            return "Error";
        }
    }

    public @Nonnull BigDecimal calculateTotalCarrito() {
        return CarritoCalculations.calculateTotalCarrito(carrito);
    }

    public @Nonnull BigDecimal calculateTotalCarritoDescuento() {
        return CarritoCalculations.calculateTotalDescuento(carrito);
    }

    public @Nonnull BigDecimal calculateTotalCarritoImpuesto() {
        return CarritoCalculations.calculateTotalImpuesto(carrito);
    }

    public void ajustarInventario(@Nonnull Users currentUser) {
        try {
            for (ArticuloCarrito articulo : carrito) {
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
 
    public void cancel(@Nonnull Users currentUser) {
        try {
            String cajero = currentUser.getUsername();
            Alertas alerta = new Alertas();
            StringBuilder antesBuilder = new StringBuilder();

            antesBuilder.append("Items en Carrito: ");
            if (carrito.isEmpty()) {
                antesBuilder.append("Carrito vacío");
            } else {
                for (ArticuloCarrito articulo : carrito) {
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

            antesBuilder.append("\nCliente: ").append(selectedClient != null ? selectedClient.getName() : "Ninguno");
            antesBuilder.append("\nCantidad Articulo: ").append(cantidadArticulo);
            antesBuilder.append("\nCódigo Barra: ").append(codigoBarra);

            alerta.setMensaje("Eliminacion Articulo en Carrito - Cajero: " + cajero);
            alerta.setTipo("facturacion");
            alerta.setAntes(antesBuilder.toString());
            alerta.setDespues("Empty");
            alerta.setVista(false);

            alertasService.create(alerta);

            // Reset state
            resetFlag = !resetFlag;
            codigoBarra = "";
            cantidadArticulo = BigDecimal.ONE;
            selectedClient = new Clients();
            carrito = new ArrayList<>();

            PrimeFaces.current().executeScript("window.close();");
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }

    public void processCodigoBarra() {
        try {
            String codigo = this.codigoBarra;
            BigDecimal cantidad = this.cantidadArticulo;

            if (codigo != null && !codigo.isBlank()) {
                Articulos articulo = articuloController.findArticuloByBarCode(codigo);

                if (articulo != null) {
                    if (cantidad.compareTo(BigDecimal.ZERO) == 1) {
                        ArticuloCarrito articuloCarrito = new ArticuloCarrito();
                        articuloCarrito.setArticulo(articulo);
                        articuloCarrito.setCantidad(cantidad);
                        boolean found = false;

                        // Recorremos el carrito para ver si ya existe el artículo Y No es una promo...
                        for (ArticuloCarrito item : carrito) {
                            if (item.getArticulo().getCodigo() == articulo.getCodigo() && !item.isPromo()) {
                                item.setCantidad(item.getCantidad().add(cantidad)); // Sumamos la cantidad existente con la nueva
                                found = true;
                                break;
                            }
                        }

                        // Si no lo encontró en el carrito, lo agrega con la cantidad especificada
                        if (!found) {
                            carrito.add(articuloCarrito); // Add to carrito first
                        }

                        // Check for active promotions in the entire cart
                        procesarPromocionesCarrito();

                        // Limpiamos los campos
                        codigoBarra = "";
                        cantidadArticulo = BigDecimal.ONE;
                        resetFlag = !resetFlag; // Toggle el reset flag

                        FacesContext.getCurrentInstance().addMessage(null,
                                new FacesMessage(FacesMessage.SEVERITY_INFO, "Artículo agregado",
                                        "El artículo fue agregado al carrito"));
                    } else {
                        FacesContext.getCurrentInstance().addMessage(null,
                                new FacesMessage(FacesMessage.SEVERITY_ERROR, "No hay cantidad",
                                        "La cantidad es inválida"));
                    }
                } else {
                    FacesContext.getCurrentInstance().addMessage(null,
                            new FacesMessage(FacesMessage.SEVERITY_ERROR, "Artículo no encontrado",
                                    "El código de barra no corresponde a un artículo válido"));
                }
            } else {
                FacesContext.getCurrentInstance().addMessage(null,
                        new FacesMessage(FacesMessage.SEVERITY_ERROR, "Código de barra vacío o nulo",
                                "El código de barra no corresponde a un artículo válido"));
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error : " + e.getMessage(), null, 0, "CarritoService.method()", null, e.getMessage());
        }
    }
}
