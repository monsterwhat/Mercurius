package Services;

import Models.Articulos.Articulos;
import Models.Lote;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Named
@ApplicationScoped
public class LoteService extends GService<Lote> {

    private static final Logger LOG = Logger.getLogger(LoteService.class);

    @Override
    protected Class<Lote> getEntityClass() {
        return Lote.class;
    }

    @PostConstruct
    public void init() {
    }

    public List<Lote> listPorArticulo(Articulos articulo) {
        try {
            TypedQuery<Lote> query = em.createQuery(
                "SELECT l FROM Lote l WHERE l.articulo = :articulo AND l.status = true ORDER BY l.fechaVencimiento ASC",
                Lote.class
            );
            query.setParameter("articulo", articulo);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.warn("Error listing lots by article: " + e.getMessage() + " | source=" + "LoteService.listPorArticulo()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public List<Lote> listProximosVencer(int dias) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, dias);
            Date fechaLimite = cal.getTime();

            TypedQuery<Lote> query = em.createQuery(
                "SELECT l FROM Lote l WHERE l.status = true AND l.cantidadActual > 0 AND l.fechaVencimiento <= :fechaLimite ORDER BY l.fechaVencimiento ASC",
                Lote.class
            );
            query.setParameter("fechaLimite", fechaLimite);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.warn("Error listing lots near expiry: " + e.getMessage() + " | source=" + "LoteService.listProximosVencer()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public List<Lote> listVencidos() {
        try {
            TypedQuery<Lote> query = em.createQuery(
                "SELECT l FROM Lote l WHERE l.status = true AND l.cantidadActual > 0 AND l.fechaVencimiento < CURRENT_DATE ORDER BY l.fechaVencimiento ASC",
                Lote.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.warn("Error listing expired lots: " + e.getMessage() + " | source=" + "LoteService.listVencidos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public Lote sugerirLoteFEFO(Articulos articulo) {
        try {
            TypedQuery<Lote> query = em.createQuery(
                "SELECT l FROM Lote l WHERE l.articulo = :articulo AND l.status = true AND l.cantidadActual > 0 AND l.fechaVencimiento >= CURRENT_DATE ORDER BY l.fechaVencimiento ASC",
                Lote.class
            );
            query.setParameter("articulo", articulo);
            query.setMaxResults(1);
            List<Lote> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
                        LOG.warn("Error suggesting FEFO lot: " + e.getMessage() + " | source=" + "LoteService.sugerirLoteFEFO()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public long countProximosVencer(int dias) {
        try {
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, dias);
            Date fechaLimite = cal.getTime();

            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(l) FROM Lote l WHERE l.status = true AND l.cantidadActual > 0 AND l.fechaVencimiento <= :fechaLimite",
                Long.class
            );
            query.setParameter("fechaLimite", fechaLimite);
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.warn("Error counting lots near expiry: " + e.getMessage() + " | source=" + "LoteService.countProximosVencer()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return 0L;
        }
    }

    public long countVencidos() {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(l) FROM Lote l WHERE l.status = true AND l.cantidadActual > 0 AND l.fechaVencimiento < CURRENT_DATE",
                Long.class
            );
            return query.getSingleResult();
        } catch (PersistenceException e) {
                        LOG.warn("Error counting expired lots: " + e.getMessage() + " | source=" + "LoteService.countVencidos()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return 0L;
        }
    }
}
