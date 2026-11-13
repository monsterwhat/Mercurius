package Services;

import Models.Articulos.Articulos;
import Models.Detalles.CodigoComercial;
import Models.Detalles.LineaDetalle;
import Models.Inventario;
import Models.Usuarios;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Reingreso de inventario para documentos que se revierten: una nota de
 * credito o la anulacion de un comprobante pendiente local.
 *
 * <p>La venta descuenta stock en {@code CarritoService.ajustarInventario}
 * escribiendo un movimiento NEGATIVO por linea. Revertir es el espejo exacto:
 * un movimiento POSITIVO con el mismo articulo, de modo que
 * {@code InventarioService.updateStock} devuelva las unidades a
 * {@code articulostock}.</p>
 *
 * <p>El motor legacy de devoluciones escribia el movimiento con
 * {@code articulo = null} y {@code create()}, que no toca {@code articulostock}:
 * la nota de credito quedaba registrada pero el stock nunca volvia. Aqui se
 * resuelve el articulo por el codigo comercial de la linea (o por nombre como
 * ultimo recurso) y, cuando no se puede resolver, se conserva el movimiento sin
 * articulo para no perder el rastro contable.</p>
 */
@ApplicationScoped
public class ReversionInventarioService {

    private static final Logger LOG = Logger.getLogger(ReversionInventarioService.class);

    /** Tipo de movimiento de una anulacion local. */
    public static final String TIPO_ANULACION = "Anulacion";

    /** Tipo de movimiento de una nota de credito. */
    public static final String TIPO_DEVOLUCION = "Devolucion";

    @Inject
    ArticulosService articulosService;

    @Inject
    InventarioService inventarioService;

    /** Una linea y la cantidad que se revierte (puede ser parcial). */
    public record LineaACreditar(@Nonnull LineaDetalle linea, @Nonnull BigDecimal cantidad) {
    }

    /**
     * Reingresa todas las lineas de un comprobante.
     *
     * @return cuantas lineas movieron inventario
     */
    @Transactional
    public int reingresarTodo(@Nullable List<LineaDetalle> lineas,
                              @Nonnull String nota,
                              @Nullable Usuarios usuario) {
        List<LineaACreditar> todas = new ArrayList<>();
        if (lineas != null) {
            for (LineaDetalle linea : lineas) {
                if (linea.getCantidad() != null && linea.getCantidad().signum() > 0) {
                    todas.add(new LineaACreditar(linea, linea.getCantidad()));
                }
            }
        }
        return reingresar(todas, TIPO_ANULACION, nota, usuario);
    }

    /**
     * Reingresa las cantidades indicadas (una nota de credito parcial incluye
     * solo las lineas y cantidades acreditadas).
     *
     * @return cuantas lineas movieron inventario
     */
    @Transactional
    public int reingresar(@Nullable List<LineaACreditar> seleccion,
                          @Nonnull String tipoMovimiento,
                          @Nonnull String nota,
                          @Nullable Usuarios usuario) {
        if (seleccion == null || seleccion.isEmpty()) {
            return 0;
        }
        int movidas = 0;
        for (LineaACreditar sel : seleccion) {
            if (sel.cantidad() == null || sel.cantidad().signum() <= 0) {
                continue;
            }
            if (Boolean.TRUE.equals(sel.linea().getEsServicio())) {
                // Un servicio no tiene existencias: mover stock lo inventaria.
                LOG.debug("Linea de servicio sin movimiento de inventario: "
                        + sel.linea().getDetalle()
                        + " | source=ReversionInventarioService.reingresar()");
                continue;
            }
            Articulos articulo = resolverArticulo(sel.linea());
            Inventario movimiento = new Inventario();
            movimiento.setArticulo(articulo);
            movimiento.setCantidad(sel.cantidad());
            movimiento.setFechaMovimiento(new Date());
            movimiento.setNotas(nota);
            movimiento.setProcessed(Boolean.TRUE);
            movimiento.setStatus(Boolean.TRUE);
            movimiento.setTipoMovimiento(tipoMovimiento);
            movimiento.setUnidadesRecomendadasFactura(sel.cantidad());
            movimiento.setUsuario(usuario);

            if (articulo == null) {
                // Sin articulo no se puede tocar articulostock; el movimiento
                // queda como rastro y el operador debe ajustar a mano.
                LOG.warn("No se pudo resolver el articulo de la linea \""
                        + sel.linea().getDetalle() + "\" (codigoCabys "
                        + sel.linea().getCodigoCabys() + "): se registra el movimiento"
                        + " sin articulo y el stock NO se reingresa"
                        + " | source=ReversionInventarioService.reingresar()");
                inventarioService.create(movimiento);
            } else {
                inventarioService.update(movimiento);
            }
            movidas++;
        }
        return movidas;
    }

    /** Articulo de la linea: primer codigo comercial que exista, luego nombre. */
    private @Nullable Articulos resolverArticulo(@Nonnull LineaDetalle linea) {
        if (linea.getCodigosComerciales() != null) {
            for (CodigoComercial codigo : linea.getCodigosComerciales()) {
                if (codigo.getCodigo() == null || codigo.getCodigo().isBlank()) {
                    continue;
                }
                Articulos articulo = articulosService.findByBarCode(codigo.getCodigo());
                if (articulo != null) {
                    return articulo;
                }
            }
        }
        if (linea.getDetalle() != null && !linea.getDetalle().isBlank()) {
            return articulosService.findByName(linea.getDetalle());
        }
        return null;
    }
}
