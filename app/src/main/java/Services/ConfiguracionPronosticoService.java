package Services;

import Models.ConfiguracionPronostico;
import Models.Usuarios;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Configuracion vigente de la economia de pronostico y reposicion.
 *
 * <p>Patron INSERT-only: la configuracion actual es la de mayor id y cada
 * guardado crea una fila nueva. Un fallo de lectura no tumba el pronostico:
 * se registra y se usan los valores por defecto, que son los mismos con los
 * que arranca una instalacion nueva.</p>
 */
@Named
@ApplicationScoped
public class ConfiguracionPronosticoService extends GService<ConfiguracionPronostico> {

    private static final Logger LOG = Logger.getLogger(ConfiguracionPronosticoService.class);

    private static final ParametrosPronostico POR_DEFECTO =
            new ParametrosPronostico(0.95, 10.0, 1.0, 7, 0, 1);

    /** Parametros ya resueltos y validados para el motor. */
    public record ParametrosPronostico(double nivelServicio,
                                       double costoFaltante,
                                       double costoMantenimiento,
                                       int plazoReposicionDias,
                                       int cantidadMinimaPedido,
                                       int multiploPedido) {

        public ParametrosPronostico {
            nivelServicio = Double.isFinite(nivelServicio) && nivelServicio > 0.0
                    && nivelServicio < 1.0 ? nivelServicio : 0.95;
            costoFaltante = Double.isFinite(costoFaltante) && costoFaltante >= 0.0
                    ? costoFaltante : 10.0;
            costoMantenimiento = Double.isFinite(costoMantenimiento) && costoMantenimiento >= 0.0
                    ? costoMantenimiento : 1.0;
            plazoReposicionDias = Math.max(1, plazoReposicionDias);
            cantidadMinimaPedido = Math.max(0, cantidadMinimaPedido);
            multiploPedido = Math.max(1, multiploPedido);
        }
    }

    @Override
    protected @Nonnull Class<ConfiguracionPronostico> getEntityClass() {
        return ConfiguracionPronostico.class;
    }

    /** Configuracion vigente (mayor id), o {@code null} si la tabla esta vacia. */
    @Nullable
    public ConfiguracionPronostico getConfiguracionActual() {
        try {
            TypedQuery<ConfiguracionPronostico> query = em.createQuery(
                    "SELECT c FROM ConfiguracionPronostico c ORDER BY c.id DESC",
                    ConfiguracionPronostico.class);
            query.setMaxResults(1);
            List<ConfiguracionPronostico> filas = query.getResultList();
            return filas.isEmpty() ? null : filas.get(0);
        } catch (PersistenceException e) {
            LOG.warn("No se pudo leer la configuracion de pronostico; se usan los valores por defecto", e);
            return null;
        }
    }

    /** Configuracion vigente, creando la fila por defecto si la tabla esta vacia. */
    @Transactional
    @Nonnull
    public ConfiguracionPronostico findOrCreateDefault() {
        ConfiguracionPronostico actual = getConfiguracionActual();
        if (actual != null) {
            return actual;
        }
        ConfiguracionPronostico nuevo = ConfiguracionPronostico.defecto();
        em.persist(nuevo);
        em.flush();
        LOG.info("Configuracion de pronostico creada con valores por defecto"
                + " (nivel=0.950, faltante=10.00, mantenimiento=1.00, plazo=7, minimo=0, multiplo=1)");
        return nuevo;
    }

    /** Parametros resueltos para el motor; nunca devuelve null. */
    @Transactional
    @Nonnull
    public ParametrosPronostico parametros() {
        ConfiguracionPronostico config = getConfiguracionActual();
        if (config == null) {
            return POR_DEFECTO;
        }
        return new ParametrosPronostico(
                decimal(config.getNivelServicio(), 0.95),
                decimal(config.getCostoFaltante(), 10.0),
                decimal(config.getCostoMantenimiento(), 1.0),
                config.getPlazoReposicionDias(),
                config.getCantidadMinimaPedido(),
                config.getMultiploPedido());
    }

    /** Historial completo, mas reciente primero. */
    @Nonnull
    public List<ConfiguracionPronostico> listAllOrderById() {
        try {
            return em.createQuery(
                            "SELECT c FROM ConfiguracionPronostico c ORDER BY c.id DESC",
                            ConfiguracionPronostico.class)
                    .getResultList();
        } catch (PersistenceException e) {
            LOG.warn("No se pudo leer el historial de configuracion de pronostico", e);
            return Collections.emptyList();
        }
    }

    /** Guarda una configuracion nueva (INSERT-only) y devuelve la fila creada. */
    @Transactional
    @Nonnull
    public ConfiguracionPronostico guardar(@Nonnull ConfiguracionPronostico configuracion,
                                           @Nullable Usuarios usuario) {
        configuracion.setUsuario(usuario);
        em.persist(configuracion);
        em.flush();
        LOG.info("Configuracion de pronostico guardada"
                + " | user=" + (usuario == null ? "sistema" : usuario.getUsername())
                + " | nivel=" + configuracion.getNivelServicio()
                + " | faltante=" + configuracion.getCostoFaltante()
                + " | mantenimiento=" + configuracion.getCostoMantenimiento()
                + " | plazo=" + configuracion.getPlazoReposicionDias()
                + " | minimo=" + configuracion.getCantidadMinimaPedido()
                + " | multiplo=" + configuracion.getMultiploPedido());
        return configuracion;
    }

    private static double decimal(@Nullable BigDecimal valor, double porDefecto) {
        return valor == null ? porDefecto : valor.doubleValue();
    }
}
