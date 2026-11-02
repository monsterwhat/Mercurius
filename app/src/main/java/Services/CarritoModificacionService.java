package Services;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.CarritoModificacion;
import Models.Usuarios;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Guarda las modificaciones del carrito del POS (control interno).
 *
 * <p>Se llama desde {@link CarritoService} cuando el cajero quita una linea o
 * cancela la venta a medio hacer. Es auditoria: ninguno de estos metodos puede
 * romper el POS, por eso atrapan y registran cualquier error en vez de
 * propagarlo.</p>
 */
@ApplicationScoped
public class CarritoModificacionService extends GService<CarritoModificacion> {

    private static final Logger LOG = Logger.getLogger(CarritoModificacionService.class);

    @Override
    protected Class<CarritoModificacion> getEntityClass() {
        return CarritoModificacion.class;
    }

    /** Registra que se quito una linea del carrito. */
    @Transactional
    public void registrarLineaEliminada(ArticuloCarrito linea, Usuarios usuario, String cliente) {
        if (linea == null || usuario == null) {
            LOG.debug("Se omite el registro de linea eliminada: falta la linea o el usuario"
                    + " | source=CarritoModificacionService.registrarLineaEliminada()");
            return;
        }
        CarritoModificacion fila = nuevaFila(usuario, CarritoModificacion.ACCION_LINEA_ELIMINADA, cliente);
        fila.setReferencia(UUID.randomUUID().toString());
        llenarDesdeLinea(fila, linea);
        guardar(fila);
    }

    /**
     * Registra la cancelacion de una venta a medio hacer.
     *
     * <p>Si el carrito tenia lineas, se guarda una fila por linea (todas con la
     * misma referencia). Si estaba vacio, se guarda una sola fila sin articulo
     * para dejar constancia de la cancelacion.</p>
     *
     * @param carrito  lineas del carrito al momento de cancelar (copia)
     * @param usuario  cajero que cancela
     * @param cliente  cliente seleccionado, si habia
     * @param detalle  resumen legible del carrito cancelado
     */
    @Transactional
    public void registrarCancelacion(List<ArticuloCarrito> carrito, Usuarios usuario, String cliente,
                                     String detalle) {
        if (usuario == null) {
            LOG.debug("Se omite el registro de cancelacion: falta el usuario"
                    + " | source=CarritoModificacionService.registrarCancelacion()");
            return;
        }
        String referencia = UUID.randomUUID().toString();
        if (carrito == null || carrito.isEmpty()) {
            CarritoModificacion fila = nuevaFila(usuario, CarritoModificacion.ACCION_CARRITO_CANCELADO, cliente);
            fila.setReferencia(referencia);
            fila.setDetalle(detalle);
            guardar(fila);
            return;
        }
        for (ArticuloCarrito linea : carrito) {
            CarritoModificacion fila = nuevaFila(usuario, CarritoModificacion.ACCION_CARRITO_CANCELADO, cliente);
            fila.setReferencia(referencia);
            llenarDesdeLinea(fila, linea);
            guardar(fila);
        }
    }

    /**
     * Registra un cambio manual de precio en una linea del carrito.
     *
     * @param linea          linea afectada
     * @param precioAnterior precio final que tenia (puede ser null)
     * @param precioNuevo    precio final aplicado
     * @param usuario        cajero
     * @param cliente        cliente seleccionado
     * @param motivo         motivo del ajuste o texto de restauracion
     */
    @Transactional
    public void registrarPrecioAjustado(ArticuloCarrito linea, BigDecimal precioAnterior,
                                        BigDecimal precioNuevo, Usuarios usuario, String cliente,
                                        String motivo) {
        if (linea == null || usuario == null) {
            LOG.debug("Se omite el registro de cambio de precio: falta la linea o el usuario"
                    + " | source=CarritoModificacionService.registrarPrecioAjustado()");
            return;
        }
        CarritoModificacion fila = nuevaFila(usuario, CarritoModificacion.ACCION_PRECIO_AJUSTADO, cliente);
        fila.setReferencia(UUID.randomUUID().toString());
        llenarDesdeLinea(fila, linea);
        fila.setPrecioUnitario(precioNuevo);
        if (precioNuevo != null && linea.getCantidad() != null) {
            fila.setImporte(precioNuevo.multiply(linea.getCantidad()));
        }
        StringBuilder detalle = new StringBuilder();
        if (precioAnterior != null) {
            detalle.append("Precio anterior: ").append(precioAnterior.toPlainString()).append(". ");
        }
        if (precioNuevo != null) {
            detalle.append("Precio nuevo: ").append(precioNuevo.toPlainString()).append(". ");
        }
        if (motivo != null && !motivo.isBlank()) {
            detalle.append("Motivo: ").append(motivo.trim());
        }
        fila.setDetalle(detalle.toString().trim());
        guardar(fila);
    }

    private CarritoModificacion nuevaFila(Usuarios usuario, String accion, String cliente) {        CarritoModificacion fila = new CarritoModificacion();
        fila.setFecha(LocalDateTime.now());
        fila.setUsuario(usuario.getUsername());
        fila.setAccion(accion);
        fila.setCliente(cliente);
        return fila;
    }

    private void llenarDesdeLinea(CarritoModificacion fila, ArticuloCarrito linea) {
        fila.setCantidad(linea.getCantidad());
        try {
            if (linea.getArticulo() != null) {
                fila.setArticuloCodigo(linea.getArticulo().getCodigoBarra());
                fila.setArticuloNombre(linea.getArticulo().getNombre());
            }
        } catch (RuntimeException e) {
            // El carrito vive en la sesion: si el articulo llega como proxy
            // desconectado no se puede leer, pero el resto del rastro si queda.
            LOG.warn("No se pudo leer el articulo de la linea para la bitacora"
                    + " | source=CarritoModificacionService.llenarDesdeLinea()", e);
        }
        BigDecimal precio = null;
        try {
            precio = linea.getPrecioFinalEfectivo();
        } catch (RuntimeException e) {
            // Sin CAByS o sin precio vigente no se puede calcular el final; se
            // guarda el precio base si existe y la fila queda igual.
            precio = linea.getPrecioPersonalizado();
            LOG.debug("Precio final no disponible en la bitacora; se usa el personalizado"
                    + " | source=CarritoModificacionService.llenarDesdeLinea()");
        }
        fila.setPrecioUnitario(precio);
        if (precio != null && linea.getCantidad() != null) {
            fila.setImporte(precio.multiply(linea.getCantidad()));
        }
    }

    private void guardar(CarritoModificacion fila) {
        try {
            em.persist(fila);
            em.flush();
        } catch (RuntimeException e) {
            LOG.error("No se pudo registrar la modificacion de carrito"
                    + " | accion=" + fila.getAccion()
                    + " | usuario=" + fila.getUsuario()
                    + " | source=CarritoModificacionService.guardar()", e);
        }
    }
}
