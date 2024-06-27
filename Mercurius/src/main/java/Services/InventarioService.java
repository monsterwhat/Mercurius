package Services;

import Models.Inventario;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

@Named
public class InventarioService extends GService<Inventario> {

    @Override
    protected Class<Inventario> getEntityClass() {
        return Inventario.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Inventario entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            System.out.println("Error creating entity: " + e.toString());
        }
    }

    @Override
    public void delete(Inventario entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error deleting " + getEntityClass().getSimpleName() + " : " + e.toString());
        }
    }

    @Override
    public void update(Inventario entity) {
        try {
            em.merge(entity);
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }
    
    public void updateAndDisable(Inventario entity) {
        try {
            // Find the existing item by its ID
            Inventario existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Disable the existing item
                existingItem.setStatus(false);
                em.merge(existingItem);

                // Create a new item with the updated information
                em.persist(entity);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error updating entity: " + e.toString());
        }
    }

    @Override
    public List<Inventario> listAll() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }
    
    public List<Inventario> ListAllEnabled() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all enabled entities: " + e.toString());
            return null;
        }
    }

    public void softDelete(Inventario entity) {
        try {
            // Find the item by its ID
            Inventario existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            } else {
                System.out.println("Entity not found");
            }
        } catch (Exception e) {
            System.out.println("Error soft deleting entity: " + e.toString());
        }
    }

    public double calculateTotalStockForItemByBarcode(String barcode) {
        try {
            // Query to sum up the quantities of inventory movements for items with the given barcode
            String queryString = "SELECT SUM(i.cantidad) FROM Inventario i WHERE i.articulo.codigoBarra = :barcode AND i.articulo.status = true AND i.articulo.processed = true";
            BigDecimal result = em.createQuery(queryString, BigDecimal.class)
                             .setParameter("barcode", barcode)
                             .getSingleResult();
            
            if(result != null){
                return result.doubleValue();
            }else{
                return 0.0;
            }
        } catch (Exception e) {
            System.out.println("Error calculating total stock for item by barcode: " + e.toString());
            return 0.0;
        }
    }


    public List<Inventario> listAllSinProcesar() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = false", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }
    }

    public List<Inventario> listAllActivosYProcesados() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = true", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }    
    }

    public List<Inventario> listAllInactivos() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = false", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            System.out.println("Error listing all entities: " + e.toString());
            return null;
        }     
    }

    public List<Inventario> findByDateRangeAndUserId(Date startDate, Date endDate, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.between(inventario.get("fechaMovimiento"), startDate, endDate);
        Predicate userPredicate = cb.equal(inventario.get("usuario").get("id"), userId);

        cq.where(cb.and(datePredicate, userPredicate));

        return em.createQuery(cq).getResultList();
    }
    
    public Date getStartOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    public Date getEndOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    public List<Inventario> findByDateAndUserId(Date date, Long userId) {
        Date startOfDay = getStartOfDay(date);
        Date endOfDay = getEndOfDay(date);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.between(inventario.get("fechaMovimiento"), startOfDay, endOfDay);
        Predicate userPredicate = cb.equal(inventario.get("usuario").get("id"), userId);

        cq.where(cb.and(datePredicate, userPredicate));

    return em.createQuery(cq).getResultList();
    }

    public List<Inventario> findInventariosAfterDate(Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.greaterThan(inventario.get("fechaMovimiento"), fecha);
        cq.where(datePredicate);

        TypedQuery<Inventario> query = em.createQuery(cq);
        return query.getResultList();
    }


}
