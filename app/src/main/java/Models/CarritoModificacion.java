package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Bitacora de modificaciones del carrito del POS.
 *
 * <p>Registra lo que el cajero hace con un ticket <b>antes de facturarlo</b>:
 * quitar una linea o cancelar la venta a medio hacer. No tiene relacion con
 * Hacienda ni con las facturas emitidas: es control interno para saber quien
 * cambio que y cuando.</p>
 *
 * <p>Reemplaza la tabla {@code alertas} del sistema anterior, que mezclaba este
 * registro con el log general de la aplicacion.</p>
 *
 * <p>Las lineas de una misma cancelacion comparten {@link #referencia}, para
 * poder reconstruir el carrito completo que se descarto.</p>
 */
@Entity
@Data
@Table(name = "carrito_modificacion")
public class CarritoModificacion implements Serializable {

    /** Accion: se quito una linea del carrito. */
    public static final String ACCION_LINEA_ELIMINADA = "LINEA_ELIMINADA";

    /** Accion: se cancelo la venta completa (factura a medio hacer). */
    public static final String ACCION_CARRITO_CANCELADO = "CARRITO_CANCELADO";

    /** Accion: el cajero cambio el precio de una linea. */
    public static final String ACCION_PRECIO_AJUSTADO = "PRECIO_AJUSTADO";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime fecha;

    /** Usuario del POS que hizo el cambio. */
    @Column(nullable = false, length = 100)
    private String usuario;

    @Column(nullable = false, length = 40)
    private String accion;

    @Column(length = 30)
    private String articuloCodigo;

    @Column(length = 200)
    private String articuloNombre;

    @Column(precision = 12, scale = 3)
    private BigDecimal cantidad;

    /** Precio unitario final (IVA incluido) al momento del cambio. */
    @Column(precision = 14, scale = 2)
    private BigDecimal precioUnitario;

    @Column(precision = 14, scale = 2)
    private BigDecimal importe;

    @Column(length = 200)
    private String cliente;

    /** Identifica la cancelacion: todas las lineas del mismo carrito la comparten. */
    @Column(length = 40)
    private String referencia;

    @Column(columnDefinition = "text")
    private String detalle;
}
