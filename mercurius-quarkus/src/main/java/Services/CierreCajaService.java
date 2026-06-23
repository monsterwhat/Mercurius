package Services;

import Models.CierreCaja;
import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import java.util.Date;
import java.util.List;

@Named
@ApplicationScoped
public class CierreCajaService extends GService<CierreCaja> {

    @Override
    protected Class<CierreCaja> getEntityClass() {
        return CierreCaja.class;
    }

    @PostConstruct
    public void init() {
    }

    public CierreCaja findSesionAbierta(Users usuario) {
        try {
            TypedQuery<CierreCaja> query = em.createQuery(
                "SELECT c FROM CierreCaja c WHERE c.usuario = :usuario AND c.estado = 'abierto'",
                CierreCaja.class
            );
            query.setParameter("usuario", usuario);
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding open session: " + e.getMessage(), usuario, 0, "CierreCajaService.findSesionAbierta()", null, e.getMessage());
            return null;
        }
    }

    public List<CierreCaja> listHistorial(Users usuario) {
        try {
            TypedQuery<CierreCaja> query = em.createQuery(
                "SELECT c FROM CierreCaja c WHERE c.usuario = :usuario ORDER BY c.fechaApertura DESC",
                CierreCaja.class
            );
            query.setParameter("usuario", usuario);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing session history: " + e.getMessage(), usuario, 0, "CierreCajaService.listHistorial()", null, e.getMessage());
            return null;
        }
    }

    public List<CierreCaja> listHistorialPorFecha(Users usuario, Date desde, Date hasta) {
        try {
            TypedQuery<CierreCaja> query = em.createQuery(
                "SELECT c FROM CierreCaja c WHERE c.usuario = :usuario AND c.fechaApertura BETWEEN :desde AND :hasta ORDER BY c.fechaApertura DESC",
                CierreCaja.class
            );
            query.setParameter("usuario", usuario);
            query.setParameter("desde", desde);
            query.setParameter("hasta", hasta);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing session history by date: " + e.getMessage(), usuario, 0, "CierreCajaService.listHistorialPorFecha()", null, e.getMessage());
            return null;
        }
    }
}
