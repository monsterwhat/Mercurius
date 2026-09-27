package Services;

import Models.ConfiguracionAplicacion;
import Models.Sucursal;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Servicio del registro de sucursales y terminales.
 *
 * <p>Es la <b>unica</b> via para distinguir puntos de venta: registrar
 * (sucursal, terminal) y seleccionar cual de ellos opera. Al seleccionar se
 * copia el par a {@code ConfiguracionAplicacion.codigoSucursal} /
 * {@code codigoTerminal}, que es de donde la emision arma el
 * NumeroConsecutivo. No se toca Services.ConsecutivoEmitidoService ni el
 * consecutivo global: el contador por (sucursal, terminal, tipo) ya existe y
 * sigue igual.</p>
 *
 * <p>Con una sola fila registrada (caso de una sola sucursal) el par global
 * queda en "001" / "001", exactamente el valor que las emisiones ya usan por
 * defecto, asi que la secuencia probada no cambia.</p>
 *
 * @author Mercurius
 */
@Named
@ApplicationScoped
public class SucursalService extends GService<Sucursal> {

    private static final Logger LOG = Logger.getLogger(SucursalService.class);

    /** Sucursal por defecto: 3 digitos, la misma que usa la emision al vuelo. */
    public static final String CODIGO_SUCURSAL_POR_DEFECTO = "001";

    /** Terminal por defecto: 5 digitos, el mismo que produce "%05d" sobre "001". */
    public static final String CODIGO_TERMINAL_POR_DEFECTO = "00001";

    public static final String NOMBRE_SUCURSAL_POR_DEFECTO = "Sucursal principal";
    public static final String NOMBRE_TERMINAL_POR_DEFECTO = "Terminal principal";

    private static final int MAXIMO_SUCURSAL = 999;
    private static final int MAXIMO_TERMINAL = 99999;
    private static final int MAXIMO_NOMBRE = 120;

    @Inject
    @Nonnull
    AppSettingsService appSettingsService;

    @Override
    protected @Nonnull Class<Sucursal> getEntityClass() {
        return Sucursal.class;
    }

    /** Todas las filas, ordenada por codigo, para pintar el registro. */
    @Transactional
    public @Nonnull List<Sucursal> listar() {
        return em.createQuery(
                "SELECT s FROM Sucursal s ORDER BY s.codigoSucursal, s.codigoTerminal",
                Sucursal.class).getResultList();
    }

    /** Solo los puntos de venta que se pueden seleccionar para emitir. */
    @Transactional
    public @Nonnull List<Sucursal> listarActivas() {
        return em.createQuery(
                "SELECT s FROM Sucursal s WHERE s.activo = true ORDER BY s.codigoSucursal, s.codigoTerminal",
                Sucursal.class).getResultList();
    }

    /**
     * Devuelve la primera fila registrada, creando el par por defecto
     * (001 / 00001) si el registro esta vacio.
     *
     * <p>No toca {@code ConfiguracionAplicacion}: con las columnas globales
     * vacias la emision ya usa "001" / "001" por su cuenta, y ese valor se
     * respeta tal cual.</p>
     */
    @Transactional
    public @Nonnull Sucursal asegurarPorDefecto() {
        List<Sucursal> existentes = em.createQuery(
                "SELECT s FROM Sucursal s ORDER BY s.codigoSucursal, s.codigoTerminal",
                Sucursal.class).setMaxResults(1).getResultList();
        if (!existentes.isEmpty()) {
            return existentes.get(0);
        }

        Sucursal porDefecto = new Sucursal();
        porDefecto.setCodigoSucursal(CODIGO_SUCURSAL_POR_DEFECTO);
        porDefecto.setNombreSucursal(NOMBRE_SUCURSAL_POR_DEFECTO);
        porDefecto.setCodigoTerminal(CODIGO_TERMINAL_POR_DEFECTO);
        porDefecto.setNombreTerminal(NOMBRE_TERMINAL_POR_DEFECTO);
        porDefecto.setActivo(Boolean.TRUE);
        em.persist(porDefecto);
        em.flush();

        LOG.info("Registro de sucursales inicializado con el punto de venta por defecto "
                + CODIGO_SUCURSAL_POR_DEFECTO + " / " + CODIGO_TERMINAL_POR_DEFECTO);
        return porDefecto;
    }

    /**
     * Registra un punto de venta nuevo. Normaliza los codigos al ancho del
     * consecutivo (3 digitos de sucursal, 5 de terminal).
     *
     * @throws IllegalArgumentException si un codigo no es valido, el nombre
     *         excede el ancho de la columna o el par ya esta registrado
     */
    @Transactional
    public @Nonnull Sucursal registrar(@Nullable String codigoSucursal,
                                       @Nullable String nombreSucursal,
                                       @Nullable String codigoTerminal,
                                       @Nullable String nombreTerminal) {
        String sucursal = normalizarCodigoSucursal(codigoSucursal);
        String terminal = normalizarCodigoTerminal(codigoTerminal);
        String nombreS = normalizarNombre(nombreSucursal, "El nombre de la sucursal");
        String nombreT = normalizarNombre(nombreTerminal, "El nombre del terminal");

        List<Sucursal> repetidos = em.createQuery(
                "SELECT s FROM Sucursal s WHERE s.codigoSucursal = :sucursal AND s.codigoTerminal = :terminal",
                Sucursal.class)
                .setParameter("sucursal", sucursal)
                .setParameter("terminal", terminal)
                .setMaxResults(1)
                .getResultList();
        if (!repetidos.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ya existe el punto de venta " + sucursal + " / " + terminal + " en el registro.");
        }

        Sucursal nueva = new Sucursal();
        nueva.setCodigoSucursal(sucursal);
        nueva.setNombreSucursal(nombreS);
        nueva.setCodigoTerminal(terminal);
        nueva.setNombreTerminal(nombreT);
        nueva.setActivo(Boolean.TRUE);
        em.persist(nueva);
        em.flush();

        LOG.info("Punto de venta registrado: sucursal=" + sucursal + " | terminal=" + terminal);
        return nueva;
    }

    /**
     * Activa o desactiva un punto de venta del registro. Solo impide
     * seleccionarlo: el par global y la secuencia emitida no cambian.
     */
    @Transactional
    public @Nonnull Sucursal cambiarActivo(@Nullable Long id, boolean activo) {
        Sucursal sucursal = buscar(id);
        sucursal.setActivo(activo);
        em.merge(sucursal);
        em.flush();
        LOG.info("Punto de venta " + sucursal.getCodigoSucursal() + " / "
                + sucursal.getCodigoTerminal() + " ahora esta " + (activo ? "activo" : "inactivo"));
        return sucursal;
    }

    /**
     * Marca el punto de venta como el que opera: copia su par a la
     * configuracion global, que es la que leen las emisiones al armar el
     * consecutivo. El contador por (sucursal, terminal, tipo) ya distingue cada
     * punto de venta, asi que al volver a esta sucursal la secuencia continua
     * donde iba.
     */
    @Transactional
    public @Nonnull Sucursal seleccionar(@Nullable Long id) {
        Sucursal sucursal = buscar(id);
        if (!Boolean.TRUE.equals(sucursal.getActivo())) {
            throw new IllegalArgumentException(
                    "El punto de venta " + sucursal.getCodigoSucursal() + " / "
                            + sucursal.getCodigoTerminal() + " esta inactivo; activelo antes de seleccionarlo.");
        }

        ConfiguracionAplicacion settings = appSettingsService.findOrCreateCurrent();
        settings.setCodigoSucursal(sucursal.getCodigoSucursal());
        settings.setCodigoTerminal(sucursal.getCodigoTerminalConfiguracion());
        appSettingsService.update(settings);

        LOG.info("Punto de venta seleccionado: sucursal=" + sucursal.getCodigoSucursal()
                + " | terminal=" + sucursal.getCodigoTerminal());
        return sucursal;
    }

    /**
     * Fila del registro que coincide con el par guardado en la configuracion
     * global, o null si ese par no esta registrado. La comparacion es numerica
     * a proposito: la configuracion guarda el terminal en 3 digitos ("001") y
     * el registro en 5 ("00001"), que son el mismo punto de venta.
     */
    @Transactional
    public @Nullable Sucursal seleccionada() {
        ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
        long sucursalEsperada = valorCodigo(settings == null ? null : settings.getCodigoSucursal(), 1L);
        long terminalEsperado = valorCodigo(settings == null ? null : settings.getCodigoTerminal(), 1L);

        for (Sucursal fila : listar()) {
            if (Long.parseLong(fila.getCodigoSucursal()) == sucursalEsperada
                    && Long.parseLong(fila.getCodigoTerminal()) == terminalEsperado) {
                return fila;
            }
        }
        return null;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private @Nonnull Sucursal buscar(@Nullable Long id) {
        if (id == null) {
            throw new IllegalArgumentException("Debe indicar el punto de venta del registro.");
        }
        Sucursal sucursal = em.find(Sucursal.class, id);
        if (sucursal == null) {
            throw new IllegalArgumentException("El punto de venta seleccionado no existe en el registro.");
        }
        return sucursal;
    }

    private static @Nonnull String normalizarCodigoSucursal(@Nullable String codigo) {
        return normalizarCodigo(codigo, MAXIMO_SUCURSAL, 3, "sucursal");
    }

    private static @Nonnull String normalizarCodigoTerminal(@Nullable String codigo) {
        return normalizarCodigo(codigo, MAXIMO_TERMINAL, 5, "terminal");
    }

    /**
     * Solo digitos dentro del rango del consecutivo. No se "limpia" la entrada
     * (quitar signos o espacios seria persistir algo que nadie escribio): se
     * rechaza y se avisa.
     */
    private static @Nonnull String normalizarCodigo(@Nullable String codigo, int maximo, int digitos, String tipo) {
        if (codigo == null || codigo.isBlank()) {
            throw new IllegalArgumentException("El codigo de " + tipo + " es requerido.");
        }
        String valor = codigo.trim();
        if (!valor.matches("[0-9]+")) {
            throw new IllegalArgumentException(
                    "El codigo de " + tipo + " debe ser un numero sin espacios ni letras (valor recibido: "
                            + codigo + ").");
        }
        long numero;
        try {
            numero = Long.parseLong(valor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El codigo de " + tipo + " debe ser un numero entre 1 y " + maximo + ".");
        }
        if (numero < 1L || numero > maximo) {
            throw new IllegalArgumentException(
                    "El codigo de " + tipo + " debe estar entre 1 y " + maximo + " (por ejemplo "
                            + (digitos == 3 ? CODIGO_SUCURSAL_POR_DEFECTO : CODIGO_TERMINAL_POR_DEFECTO) + ").");
        }
        return String.format("%0" + digitos + "d", numero);
    }

    private static @Nullable String normalizarNombre(@Nullable String nombre, String etiqueta) {
        if (nombre == null || nombre.isBlank()) {
            return null;
        }
        String valor = nombre.trim();
        if (valor.length() > MAXIMO_NOMBRE) {
            throw new IllegalArgumentException(
                    etiqueta + " no puede superar " + MAXIMO_NOMBRE + " caracteres.");
        }
        return valor;
    }

    /** Valor numerico de un codigo global; null, vacio o no numerico = 1. */
    private static long valorCodigo(@Nullable String codigo, long porDefecto) {
        if (codigo == null || codigo.isBlank() || !codigo.trim().matches("[0-9]+")) {
            return porDefecto;
        }
        try {
            return Long.parseLong(codigo.trim());
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }
}
