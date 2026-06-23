package Services;

import Models.Articulos.Articulos;
import Models.Lote;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Named
@ApplicationScoped
public class LoteService extends GService<Lote> {

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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing lots by article: " + e.getMessage(), null, 0, "LoteService.listPorArticulo()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing lots near expiry: " + e.getMessage(), null, 0, "LoteService.listProximosVencer()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing expired lots: " + e.getMessage(), null, 0, "LoteService.listVencidos()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error suggesting FEFO lot: " + e.getMessage(), null, 0, "LoteService.sugerirLoteFEFO()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting lots near expiry: " + e.getMessage(), null, 0, "LoteService.countProximosVencer()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting expired lots: " + e.getMessage(), null, 0, "LoteService.countVencidos()", null, e.getMessage());
            return 0L;
        }
    }
}
