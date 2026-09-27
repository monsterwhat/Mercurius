package Services;

import Models.ConfiguracionAplicacion;
import Utils.EncryptionUtil;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.enterprise.event.Observes;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigInteger;
import java.util.regex.Pattern;
/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class AppSettingsService extends GService<ConfiguracionAplicacion> {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(AppSettingsService.class);

    /**
     * Rango de TelefonoType en el XSD oficial v4.4: xs:integer con
     * minInclusive=100 y maxInclusive=99999999999999999999. El maximo son 20
     * digitos, el mismo ancho que la columna numero_telefono.
     */
    private static final BigInteger TELEFONO_MINIMO = BigInteger.valueOf(100);
    private static final BigInteger TELEFONO_MAXIMO = new BigInteger("99999999999999999999");

    /** Solo digitos: un NumTelefono es un entero, no un numero de telephone. */
    private static final Pattern TELEFONO_ENTEROS = Pattern.compile("[0-9]+");

    @Override
    protected @Nonnull Class<ConfiguracionAplicacion> getEntityClass() {
        return ConfiguracionAplicacion.class;
    }

    /**
     * Valida el Telefono del perfil antes de persistirlo. TelefonoType exige un
     * xs:integer, asi que un valor con guiones, espacios, prefijo + o letras
     * ("8888-0000", "+506 8888 0000") llega tal cual a <NumTelefono> desde
     * EncabezadoBuilder.buildEmisor y el XML no valida contra el XSD oficial.
     *
     * <p>Se rechaza el valor, no se "limpia": quitar separadores y guardar
     * 88880000 seria persistir algo que el usuario nunca escribio. Un valor
     * vacio NO es error — el elemento es minOccurs="0" — y se normaliza a null
     * ("no configurado"), que es lo que EncabezadoBuilder ya interpreta como
     * "omitir &lt;Telefono&gt;".</p>
     *
     * @throws IllegalArgumentException si el telefono no es un entero dentro
     *         del rango del XSD
     */
    public void validarTelefono(@Nonnull ConfiguracionAplicacion entity) {
        String telefono = entity.getTelefono();

        if (telefono == null || telefono.isBlank()) {
            entity.setTelefono(null); // sin configurar: el <Telefono> es opcional
            return;
        }

        String valor = telefono.trim();

        // Solo digitos: rechaza guiones, espacios, el prefijo "+506", el "-",
        // letras y decimales. Ojo: BigInteger aceptaria "+50688880000" y
        // guardaria 50688880000, o sea un numero que el usuario nunca escribio.
        if (!TELEFONO_ENTEROS.matcher(valor).matches()) {
            throw new IllegalArgumentException(
                    "El teléfono debe ser un número entero sin guiones, espacios ni el prefijo + (por ejemplo 88880000). Valor recibido: "
                            + telefono);
        }

        BigInteger numero = new BigInteger(valor);
        if (numero.compareTo(TELEFONO_MINIMO) < 0 || numero.compareTo(TELEFONO_MAXIMO) > 0) {
            throw new IllegalArgumentException(
                    "El teléfono debe estar entre 100 y 99999999999999999999 según el XSD de Hacienda. Valor recibido: "
                            + telefono);
        }

        entity.setTelefono(valor);
    }

    @Override
    @Transactional
    public void update(@Nonnull ConfiguracionAplicacion entity) {
        validarTelefono(entity);
        super.update(entity);
    }

    @Override
    @Transactional
    public void create(@Nonnull ConfiguracionAplicacion entity) {
        validarTelefono(entity);
        super.create(entity);
    }
         
    public void disable(@Nonnull ConfiguracionAplicacion entity) {
        try {
            if (!em.contains(entity)) {
                Object id = em.getEntityManagerFactory().getPersistenceUnitUtil().getIdentifier(entity);
                entity = em.find(getEntityClass(), id);
            }

            if (entity != null) {
                entity.setEstatus(false);
                em.merge(entity);
                em.flush();
                LOG.info("app settings disabled");
            } else {
                LOG.warn("app settings disable: entity not found");
            }
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.error("failed to disable app settings", e);
        }
    }
     
    public @Nullable ConfiguracionAplicacion returnCurrent() {
        try {
            TypedQuery<ConfiguracionAplicacion> query = em.createQuery("SELECT a FROM ConfiguracionAplicacion a WHERE a.estatus = true", getEntityClass());
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("failed to load current app settings", e);
            return null;
        }
    }

    /**
     * Returns the current active settings, or falls back to the most recent row
     * by Id if no row has estatus=true. Creates and returns a new settings row
     * only if the table is completely empty.
     */
    @Transactional
    public @Nonnull ConfiguracionAplicacion findOrCreateCurrent() {
        ConfiguracionAplicacion current = returnCurrent();
        if (current != null) {
            return current;
        }

        try {
            TypedQuery<ConfiguracionAplicacion> query = em.createQuery(
                "SELECT a FROM ConfiguracionAplicacion a ORDER BY a.Id DESC", getEntityClass());
            query.setMaxResults(1);
            current = query.getSingleResult();
            current.setEstatus(true);
            return em.merge(current);
        } catch (NoResultException e) {
            // table empty — fall through to create
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("failed to find fallback app settings", e);
        }

        current = new ConfiguracionAplicacion();
        current.setEstatus(true);
        em.persist(current);
        return current;
    }

    @Transactional
    public String getOrCreateAuthSessionKey() {
        ConfiguracionAplicacion s = findOrCreateCurrent();
        if (s.getAuthSessionKey() != null && !s.getAuthSessionKey().isEmpty() && s.getAuthSessionKey().length() >= 32) {
            return s.getAuthSessionKey();
        }
        String newKey = EncryptionUtil.generateKey();
        s.setAuthSessionKey(newKey);
        em.merge(s);
        LOG.info("generated new DB-managed authSessionKey");
        return newKey;
    }

    @Transactional
    public String getOrCreateHaciendaEncryptionKey() {
        ConfiguracionAplicacion s = findOrCreateCurrent();
        if (s.getHaciendaEncryptionKey() != null && !s.getHaciendaEncryptionKey().isEmpty() && s.getHaciendaEncryptionKey().length() >= 32) {
            return s.getHaciendaEncryptionKey();
        }
        String newKey = EncryptionUtil.generateKey();
        s.setHaciendaEncryptionKey(newKey);
        em.merge(s);
        LOG.info("generated new DB-managed haciendaEncryptionKey");
        return newKey;
    }

    @Transactional
    public boolean rotateAuthSessionKey() {
        ConfiguracionAplicacion s = returnCurrent();
        if (s == null) return false;
        String newKey = EncryptionUtil.generateKey();
        s.setAuthSessionKey(newKey);
        em.merge(s);
        LOG.warn("auth session key rotated, all existing sessions invalidated");
        return true;
    }

    @Transactional
    public boolean rotateHaciendaEncryptionKey() {
        // Rotation requires re-encrypting existing secrets — delegate to HaciendaCertificateService
        return false;
    }

    void onStart(@Observes StartupEvent ev) {
        try {
            getOrCreateAuthSessionKey();
            getOrCreateHaciendaEncryptionKey();
            LOG.info("DB-managed keys ensured on startup");
        } catch (Exception e) {
            LOG.error("failed to ensure DB session keys on startup", e);
        }
    }

}
