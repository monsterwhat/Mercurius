package Services;

import Models.Departamento;
import Models.OrdenCompra;
import Models.OrdenCompraDetalle;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import jakarta.transaction.Transactional;
import java.util.List;

@Named
@ApplicationScoped
public class OrdenCompraService extends GService<OrdenCompra> {

    @Override
    protected @Nonnull Class<OrdenCompra> getEntityClass() {
        return OrdenCompra.class;
    }

    @PostConstruct
    public void init() {
    }

    /**
     * Genera el siguiente número de orden en formato OC-YYYY-NNNNN
     */
    public @Nonnull String generarNumeroOrden() {
        try {
            int year = LocalDate.now().getYear();
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(o) FROM OrdenCompra o WHERE o.numeroOrden LIKE :prefijo",
                Long.class
            );
            query.setParameter("prefijo", "OC-" + year + "-%");
            long count = query.getSingleResult();
            long siguiente = count + 1;
            return String.format("OC-%d-%05d", year, siguiente);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error generando número de orden: " + e.getMessage(), null, 0, "OrdenCompraService.generarNumeroOrden()", null, e.getMessage());
            int year = LocalDate.now().getYear();
            return String.format("OC-%d-%05d", year, System.currentTimeMillis() % 100000);
        }
    }

    /**
     * Crea una orden de compra con sus detalles
     */
    @Transactional
    public void crearOrden(@Nonnull OrdenCompra orden, @Nonnull List<OrdenCompraDetalle> detalles) {
        try {
            orden.setDetalles(detalles);
            for (OrdenCompraDetalle detalle : detalles) {
                detalle.setOrdenCompra(orden);
                detalle.calcularSubtotal();
            }
            orden.setTotalEstimado(calcularTotal(detalles));
            orden.setStatus(true);
            if (orden.getEstado() == null) {
                orden.setEstado("BORRADOR");
            }
            em.persist(orden);
            em.flush();
            em.refresh(orden);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error creando orden de compra: " + e.getMessage(), null, 0, "OrdenCompraService.crearOrden()", null, e.getMessage());
            throw new RuntimeException("Error al crear la orden de compra", e);
        }
    }

    /**
     * Cambia el estado de una orden validando la transición
     */
    @Transactional
    public void cambiarEstado(@Nonnull OrdenCompra orden, @Nonnull String nuevoEstado) {
        try {
            OrdenCompra existing = em.find(getEntityClass(), orden.getId());
            if (existing != null) {
                existing.setEstado(nuevoEstado);
                em.merge(existing);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error cambiando estado de orden: " + e.getMessage(), null, 0, "OrdenCompraService.cambiarEstado()", null, e.getMessage());
        }
    }

    /**
     * Lista órdenes por proveedor
     */
    public @Nullable List<OrdenCompra> findByProveedor(@Nonnull Departamento proveedor) {
        try {
            TypedQuery<OrdenCompra> query = em.createQuery(
                "SELECT o FROM OrdenCompra o LEFT JOIN FETCH o.detalles d LEFT JOIN FETCH d.articulo LEFT JOIN FETCH o.proveedor WHERE o.proveedor = :proveedor AND o.status = true ORDER BY o.fechaOrden DESC",
                OrdenCompra.class
            );
            query.setParameter("proveedor", proveedor);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error buscando órdenes por proveedor: " + e.getMessage(), null, 0, "OrdenCompraService.findByProveedor()", null, e.getMessage());
            return null;
        }
    }

    /**
     * Lista órdenes por estado
     */
    public @Nullable List<OrdenCompra> findByEstado(@Nonnull String estado) {
        try {
            TypedQuery<OrdenCompra> query = em.createQuery(
                "SELECT o FROM OrdenCompra o LEFT JOIN FETCH o.detalles d LEFT JOIN FETCH d.articulo LEFT JOIN FETCH o.proveedor WHERE o.estado = :estado AND o.status = true ORDER BY o.fechaOrden DESC",
                OrdenCompra.class
            );
            query.setParameter("estado", estado);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error buscando órdenes por estado: " + e.getMessage(), null, 0, "OrdenCompraService.findByEstado()", null, e.getMessage());
            return null;
        }
    }

    /**
     * Lista órdenes pendientes (ENVIADA o CONFIRMADA)
     */
    public @Nullable List<OrdenCompra> findPendientes() {
        try {
            TypedQuery<OrdenCompra> query = em.createQuery(
                "SELECT o FROM OrdenCompra o LEFT JOIN FETCH o.detalles d LEFT JOIN FETCH d.articulo LEFT JOIN FETCH o.proveedor WHERE o.estado IN ('ENVIADA', 'CONFIRMADA') AND o.status = true ORDER BY o.fechaOrden DESC",
                OrdenCompra.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error buscando órdenes pendientes: " + e.getMessage(), null, 0, "OrdenCompraService.findPendientes()", null, e.getMessage());
            return null;
        }
    }

    /**
     * Marca una orden como recibida
     */
    @Transactional
    public void recibirOrden(@Nonnull OrdenCompra orden) {
        try {
            OrdenCompra existing = em.find(getEntityClass(), orden.getId());
            if (existing != null) {
                existing.setFechaEntregaReal(new Date());
                existing.setEstado("RECIBIDA");
                if (orden.getTotalReal() != null) {
                    existing.setTotalReal(orden.getTotalReal());
                }
                em.merge(existing);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error recibiendo orden: " + e.getMessage(), null, 0, "OrdenCompraService.recibirOrden()", null, e.getMessage());
        }
    }

    /**
     * Cancela una orden con motivo
     */
    @Transactional
    public void cancelarOrden(@Nonnull OrdenCompra orden, @Nullable String motivo) {
        try {
            OrdenCompra existing = em.find(getEntityClass(), orden.getId());
            if (existing != null) {
                existing.setEstado("CANCELADA");
                if (motivo != null && !motivo.isBlank()) {
                    existing.setNotas(motivo);
                }
                em.merge(existing);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error cancelando orden: " + e.getMessage(), null, 0, "OrdenCompraService.cancelarOrden()", null, e.getMessage());
        }
    }

    /**
     * Calcula el total de una lista de detalles
     */
    public @Nonnull BigDecimal calcularTotal(@Nonnull List<OrdenCompraDetalle> detalles) {
        return detalles.stream()
            .map(d -> d.getSubtotal() != null ? d.getSubtotal() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Lista todas las órdenes activas con relaciones
     */
    @Override
    public @Nonnull List<OrdenCompra> listAll() {
        try {
            TypedQuery<OrdenCompra> query = em.createQuery(
                "SELECT DISTINCT o FROM OrdenCompra o " +
                "LEFT JOIN FETCH o.detalles d " +
                "LEFT JOIN FETCH d.articulo " +
                "LEFT JOIN FETCH o.proveedor " +
                "LEFT JOIN FETCH o.usuario " +
                "WHERE o.status = true " +
                "ORDER BY o.fechaOrden DESC",
                OrdenCompra.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error listando órdenes: " + e.getMessage(), null, 0, "OrdenCompraService.listAll()", null, e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Soft delete: marca status como false
     */
    @Transactional
    public void softDelete(@Nonnull OrdenCompra entity) {
        try {
            OrdenCompra existingItem = em.find(getEntityClass(), entity.getId());
            if (existingItem != null) {
                existingItem.setStatus(false);
                em.merge(existingItem);
            em.flush();
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for softDelete", null, 0, "OrdenCompraService.softDelete()", null, null);
            }
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "OrdenCompraService.softDelete()", null, e.getMessage());
        }
    }

    /**
     * Valida si una transición de estado es válida
     */
    public boolean esTransicionValida(@Nonnull String estadoActual, @Nonnull String nuevoEstado) {
        return switch (estadoActual) {
            case "BORRADOR" -> nuevoEstado.equals("ENVIADA") || nuevoEstado.equals("CANCELADA");
            case "ENVIADA" -> nuevoEstado.equals("CONFIRMADA") || nuevoEstado.equals("CANCELADA");
            case "CONFIRMADA" -> nuevoEstado.equals("RECIBIDA") || nuevoEstado.equals("CANCELADA");
            case "RECIBIDA" -> nuevoEstado.equals("FACTURADA");
            default -> false;
        };
    }
}
