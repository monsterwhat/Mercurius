package Services;

import Models.NotaCredito;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
public class NotaCreditoService extends GService<NotaCredito> {

    private static final Logger LOG = Logger.getLogger(NotaCreditoService.class);

    @Override
    protected Class<NotaCredito> getEntityClass() {
        return NotaCredito.class;
    }

    @PostConstruct
    public void init() {
    }

    public List<NotaCredito> listPorComprobante(Long comprobanteId) {
        try {
            TypedQuery<NotaCredito> query = em.createQuery(
                "SELECT n FROM NotaCredito n WHERE n.comprobanteOriginal.id = :comprobanteId ORDER BY n.fecha DESC",
                NotaCredito.class
            );
            query.setParameter("comprobanteId", comprobanteId);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error listing credit notes: " + e.getMessage() + " | source=" + "NotaCreditoService.listPorComprobante()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Busca la fila {@code NotaCredito} de una NC electronica por su clave.
     * Necesario cuando una factura tiene varias devoluciones parciales: el
     * estado de Hacienda debe actualizarse solo en la nota que se envio, no
     * en todas las notas de la factura.
     */
    public @Nullable NotaCredito findByHaciendaClave(@Nullable String clave) {
        if (clave == null || clave.isBlank()) {
            return null;
        }
        try {
            TypedQuery<NotaCredito> query = em.createQuery(
                "SELECT n FROM NotaCredito n WHERE n.haciendaClave = :clave",
                NotaCredito.class
            );
            query.setParameter("clave", clave);
            List<NotaCredito> encontradas = query.getResultList();
            return encontradas.isEmpty() ? null : encontradas.get(0);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error finding credit note by clave: " + e.getMessage()
                    + " | source=NotaCreditoService.findByHaciendaClave() | clave=" + clave);
            return null;
        }
    }

    public List<NotaCredito> listAll() {
        try {
            TypedQuery<NotaCredito> query = em.createQuery(
                "SELECT n FROM NotaCredito n ORDER BY n.fecha DESC",
                NotaCredito.class
            );
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error listing all credit notes: " + e.getMessage() + " | source=" + "NotaCreditoService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    /**
     * Las notas de credito mas recientes, acotadas en SQL.
     *
     * <p>La usa el refresco OOB del historial de devoluciones, que corre
     * despues de CADA devolucion confirmada: el historial no tiene techo, asi
     * que materializarlo entero para pintar la primera pantalla crece sin
     * limite. Mismo criterio de orden que {@link #listAll()} (fecha
     * descendente), con {@code id} como desempate para que dos notas del mismo
     * instante no cambien de sitio entre consultas.</p>
     *
     * <p>El {@code JOIN FETCH} de comprobante original y encabezado evita que
     * el fragmento HTML pague dos consultas lazy por fila (y que las toque ya
     * fuera de la transaccion de la devolucion).</p>
     *
     * @param limit maximo de filas devueltas (se fuerza a {@code >= 1})
     * @return las notas mas recientes; lista vacia si la consulta falla
     */
    public @Nonnull List<NotaCredito> listRecent(int limit) {
        try {
            TypedQuery<NotaCredito> query = em.createQuery(
                "SELECT n FROM NotaCredito n" +
                " LEFT JOIN FETCH n.comprobanteOriginal co LEFT JOIN FETCH co.encabezado" +
                " ORDER BY n.fecha DESC, n.id DESC",
                NotaCredito.class
            );
            query.setMaxResults(Math.max(limit, 1));
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error listing recent credit notes: " + e.getMessage()
                    + " | source=NotaCreditoService.listRecent() | despues=" + e.getMessage());
            return List.of();
        }
    }
}
