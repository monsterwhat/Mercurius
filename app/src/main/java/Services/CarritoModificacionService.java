package Services;

import Models.Articulos.Carrito.ArticuloCarrito;
import Models.CarritoModificacion;
import Models.Usuarios;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Guarda y consulta las modificaciones del carrito del POS (control interno).
 *
 * <p>Se llama desde {@link CarritoService} cuando el cajero quita una linea o
 * cancela la venta a medio hacer, y desde el ajuste de precio del POS. Es
 * auditoria: ninguno de los metodos de escritura puede romper el POS, por eso
 * atrapan y registran cualquier error en vez de propagarlo.</p>
 */
@ApplicationScoped
public class CarritoModificacionService extends GService<CarritoModificacion> {

    private static final Logger LOG = Logger.getLogger(CarritoModificacionService.class);

    @Override
    protected Class<CarritoModificacion> getEntityClass() {
        return CarritoModificacion.class;
    }

    // ── Escritura ───────────────────────────────────────────────────────────

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

    /** Registra un cambio manual de precio en una linea del carrito. */
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

    // ── Consulta (pagina de la app) ─────────────────────────────────────────

    /** Cuenta las filas que cumplen los filtros. */
    @Transactional(TxType.SUPPORTS)
    public long contar(@Nullable LocalDateTime desde, @Nullable LocalDateTime hasta,
                       @Nullable String usuario, @Nullable String accion, @Nullable String q) {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT count(m) FROM CarritoModificacion m"
                    + where(desde, hasta, usuario, accion, q), Long.class);
            bind(query, desde, hasta, usuario, accion, q);
            Long total = query.getSingleResult();
            return total != null ? total : 0L;
        } catch (RuntimeException e) {
            LOG.error("No se pudo contar la bitacora del carrito"
                    + " | source=CarritoModificacionService.contar()", e);
            return 0L;
        }
    }

    /** Una pagina de filas, ordenada por la columna pedida (whitelist). */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<CarritoModificacion> pagina(@Nullable LocalDateTime desde,
                                                     @Nullable LocalDateTime hasta,
                                                     @Nullable String usuario,
                                                     @Nullable String accion,
                                                     @Nullable String q,
                                                     @Nullable String sort,
                                                     @Nullable String dir,
                                                     int offset, int size) {
        try {
            TypedQuery<CarritoModificacion> query = em.createQuery(
                    "SELECT m FROM CarritoModificacion m"
                    + where(desde, hasta, usuario, accion, q)
                    + " ORDER BY " + orden(sort, dir) + ", m.id DESC",
                    CarritoModificacion.class);
            bind(query, desde, hasta, usuario, accion, q);
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.min(Math.max(size, 1), 200));
            return query.getResultList();
        } catch (RuntimeException e) {
            LOG.error("No se pudo leer la bitacora del carrito"
                    + " | source=CarritoModificacionService.pagina()", e);
            return List.of();
        }
    }

    /** Conteo por accion sobre los mismos filtros (tarjetas del encabezado). */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull Map<String, Long> conteoPorAccion(@Nullable LocalDateTime desde,
                                                      @Nullable LocalDateTime hasta,
                                                      @Nullable String usuario,
                                                      @Nullable String accion,
                                                      @Nullable String q) {
        Map<String, Long> resultado = new LinkedHashMap<>();
        try {
            TypedQuery<Object[]> query = em.createQuery(
                    "SELECT m.accion, count(m) FROM CarritoModificacion m"
                    + where(desde, hasta, usuario, accion, q)
                    + " GROUP BY m.accion", Object[].class);
            bind(query, desde, hasta, usuario, accion, q);
            for (Object[] fila : query.getResultList()) {
                resultado.put(String.valueOf(fila[0]), ((Number) fila[1]).longValue());
            }
        } catch (RuntimeException e) {
            LOG.error("No se pudo agrupar la bitacora del carrito"
                    + " | source=CarritoModificacionService.conteoPorAccion()", e);
        }
        return resultado;
    }

    /** Usuarios que aparecen en la bitacora, para el filtro. */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<String> usuarios() {
        try {
            return em.createQuery(
                    "SELECT DISTINCT m.usuario FROM CarritoModificacion m"
                    + " WHERE m.usuario IS NOT NULL ORDER BY m.usuario", String.class)
                    .getResultList();
        } catch (RuntimeException e) {
            LOG.error("No se pudieron leer los usuarios de la bitacora"
                    + " | source=CarritoModificacionService.usuarios()", e);
            return List.of();
        }
    }

    // ── Internos ────────────────────────────────────────────────────────────

    private static String where(@Nullable LocalDateTime desde, @Nullable LocalDateTime hasta,
                                @Nullable String usuario, @Nullable String accion, @Nullable String q) {
        List<String> condiciones = new ArrayList<>();
        if (desde != null) {
            condiciones.add("m.fecha >= :desde");
        }
        if (hasta != null) {
            condiciones.add("m.fecha < :hasta");
        }
        if (usuario != null && !usuario.isBlank()) {
            condiciones.add("m.usuario = :usuario");
        }
        if (accion != null && !accion.isBlank()) {
            condiciones.add("m.accion = :accion");
        }
        if (q != null && !q.isBlank()) {
            condiciones.add("(lower(coalesce(m.articuloNombre, '')) LIKE :q"
                    + " OR lower(coalesce(m.articuloCodigo, '')) LIKE :q"
                    + " OR lower(coalesce(m.cliente, '')) LIKE :q)");
        }
        return condiciones.isEmpty() ? "" : " WHERE " + String.join(" AND ", condiciones);
    }

    private static void bind(TypedQuery<?> query, @Nullable LocalDateTime desde,
                             @Nullable LocalDateTime hasta, @Nullable String usuario,
                             @Nullable String accion, @Nullable String q) {
        if (desde != null) {
            query.setParameter("desde", desde);
        }
        if (hasta != null) {
            query.setParameter("hasta", hasta);
        }
        if (usuario != null && !usuario.isBlank()) {
            query.setParameter("usuario", usuario);
        }
        if (accion != null && !accion.isBlank()) {
            query.setParameter("accion", accion);
        }
        if (q != null && !q.isBlank()) {
            query.setParameter("q", "%" + q.trim().toLowerCase() + "%");
        }
    }

    private static String orden(@Nullable String sort, @Nullable String dir) {
        String columna = switch (sort == null ? "" : sort) {
            case "usuario" -> "m.usuario";
            case "accion" -> "m.accion";
            case "articulo" -> "m.articuloNombre";
            case "codigo" -> "m.articuloCodigo";
            case "cantidad" -> "m.cantidad";
            case "precio" -> "m.precioUnitario";
            case "importe" -> "m.importe";
            case "cliente" -> "m.cliente";
            default -> "m.fecha";
        };
        return columna + ("desc".equalsIgnoreCase(dir) ? " DESC" : " ASC");
    }

    private CarritoModificacion nuevaFila(Usuarios usuario, String accion, String cliente) {
        CarritoModificacion fila = new CarritoModificacion();
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
