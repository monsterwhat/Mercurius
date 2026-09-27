package Models;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

/**
 * Registro de sucursales y terminales (puntos de venta) del negocio.
 *
 * <p>Una fila por combinación (codigoSucursal, codigoTerminal): una sucursal
 * puede tener cuantos terminales se necesiten, y cada terminal es el punto de
 * venta que Hacienda exige como prefijo del consecutivo.</p>
 *
 * <p>Los codigos usan el ancho del consecutivo de Hacienda: <b>3 digitos</b>
 * para la sucursal y <b>5 digitos</b> para el terminal. Asi el par de esta
 * tabla es el mismo que Services.ConsecutivoEmitidoService ya indexa en
 * {@code consecutivo_emitido (sucursal, terminal, tipo)}, de modo que cada
 * punto de venta conserva su propia secuencia por tipo de documento.</p>
 *
 * <p>La tabla se crea sola por
 * {@code quarkus.hibernate-orm.schema-management.strategy=update}; no hace
 * falta script de migracion.</p>
 *
 * <p><b>Relacion con la seleccion activa.</b> El registro unico y explicito de
 * que sucursal/terminal opera lo hace
 * {@code Services.SucursalService.seleccionar()}, que copia el par de la fila
 * elegida a {@code ConfiguracionAplicacion.codigoSucursal} /
 * {@code codigoTerminal}. esos dos campos siguen siendo los que leen
 * ComprobanteService / MensajeReceptorService / DevolucionesResource para
 * armar el NumeroConsecutivo, de modo que la emision no cambia: solo cambia de
 * par al seleccionar.</p>
 */
@Data
@Entity
@Table(name = "sucursal", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"codigo_sucursal", "codigo_terminal"})
})
public class Sucursal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Codigo de sucursal, 3 digitos (por ejemplo "001"). */
    @Column(name = "codigo_sucursal", length = 3, nullable = false)
    private String codigoSucursal;

    /** Nombre legible de la sucursal. */
    @Nullable
    @Column(name = "nombre_sucursal", length = 120)
    private String nombreSucursal;

    /** Codigo de terminal, 5 digitos (por ejemplo "00001"). */
    @Column(name = "codigo_terminal", length = 5, nullable = false)
    private String codigoTerminal;

    /** Nombre legible del terminal. */
    @Nullable
    @Column(name = "nombre_terminal", length = 120)
    private String nombreTerminal;

    /** Si el punto de venta puede seleccionarse para emitir. */
    @Column(name = "activo", nullable = false)
    private Boolean activo = Boolean.TRUE;

    /**
     * El par como lo guarda hoy {@code ConfiguracionAplicacion}: el codigo de
     * terminal del perfil se ha escrito siempre en 3 digitos ("001"), y los
     * consumidores lo reformatean a {@code %05d} al armar el consecutivo. Un
     * terminal de 4 o 5 digitos no cabe en esa convencion, asi que ahi se
     * devuelve el valor completo.
     */
    public String getCodigoTerminalConfiguracion() {
        long valor = Long.parseLong(codigoTerminal);
        return valor <= 999L
                ? String.format("%03d", valor)
                : codigoTerminal;
    }
}
