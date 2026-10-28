package Services;

import Models.Departamento;
import Models.OrdenCompra;
import Models.OrdenCompraDetalle;
import org.jboss.logging.Logger;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.List;
import java.util.Map;

@Named
@ApplicationScoped
public class OrdenCompraService extends GService<OrdenCompra> {

    private static final Logger LOG = Logger.getLogger(OrdenCompraService.class);

    /** Escape character of the LIKE patterns built by {@link #likePattern(String)}. */
    private static final char LIKE_ESCAPE = '!';

    /** {@code ESCAPE 'x'} clause appended to every LIKE predicate (see above). */
    private static final String ESCAPE_LIKE = " ESCAPE '" + LIKE_ESCAPE + "'";

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
                        LOG.warn("Error generando número de orden: " + e.getMessage() + " | source=" + "OrdenCompraService.generarNumeroOrden()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error creando orden de compra: " + e.getMessage() + " | source=" + "OrdenCompraService.crearOrden()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error cambiando estado de orden: " + e.getMessage() + " | source=" + "OrdenCompraService.cambiarEstado()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error buscando órdenes por proveedor: " + e.getMessage() + " | source=" + "OrdenCompraService.findByProveedor()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error buscando órdenes por estado: " + e.getMessage() + " | source=" + "OrdenCompraService.findByEstado()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error buscando órdenes pendientes: " + e.getMessage() + " | source=" + "OrdenCompraService.findPendientes()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error recibiendo orden: " + e.getMessage() + " | source=" + "OrdenCompraService.recibirOrden()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error cancelando orden: " + e.getMessage() + " | source=" + "OrdenCompraService.cancelarOrden()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
                        LOG.warn("Error listando órdenes: " + e.getMessage() + " | source=" + "OrdenCompraService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Paged read of the active order book — the SQL replacement for the
     * {@code listAll().stream().filter(...).sorted(...).subList(from, to)} that
     * {@code OrdenCompraResource} used to run for both the JSON list and the
     * HTML data table (10^4-10^5 rows materialised per request).
     *
     * <p><b>Two-step query, deliberately.</b> Step 1 selects only the ids of the
     * requested page ({@code SELECT o.id ... setFirstResult/setMaxResults});
     * step 2 hydrates exactly those ids with the same JOIN FETCHes as
     * {@link #listAll()}. {@code setMaxResults} must never go on the
     * collection-fetch query: Hibernate translates it into a SQL LIMIT over the
     * JOINed rows, so a page of 20 would be filled by the details of the first
     * order (and an order with more lines than the page size would come back
     * with a truncated {@code detalles} collection). Here the LIMIT only ever
     * sees one row per order.</p>
     *
     * <p>Filter and ordering semantics are the ones the resource applied in
     * memory: {@code status = true} always; {@code q} is a case-insensitive
     * substring over numeroOrden / proveedor.nombre / estado / notas /
     * usuario.username; {@code estado} exact; {@code proveedorId} exact;
     * {@code numeroOrden} case-insensitive substring. Null/blank/unknown
     * filters are ignored. With no usable {@code sort} the order is
     * {@code fechaOrden DESC} (the {@link #listAll()} order); a whitelisted key
     * sorts in {@code dir} direction, with {@code fechaOrden DESC} underneath
     * (the order the old stable {@code List.sort()} started from) and
     * {@code id} as the final deterministic tiebreaker. Text keys sort on
     * {@code LOWER(...)} because the old comparators were case-insensitive, and
     * PostgreSQL's default NULL placement matches the old
     * {@code nullsLast(...)} / {@code .reversed()} pair (NULLs last ascending,
     * NULLs first descending).</p>
     *
     * @param q           global text filter (legacy globalFilterFunction), may be null
     * @param estado      exact estado filter, may be null
     * @param proveedorId exact proveedor id filter, may be null
     * @param numeroOrden numeroOrden substring filter, may be null
     * @param sort        numeroOrden|proveedor|fechaOrden|entrega|estado|total, or null/unknown for the default order
     * @param dir         {@code desc} for descending, anything else ascending
     * @param offset      0-based index of the first row (negative is clamped to 0)
     * @param size        page size (values below 1 are clamped to 1)
     * @return the page rows with collections/associations fully fetched, in page
     *         order; empty when the page is past the end
     * @throws PersistenceException if the query fails — a failed read must not
     *         render as an empty order book (see {@link GService#listAll()})
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<OrdenCompra> listPage(@Nullable String q, @Nullable String estado,
                                               @Nullable Integer proveedorId,
                                               @Nullable String numeroOrden,
                                               @Nullable String sort, @Nullable String dir,
                                               int offset, int size) {
        FiltroOrden filtro = FiltroOrden.of(q, estado, proveedorId, numeroOrden);
        try {
            // Step 1: the ids of the page. No collection joins here, so LIMIT
            // counts orders, never joined rows.
            TypedQuery<Long> idsQuery = em.createQuery(
                    "SELECT o.id FROM OrdenCompra o"
                            + " LEFT JOIN o.proveedor p"
                            + " LEFT JOIN o.usuario u"
                            + where(filtro)
                            + orderBy(sort, dir),
                    Long.class);
            bind(idsQuery, filtro);
            List<Long> ids = idsQuery
                    .setFirstResult(Math.max(offset, 0))
                    .setMaxResults(Math.max(size, 1))
                    .getResultList();
            if (ids.isEmpty()) {
                return List.of();
            }

            // Step 2: hydrate exactly that page, collections included.
            TypedQuery<OrdenCompra> fetch = em.createQuery(
                    "SELECT DISTINCT o FROM OrdenCompra o"
                            + " LEFT JOIN FETCH o.detalles d"
                            + " LEFT JOIN FETCH d.articulo"
                            + " LEFT JOIN FETCH o.proveedor"
                            + " LEFT JOIN FETCH o.usuario"
                            + " WHERE o.id IN :ids",
                    OrdenCompra.class);
            fetch.setParameter("ids", ids);

            Map<Long, OrdenCompra> porId = new HashMap<>(ids.size() * 2);
            for (OrdenCompra orden : fetch.getResultList()) {
                porId.put(orden.getId(), orden);
            }
            // The id query owns the page order; the fetch query cannot preserve
            // it (DISTINCT + collection join reorders rows in SQL).
            List<OrdenCompra> pagina = new ArrayList<>(ids.size());
            for (Long id : ids) {
                OrdenCompra orden = porId.get(id);
                if (orden != null) {
                    pagina.add(orden);
                }
            }
            return pagina;
        } catch (PersistenceException e) {
            LOG.warn("Error listando la página de órdenes: " + e.getMessage()
                    + " | source=" + "OrdenCompraService.listPage()"
                    + " | offset=" + offset + " | size=" + size
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            throw e;
        }
    }

    /**
     * Total rows matched by the same predicate as {@link #listPage}, for the
     * pager and the JSON envelope's {@code total}. Runs as a SQL aggregate over
     * the root entity only — no entity is hydrated.
     *
     * @param q           global text filter, may be null
     * @param estado      exact estado filter, may be null
     * @param proveedorId exact proveedor id filter, may be null
     * @param numeroOrden numeroOrden substring filter, may be null
     * @return number of matching active orders
     * @throws PersistenceException if the query fails (a count that silently
     *         read 0 would render the whole order book as empty)
     */
    @Transactional(TxType.SUPPORTS)
    public long countPage(@Nullable String q, @Nullable String estado,
                          @Nullable Integer proveedorId, @Nullable String numeroOrden) {
        FiltroOrden filtro = FiltroOrden.of(q, estado, proveedorId, numeroOrden);
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(o) FROM OrdenCompra o"
                            + " LEFT JOIN o.proveedor p"
                            + " LEFT JOIN o.usuario u"
                            + where(filtro),
                    Long.class);
            bind(query, filtro);
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (PersistenceException e) {
            LOG.warn("Error contando órdenes: " + e.getMessage()
                    + " | source=" + "OrdenCompraService.countPage()"
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            throw e;
        }
    }

    /**
     * Active orders per estado in ONE {@code GROUP BY} query — replaces the five
     * in-memory stream passes over {@code listAll()} that fed the page-header
     * counters.
     *
     * @return estado → row count; estados with no rows are absent (render as 0)
     *         and a failed query returns an empty map, which only zeroes the
     *         decorative counters — the page still renders
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull Map<String, Long> contarPorEstado() {
        try {
            List<Object[]> filas = em.createQuery(
                    "SELECT o.estado, COUNT(o) FROM OrdenCompra o"
                            + " WHERE o.status = true"
                            + " GROUP BY o.estado",
                    Object[].class).getResultList();
            Map<String, Long> conteo = new HashMap<>(filas.size() * 2);
            for (Object[] fila : filas) {
                if (fila[0] != null) {
                    conteo.put((String) fila[0], ((Number) fila[1]).longValue());
                }
            }
            return conteo;
        } catch (PersistenceException e) {
            LOG.warn("Error contando órdenes por estado: " + e.getMessage()
                    + " | source=" + "OrdenCompraService.contarPorEstado()"
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            return Map.of();
        }
    }

    /**
     * Normalized filter set shared by {@link #listPage} and {@link #countPage},
     * so the WHERE fragment and its bound parameters cannot drift apart (a count
     * that disagreed with the page would break the pager).
     */
    private record FiltroOrden(@Nullable String q, @Nullable String numeroOrden,
                               @Nullable String estado, @Nullable Integer proveedorId) {

        static FiltroOrden of(@Nullable String q, @Nullable String estado,
                              @Nullable Integer proveedorId, @Nullable String numeroOrden) {
            return new FiltroOrden(likePattern(q), likePattern(numeroOrden),
                    trimToNull(estado), proveedorId);
        }
    }

    /** WHERE fragment shared by the id query and the count query (must stay identical). */
    private static String where(FiltroOrden filtro) {
        StringBuilder jpql = new StringBuilder(" WHERE o.status = true");
        if (filtro.q() != null) {
            jpql.append(" AND (LOWER(o.numeroOrden) LIKE LOWER(:q)").append(ESCAPE_LIKE)
                    .append(" OR LOWER(p.nombre) LIKE LOWER(:q)").append(ESCAPE_LIKE)
                    .append(" OR LOWER(o.estado) LIKE LOWER(:q)").append(ESCAPE_LIKE)
                    .append(" OR LOWER(o.notas) LIKE LOWER(:q)").append(ESCAPE_LIKE)
                    .append(" OR LOWER(u.username) LIKE LOWER(:q)").append(ESCAPE_LIKE)
                    .append(')');
        }
        if (filtro.estado() != null) {
            jpql.append(" AND o.estado = :estado");
        }
        if (filtro.proveedorId() != null) {
            jpql.append(" AND p.id = :proveedorId");
        }
        if (filtro.numeroOrden() != null) {
            jpql.append(" AND LOWER(o.numeroOrden) LIKE LOWER(:numeroOrden)").append(ESCAPE_LIKE);
        }
        return jpql.toString();
    }

    /** Binds exactly the parameters {@link #where(FiltroOrden)} emitted. */
    private static void bind(TypedQuery<?> query, FiltroOrden filtro) {
        if (filtro.q() != null) {
            query.setParameter("q", filtro.q());
        }
        if (filtro.estado() != null) {
            query.setParameter("estado", filtro.estado());
        }
        if (filtro.proveedorId() != null) {
            query.setParameter("proveedorId", filtro.proveedorId());
        }
        if (filtro.numeroOrden() != null) {
            query.setParameter("numeroOrden", filtro.numeroOrden());
        }
    }

    /**
     * ORDER BY of the id query. Only whitelisted keys are interpolated — the
     * request's {@code sort} value never reaches the JPQL.
     *
     * <p>Default (no key, or an unknown one) is the {@link #listAll()} order,
     * {@code fechaOrden DESC}; {@code id DESC} makes it total so identical
     * timestamps cannot duplicate or drop rows across pages. With an explicit
     * key, {@code fechaOrden DESC} is the tiebreaker the old stable
     * {@code List.sort()} produced.</p>
     */
    private static String orderBy(@Nullable String sort, @Nullable String dir) {
        String direccion = "desc".equalsIgnoreCase(dir) ? " DESC" : " ASC";
        String expresion = sort == null ? null : switch (sort) {
            case "numeroOrden" -> "LOWER(o.numeroOrden)";
            case "proveedor" -> "LOWER(p.nombre)";
            case "fechaOrden" -> "o.fechaOrden";
            case "entrega" -> "o.fechaEntregaEstimada";
            case "estado" -> "LOWER(o.estado)";
            case "total" -> "o.totalEstimado";
            default -> null;
        };
        if (expresion == null) {
            return " ORDER BY o.fechaOrden DESC, o.id DESC";
        }
        String tiebreak = "o.fechaOrden".equals(expresion)
                ? ", o.id DESC"
                : ", o.fechaOrden DESC, o.id DESC";
        return " ORDER BY " + expresion + direccion + tiebreak;
    }

    /**
     * Builds the {@code %term%} LIKE pattern, escaping the metacharacters so a
     * user typing {@code %} or {@code _} searches for them literally (the old
     * Java filter used {@code contains}, not a pattern). Null/blank means "no
     * filter".
     */
    private static @Nullable String likePattern(@Nullable String term) {
        String texto = trimToNull(term);
        if (texto == null) {
            return null;
        }
        StringBuilder pattern = new StringBuilder(texto.length() + 2);
        pattern.append('%');
        for (int i = 0; i < texto.length(); i++) {
            char ch = texto.charAt(i);
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(ch);
        }
        pattern.append('%');
        return pattern.toString();
    }

    /** Trims and maps blank to null (filter "not requested"). */
    private static @Nullable String trimToNull(@Nullable String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        return limpio.isEmpty() ? null : limpio;
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
                                LOG.info("Entity not found for softDelete" + " | source=" + "OrdenCompraService.softDelete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (PersistenceException e) {
                        LOG.warn("Error soft deleting entity: " + e.getMessage() + " | source=" + "OrdenCompraService.softDelete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
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
