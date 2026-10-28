package Services;

import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import Models.Validacion.PrevalidationResult;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException; 
import jakarta.persistence.PersistenceException; 
import jakarta.transaction.Transactional;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root; 
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class ComprobantesRecibidosService extends GService<ComprobantesRecibidos> {
    
    private static final Logger LOG = Logger.getLogger(ComprobantesRecibidosService.class);

    @Inject @Nonnull ComprobantesRecibidosPrevalidationService prevalidationService;

    @Override
    protected Class<ComprobantesRecibidos> getEntityClass() {
        return ComprobantesRecibidos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(ComprobantesRecibidos entity) {
        try {
            em.persist(entity);
            em.flush();
            em.refresh(entity);
            LOG.info("Successfully created ComprobantesRecibidos with ID: " + entity.getId() + " | source=ComprobantesRecibidosService.create()");
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.create() | despues=" + e.getMessage());
            throw new RuntimeException("Failed to create ComprobantesRecibidos", e);
        }
    }
    
    // Method to create ComprobantesRecibidos with pre-persisted related entities
    // Uses proper cascading to ensure atomic transaction - if any entity fails, all rollback
    @Transactional
    @Nonnull
    public PrevalidationResult createWithRelatedEntities(@Nonnull ComprobantesRecibidos entity, @Nonnull Encabezado encabezado, @Nonnull ResumenFactura resumenFactura) {
        PrevalidationResult prevalidation = null;
        try {
            entity.setEncabezado(encabezado);
            entity.setResumen(resumenFactura);

            // Pre-validate — record errors but ALWAYS persist
            prevalidation = prevalidationService.prevalidarCompleto(entity);
            if (prevalidation.hasErrors() || prevalidation.hasWarnings()) {
                List<Models.Validacion.ValidationError> allIssues = prevalidation.getAllIssues();
                String errorSummary = allIssues.stream()
                    .map(e -> e.getField() + ": " + e.getMessage())
                    .collect(java.util.stream.Collectors.joining("; "));
                entity.setPrevalidationErrors(errorSummary);
                LOG.warn("Pre-validation warnings for " + (entity.getEncabezado() != null ? entity.getEncabezado().getNumeroConsecutivo() : "?") +
                    ": " + errorSummary
                    + " | source=ComprobantesRecibidosService.createWithRelatedEntities()"
                    + " | despues=" + errorSummary);
            }

            // The Emisor/Receptor arrive DETACHED here: Parser.parseXML() persisted
            // them via EmisorService.createIfNotExist / ReceptorService.createIfNotExist
            // inside their own committed transactions, so persist() rejects them
            // ("Detached entity passed to persist" — bug seen in prod logs, 238/245
            // recibidos silently lost). merge() re-attaches the whole graph
            // idempotently: each transient child (Encabezado, Resumen, Detalles,
            // LineaDetalle) is inserted and each detached child with an ID is
            // matched by ID and updated with identical data. This is the same
            // convention create()/update()/toggle() in this service already use.
            ComprobantesRecibidos managed = em.merge(entity);
            em.flush();
            em.refresh(managed);

            String consecutive = managed.getEncabezado() != null ? managed.getEncabezado().getNumeroConsecutivo() : String.valueOf(managed.getId());
            LOG.info("Successfully created ComprobantesRecibidos: " + consecutive + " | source=ComprobantesRecibidosService.createWithRelatedEntities()");
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity with related entities: " + e.getMessage() + " | source=ComprobantesRecibidosService.createWithRelatedEntities() | despues=" + e.getMessage());
            throw new RuntimeException("No se pudo guardar el comprobante recibido: " + e.getMessage(), e);
        }
        return prevalidation;
    }

    @Override
    @Transactional
    public void delete(ComprobantesRecibidos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                LOG.info("Entity not found for delete | source=ComprobantesRecibidosService.delete()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=ComprobantesRecibidosService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(ComprobantesRecibidos entity) {
        try {
            ComprobantesRecibidos merged = em.merge(entity);
            em.flush();
            em.refresh(merged);
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.update() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void softDelete(@Nonnull ComprobantesRecibidos entity) {
        try {
            // Find the item by its ID
            ComprobantesRecibidos existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            em.flush();
            } else {
                LOG.info("Entity not found for softDelete | source=ComprobantesRecibidosService.softDelete()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error soft deleting entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.softDelete() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void toggle(@Nonnull ComprobantesRecibidos entity){
        try {
            // Find the item by its ID
            ComprobantesRecibidos existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                //Toggle the item from state
                if(existingItem.getStatus()){
                    existingItem.setStatus(false);
                }else{
                    existingItem.setStatus(true);
                }
                em.merge(existingItem);
            em.flush();
            } else {
                LOG.info("Entity not found for toggle | source=ComprobantesRecibidosService.toggle()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error toggling entity: " + e.getMessage() + " | source=ComprobantesRecibidosService.toggle() | despues=" + e.getMessage());
        }
    }
    
    @Override @Nonnull
    public List<ComprobantesRecibidos> listAll() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=ComprobantesRecibidosService.listAll() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }
    
    @Nullable
    public List<ComprobantesRecibidos> ListAllEnabled() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.status = true",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all enabled entities: " + e.getMessage() + " | source=ComprobantesRecibidosService.ListAllEnabled() | despues=" + e.getMessage());
            return null;
        }
    }

    public boolean findByNumeroConsecutivo(@Nonnull String numeroConsecutivo) {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery("SELECT f FROM ComprobantesRecibidos f WHERE f.encabezado.numeroConsecutivo = :numeroConsecutivo", ComprobantesRecibidos.class);
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            // Attempt to get a single result
            ComprobantesRecibidos factura = query.getSingleResult();
            // If a result is found, return true
            return factura != null;
        } catch (NoResultException e) {
            // If no result is found, catch the NoResultException and return false
            return false;
        } catch (PersistenceException e) {
            LOG.warn("Error finding entity by numeroConsecutivo: " + e.getMessage() + " | source=ComprobantesRecibidosService.findByNumeroConsecutivo() | despues=" + e.getMessage());
            return false;
        }
}
    
    @Transactional
    @Nullable
    public ComprobantesRecibidos findByIdWithDetails(@Nonnull Long id) {
        try {
            ComprobantesRecibidos entity = em.find(ComprobantesRecibidos.class, id);
            if (entity == null) {
                return null;
            }
            
            // Initialize the detalles collection if it exists
            if (entity.getDetalles() != null) {
                em.refresh(entity.getDetalles());
                
                // Initialize lineasDetalle collection separately
                if (entity.getDetalles().getLineasDetalle() != null) {
                    entity.getDetalles().getLineasDetalle().size(); // Force initialization
                    
                    // Initialize nested collections for each line
                    for (LineaDetalle linea : entity.getDetalles().getLineasDetalle()) {
                        if (linea.getCodigosComerciales() != null) {
                            linea.getCodigosComerciales().size();
                        }
                        if (linea.getDescuentos() != null) {
                            linea.getDescuentos().size();
                        }
                        if (linea.getImpuestos() != null) {
                            linea.getImpuestos().size();
                        }
                    }
                }
            }
            
            // Initialize other relationships
            if (entity.getEncabezado() != null) {
                em.refresh(entity.getEncabezado());
            }
            if (entity.getResumen() != null) {
                em.refresh(entity.getResumen());
            }
            if (entity.getInformacionReferencia() != null) {
                entity.getInformacionReferencia().size();
            }
            
            return entity;
        } catch (NoResultException e) {
            return null;
        } catch (PersistenceException e) {
            LOG.warn("Error finding ComprobantesRecibidos by ID with details: " + e.getMessage() + " | source=ComprobantesRecibidosService.findByIdWithDetails() | despues=" + e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public List<ComprobantesRecibidos> listByDateRange(@Nonnull Date start, @Nonnull Date end) {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesRecibidos.class
            );
            query.setParameter("start", start.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            query.setParameter("end", end.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing recibidos by date range: " + e.getMessage() + " | source=ComprobantesRecibidosService.listByDateRange() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<ComprobantesRecibidos> findComprobantesAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ComprobantesRecibidos> cq = cb.createQuery(ComprobantesRecibidos.class);
        Root<ComprobantesRecibidos> ComprobantesRecibidos = cq.from(ComprobantesRecibidos.class);
        Join<ComprobantesRecibidos, Encabezado> encabezado = ComprobantesRecibidos.join("encabezado");

        Predicate datePredicate = cb.greaterThan(encabezado.get("fechaEmision"), fecha);
        cq.where(datePredicate);

        TypedQuery<ComprobantesRecibidos> query = em.createQuery(cq);
        return query.getResultList();
    }

   @Nullable
   public List<ComprobantesRecibidos> listPendientes() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.paid = false",
                ComprobantesRecibidos.class
            );
            List<ComprobantesRecibidos> results = query.getResultList();
            java.time.LocalDate currentDate = java.time.LocalDate.now();
            
            // Filter in Java - date due date is in the future
            return results.stream()
                .filter(f -> {
                    if (f.getEncabezado() != null && f.getEncabezado().getPlazoCredito() != null && f.getEncabezado().getFechaEmision() != null) {
                        try {
                            int plazoCredito = Integer.parseInt(f.getEncabezado().getPlazoCredito());
                            java.time.LocalDate dueDate = f.getEncabezado().getFechaEmision().toLocalDate().plusDays(plazoCredito);
                            return dueDate.isAfter(currentDate);
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.toList());
        } catch (PersistenceException e) {
            LOG.warn("Error listing pendientes: " + e.getMessage() + " | source=ComprobantesRecibidosService.listPendientes() | despues=" + e.getMessage());
            return null;
        }
    }

    @Nonnull
    public List<ComprobantesRecibidos> listVencidas() {
        try {
            // Query unpaid invoices only - avoid JOIN FETCH on collection (lineasDetalle) 
            // as it causes duplicate rows and Hibernate DISTINCT issues
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.paid = false",
                ComprobantesRecibidos.class
            );
            List<ComprobantesRecibidos> results = query.getResultList();
            java.time.LocalDate currentDate = java.time.LocalDate.now();
            
            // Filter in Java - due date is today or in the past
            return results.stream()
                .filter(f -> {
                    if (f.getEncabezado() != null && f.getEncabezado().getPlazoCredito() != null && f.getEncabezado().getFechaEmision() != null) {
                        try {
                            int plazoCredito = Integer.parseInt(f.getEncabezado().getPlazoCredito());
                            java.time.LocalDate dueDate = f.getEncabezado().getFechaEmision().toLocalDate().plusDays(plazoCredito);
                            return dueDate.isBefore(currentDate) || dueDate.isEqual(currentDate);
                        } catch (NumberFormatException e) {
                            return false;
                        }
                    }
                    return false;
                })
                .collect(java.util.stream.Collectors.toList());
        } catch (PersistenceException e) {
            LOG.warn("Error listing vencidas: " + e.getMessage() + " | source=ComprobantesRecibidosService.listVencidas() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    // ── SQL-paged reporte Recibos Pendientes / Vencidos (app) ─────────────
    // /app/reportes/recibos/{pendientes,vencidos} llamaba a listPendientes() /
    // listVencidas(): cargaban TODAS las facturas recibidas impagas con su
    // grafo (detalles + lineas + encabezado + resumen), calculaban el
    // vencimiento en Java y paginaban en memoria. Con 50k recibidos eso pasa
    // de 30 s. Estas consultas dejan el conteo, el filtro, el orden y el
    // recorte a SQL; solo se hidrata la pagina pedida.

    /**
     * COUNT companion of {@link #pageRecibosPorVencimiento}: identical
     * FROM/WHERE, so the pager total and the returned page always describe the
     * same result set.
     *
     * @param vencidos {@code true} → {@code listVencidas()} (due date today or
     *                 past); {@code false} → {@code listPendientes()} (due date
     *                 still in the future)
     * @param q        free-text filter over the report columns
     *                 (consecutivo, emisor nombre, condicion de venta, fecha de
     *                 emision); null/blank means "no filter"
     * @return filtered row count, 0 when the query fails (logged)
     */
    public long countRecibosPorVencimiento(boolean vencidos, @Nullable String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT COUNT(f)"
                + fromClauseReporte(false, tieneTexto(q))
                + whereClauseReporte(vencidos, q, params);
        try {
            TypedQuery<Long> query = em.createQuery(jpql, Long.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (PersistenceException e) {
            LOG.warn("Error contando recibos por vencimiento: " + e.getMessage()
                    + " | source=ComprobantesRecibidosService.countRecibosPorVencimiento()"
                    + " | vencidos=" + vencidos + " | q=" + q
                    + " | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * One page of the Pendientes/Vencidos report, filtered and sorted in SQL.
     *
     * <p>Same predicates as the legacy service calls the report used:</p>
     * <ul>
     *   <li>{@code f.paid = false};</li>
     *   <li>due date = {@code fechaEmision.toLocalDate() + plazoCredito}:
     *       {@code > today} for pendientes, {@code <= today} for vencidas —
     *       exactly the Java {@code isAfter}/{@code !isAfter} comparisons;</li>
     *   <li>a non-numeric, empty or overflow-length {@code plazoCredito} makes
     *       the due date NULL, so the row is excluded, mirroring the old
     *       {@code NumberFormatException → false} branch.</li>
     * </ul>
     *
     * <p>Only to-one associations are fetched ({@code encabezado},
     * {@code emisor}, {@code resumen}): the template reads them for every
     * column, and fetching them avoids one lazy load per row. The old
     * {@code DISTINCT} was only needed because {@code listPendientes()}/
     * {@code listVencidas()} also fetched the {@code detalles} bag; without a
     * bag there is no row multiplication.</p>
     *
     * <p>ORDER BY is whitelisted against the report columns
     * ({@code consecutivo}, {@code fecha}, {@code emisor}, {@code condicion},
     * {@code plazo}, {@code total}); {@code estado} deliberately keeps the
     * legacy no-op (the in-memory switch had no comparator for it) and unknown
     * keys fall back to {@code f.id}. Every order ends with {@code f.id ASC} so
     * a page can never shuffle between requests. Explicit {@code NULLS FIRST}
     * (ASC) / {@code NULLS LAST} (DESC) mirror the old
     * {@code nullsFirst(...)} + {@code reversed()} comparators.</p>
     *
     * @param offset zero-based first row
     * @param size   page size (clamped to at least 1)
     */
    public @Nonnull List<ComprobantesRecibidos> pageRecibosPorVencimiento(
            boolean vencidos, @Nullable String q,
            @Nullable String sort, @Nullable String dir,
            int offset, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT f"
                + fromClauseReporte(true, true)
                + whereClauseReporte(vencidos, q, params)
                + orderByReporte(sort, dir);
        try {
            TypedQuery<ComprobantesRecibidos> query =
                    em.createQuery(jpql, ComprobantesRecibidos.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error paginando recibos por vencimiento: " + e.getMessage()
                    + " | source=ComprobantesRecibidosService.pageRecibosPorVencimiento()"
                    + " | vencidos=" + vencidos + " | q=" + q
                    + " | sort=" + sort + " | dir=" + dir
                    + " | offset=" + offset + " | size=" + size
                    + " | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * FROM shared by the report COUNT/page. The emisor join is only added when
     * the page needs it (page always, COUNT only for the {@code q} predicate);
     * it is a to-one LEFT JOIN in both cases, so no COUNT row multiplication.
     */
    private static @Nonnull String fromClauseReporte(boolean fetchForPage, boolean needsEmisor) {
        String join = fetchForPage ? " LEFT JOIN FETCH " : " LEFT JOIN ";
        String from = " FROM ComprobantesRecibidos f" + join + "f.encabezado e";
        if (fetchForPage) {
            from += join + "f.resumen r";
        }
        if (needsEmisor) {
            from += join + "e.emisor em";
        }
        return from;
    }

    /**
     * Shared WHERE for {@link #countRecibosPorVencimiento} and
     * {@link #pageRecibosPorVencimiento}. The {@code q} fields are exactly the
     * ones the resource filtered in memory (emisor nombre, numero
     * consecutivo, condicion de venta and fecha de emision), case-insensitive
     * contains with {@code %}/{@code _} escaped.
     */
    private static @Nonnull String whereClauseReporte(boolean vencidos, @Nullable String q,
                                                      @Nonnull Map<String, Object> params) {
        List<String> predicates = new ArrayList<>();
        predicates.add("f.paid = false");
        predicates.add("CAST(timestampadd(day, " + plazoEnDias()
                + ", e.fechaEmision) AS date) " + (vencidos ? "<= :hoy" : "> :hoy"));
        params.put("hoy", LocalDate.now());

        String term = q == null ? "" : q.trim();
        if (!term.isEmpty()) {
            String like = "LIKE :q ESCAPE '" + LIKE_ESCAPE + "'";
            predicates.add("(LOWER(COALESCE(em.nombre, '')) " + like
                    + " OR LOWER(COALESCE(e.numeroConsecutivo, '')) " + like
                    + " OR LOWER(COALESCE(e.condicionVenta, '')) " + like
                    + " OR LOWER(COALESCE(FUNCTION('to_char', e.fechaEmision,"
                    + " 'YYYY-MM-DD\"T\"HH24:MI:SS'), '')) " + like + ")");
            params.put("q", likePattern(term.toLowerCase(Locale.ROOT)));
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Whitelisted ORDER BY for the report table. {@code plazo} and
     * {@code condicion} sort as the VARCHAR columns they are (the old
     * comparators compared the raw strings), {@code emisor} sorts
     * case-insensitively on {@code LOWER(nombre)} like the inbox query, and
     * {@code f.id ASC} is the deterministic tiebreak.
     */
    private static @Nonnull String orderByReporte(@Nullable String sort, @Nullable String dir) {
        String key = sort == null ? "" : sort.trim();
        boolean desc = "desc".equalsIgnoreCase(dir == null ? "" : dir.trim());
        String direction = desc ? " DESC" : " ASC";
        String nulls = desc ? " NULLS LAST" : " NULLS FIRST";
        return switch (key) {
            case "consecutivo" -> " ORDER BY e.numeroConsecutivo" + direction + nulls + ", f.id ASC";
            case "fecha" -> " ORDER BY e.fechaEmision" + direction + nulls + ", f.id ASC";
            case "emisor" -> " ORDER BY LOWER(em.nombre)" + direction + nulls + ", f.id ASC";
            case "condicion" -> " ORDER BY e.condicionVenta" + direction + nulls + ", f.id ASC";
            case "plazo" -> " ORDER BY e.plazoCredito" + direction + nulls + ", f.id ASC";
            case "total" -> " ORDER BY r.totalComprobante" + direction + nulls + ", f.id ASC";
            // "estado" has no comparator in the legacy switch either: keep the
            // stable id order instead of inventing a new one.
            default -> " ORDER BY f.id ASC";
        };
    }

    /** True when the free-text filter has anything to match. */
    private static boolean tieneTexto(@Nullable String q) {
        return q != null && !q.trim().isEmpty();
    }

    // ── SQL-paged inbox (buzón de facturas recibidas) ─────────────────────
    // Replaces the resource's "load every row, filter/sort/slice in memory"
    // path. Kept in this service (instead of the resource) so the SAME
    // FROM/WHERE feeds the COUNT and the page, and the pager total can never
    // disagree with the returned rows.

    /** Escape character for the free-text LIKE patterns (contains semantics). */
    private static final char LIKE_ESCAPE = '!';

    /**
     * COUNT companion of {@link #pageRecibidos}: identical FROM/WHERE, so the
     * pager total and the page always describe the same result set.
     *
     * @param bucket {@code todas|activas|pagadas|procesadas|pendientes|vencidas}
     *               (null/unknown behaves like {@code todas})
     * @param q      global filter text; null/blank means "no filter"
     */
    public long countRecibidos(@Nullable String bucket, @Nullable String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT COUNT(f)" + fromClause(false)
                + whereClause(bucket, q, params);
        try {
            TypedQuery<Long> query = em.createQuery(jpql, Long.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (PersistenceException e) {
            LOG.warn("Error counting recibidos: " + e.getMessage()
                    + " | source=ComprobantesRecibidosService.countRecibidos()"
                    + " | bucket=" + bucket + " | q=" + q
                    + " | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * SQL-paged list of received invoices for the inbox table.
     *
     * <p>Bucket predicates mirror the legacy service calls the resource used
     * before paging in SQL:</p>
     * <ul>
     *   <li>{@code todas}/{@code activas} → {@code f.status = true}
     *       ({@code ListAllEnabled()} plus the old {@code status} filter);</li>
     *   <li>{@code pagadas} → {@code f.paid = true AND f.status = true};</li>
     *   <li>{@code procesadas} → {@code f.processed = true AND f.status = true};</li>
     *   <li>{@code pendientes} → {@code f.paid = false} and
     *       {@code fechaEmision.toLocalDate() + plazoCredito > today}
     *       ({@code listPendientes()});</li>
     *   <li>{@code vencidas} → {@code f.paid = false} and
     *       {@code fechaEmision.toLocalDate() + plazoCredito <= today}
     *       ({@code listVencidas()}).</li>
     * </ul>
     *
     * <p>{@code q} keeps the legacy {@code globalFilterFunction} fields: codigo
     * de actividad del emisor, condicion de venta, emisor nombre/nombre
     * comercial/identificacion/correos, fecha de emision and numero
     * consecutivo, all case-insensitive contains. {@code %}/{@code _} are
     * escaped because the legacy filter used {@code String.contains}.</p>
     *
     * <p>ORDER BY is whitelisted; unknown/null keys fall back to {@code f.id}.
     * Every order ends with {@code f.id ASC}, so a page can never shuffle
     * between requests. The old Java comparators used {@code nullsFirst} and
     * then {@code reversed()} for DESC, i.e. nulls first on ASC and last on
     * DESC — mirrored with explicit {@code NULLS FIRST}/{@code NULLS LAST}.</p>
     *
     * @param offset zero-based first row
     * @param size   page size (clamped to at least 1)
     */
    public @Nonnull List<ComprobantesRecibidos> pageRecibidos(@Nullable String bucket, @Nullable String q,
                                                              @Nullable String sort, @Nullable String dir,
                                                              int offset, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT f" + fromClause(true)
                + whereClause(bucket, q, params)
                + orderBy(sort, dir);
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(jpql, ComprobantesRecibidos.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error paging recibidos: " + e.getMessage()
                    + " | source=ComprobantesRecibidosService.pageRecibidos()"
                    + " | bucket=" + bucket + " | q=" + q + " | sort=" + sort + " | dir=" + dir
                    + " | offset=" + offset + " | size=" + size
                    + " | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Shared FROM builder. To-one relations only, so no row multiplication and
     * no DISTINCT needed (the old {@code DISTINCT} forced a full sort before
     * LIMIT). The page variant FETCHes exactly what the list DTO reads;
     * identificacion is a plain LEFT JOIN because only the filter uses it and
     * an implicit join would drop emisores without a tax id.
     */
    private static @Nonnull String fromClause(boolean fetchForPage) {
        String from = " FROM ComprobantesRecibidos f"
                + " LEFT JOIN " + (fetchForPage ? "FETCH " : "") + "f.encabezado e";
        if (fetchForPage) {
            // Only the page DTO reads resumen; COUNT never needs it.
            from += " LEFT JOIN FETCH f.resumen r";
        }
        return from
                + " LEFT JOIN " + (fetchForPage ? "FETCH " : "") + "e.emisor em"
                + " LEFT JOIN em.identificacion ident";
    }

    /**
     * Shared WHERE builder for {@link #countRecibidos} and
     * {@link #pageRecibidos}. User text only ever travels as the {@code :q}
     * parameter; the JPQL is assembled from constants.
     */
    private static @Nonnull String whereClause(@Nullable String bucket, @Nullable String q,
                                               @Nonnull Map<String, Object> params) {
        List<String> predicates = new ArrayList<>();
        switch (normalizarBucket(bucket)) {
            case "activas" -> predicates.add("f.status = true");
            case "pagadas" -> predicates.add("(f.paid = true AND f.status = true)");
            case "procesadas" -> predicates.add("(f.processed = true AND f.status = true)");
            case "pendientes" -> {
                predicates.add("f.paid = false");
                predicates.add("CAST(timestampadd(day, " + plazoEnDias()
                        + ", e.fechaEmision) AS date) > :hoy");
                params.put("hoy", LocalDate.now());
            }
            case "vencidas" -> {
                predicates.add("f.paid = false");
                predicates.add("CAST(timestampadd(day, " + plazoEnDias()
                        + ", e.fechaEmision) AS date) <= :hoy");
                params.put("hoy", LocalDate.now());
            }
            default -> predicates.add("f.status = true");
        }
        String term = q == null ? "" : q.trim();
        if (!term.isEmpty()) {
            String like = "LIKE :q ESCAPE '" + LIKE_ESCAPE + "'";
            predicates.add("(LOWER(COALESCE(e.codigoActividadEmisor, '')) " + like
                    + " OR LOWER(COALESCE(e.condicionVenta, '')) " + like
                    + " OR LOWER(COALESCE(em.nombre, '')) " + like
                    + " OR LOWER(COALESCE(em.nombreComercial, '')) " + like
                    + " OR LOWER(COALESCE(ident.numero, '')) " + like
                    + " OR LOWER(COALESCE(FUNCTION('to_char', e.fechaEmision,"
                    + " 'YYYY-MM-DD\"T\"HH24:MI:SS'), '')) " + like
                    + " OR LOWER(COALESCE(e.numeroConsecutivo, '')) " + like
                    + " OR EXISTS (SELECT ce.id FROM CorreoElectronicoEmisor ce"
                    + " WHERE ce.emisor = em AND LOWER(COALESCE(ce.correo, '')) " + like + "))");
            params.put("q", likePattern(term.toLowerCase(Locale.ROOT)));
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Plazo de credito as days, or NULL when the legacy
     * {@code Integer.parseInt} would have thrown (non-numeric, empty or
     * overflow-length). The CASE keeps PostgreSQL from evaluating the cast on
     * non-numeric rows, and NULL makes the date comparison false — the same
     * {@code NumberFormatException} guard the old in-memory filter had.
     */
    private static @Nonnull String plazoEnDias() {
        return "CASE WHEN e.plazoCredito IS NOT NULL"
                + " AND e.plazoCredito <> ''"
                + " AND LENGTH(e.plazoCredito) <= 9"
                + " AND FUNCTION('regexp_replace', e.plazoCredito, '[^0-9]', '', 'g') = e.plazoCredito"
                + " THEN CAST(e.plazoCredito AS integer) ELSE NULL END";
    }

    /**
     * Whitelisted ORDER BY. {@code emisorNombre} is the column key the table
     * declares ({@code emisor} is kept for the legacy API); text keys sort on
     * {@code LOWER(...)} like the old case-insensitive comparator, and
     * {@code f.id ASC} is the deterministic tiebreak.
     */
    private static @Nonnull String orderBy(@Nullable String sort, @Nullable String dir) {
        String key = sort == null ? "" : sort.trim();
        boolean desc = "desc".equalsIgnoreCase(dir == null ? "" : dir.trim());
        String direction = desc ? " DESC" : " ASC";
        String nulls = desc ? " NULLS LAST" : " NULLS FIRST";
        return switch (key) {
            case "consecutivo" -> " ORDER BY e.numeroConsecutivo" + direction + nulls + ", f.id ASC";
            case "fechaEmision" -> " ORDER BY e.fechaEmision" + direction + nulls + ", f.id ASC";
            case "emisor", "emisorNombre" -> " ORDER BY LOWER(em.nombre)" + direction + nulls + ", f.id ASC";
            case "totalComprobante" -> " ORDER BY r.totalComprobante" + direction + nulls + ", f.id ASC";
            case "totalImpuesto" -> " ORDER BY r.totalImpuesto" + direction + nulls + ", f.id ASC";
            case "estado" -> " ORDER BY e.estado" + direction + nulls + ", f.id ASC";
            default -> " ORDER BY f.id" + direction;
        };
    }

    /** Same bucket normalization used by the resource (unknown → todas). */
    private static @Nonnull String normalizarBucket(@Nullable String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return "todas";
        }
        return switch (bucket.trim().toLowerCase(Locale.ROOT)) {
            case "activas" -> "activas";
            case "pagadas" -> "pagadas";
            case "procesadas" -> "procesadas";
            case "pendientes" -> "pendientes";
            case "vencidas" -> "vencidas";
            default -> "todas";
        };
    }

    /**
     * Builds the {@code %term%} LIKE pattern, escaping the escape char,
     * {@code %} and {@code _} so the old {@code String.contains} semantics are
     * preserved for literal user input.
     */
    private static @Nonnull String likePattern(@Nonnull String term) {
        StringBuilder pattern = new StringBuilder(term.length() + 2);
        pattern.append('%');
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(ch);
        }
        pattern.append('%');
        return pattern.toString();
    }

    @Nullable
    public List<ComprobantesRecibidos> findPendientesMensajeReceptor() {
        try {
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesRecibidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle " +
                "LEFT JOIN FETCH f.encabezado " +
                "LEFT JOIN FETCH f.resumen " +
                "WHERE f.status = true " +
                "AND (f.haciendaMensajeReceptorEstado IS NULL OR f.haciendaMensajeReceptorEstado = 'PENDIENTE') " +
                "ORDER BY f.encabezado.fechaEmision DESC",
                ComprobantesRecibidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding pendientes Mensaje Receptor: " + e.getMessage() + " | source=ComprobantesRecibidosService.findPendientesMensajeReceptor() | despues=" + e.getMessage());
            return null;
        }
    }

    @Nonnull
    public List<ComprobantesRecibidos> findProximosVencerMensajeReceptor(int dias) {
        try {
            LocalDate limite = LocalDate.now().plusDays(dias);
            List<ComprobantesRecibidos> todos = findPendientesMensajeReceptor();
            if (todos == null) return java.util.Collections.emptyList();
            
            return todos.stream()
                .filter(f -> f.getMensajeReceptorLimite() != null && !f.getMensajeReceptorLimite().isAfter(limite))
                .collect(java.util.stream.Collectors.toList());
        } catch (PersistenceException e) {
            LOG.warn("Error finding proximos vencer Mensaje Receptor: " + e.getMessage() + " | source=ComprobantesRecibidosService.findProximosVencerMensajeReceptor() | despues=" + e.getMessage());
            return null;
        }
}

}
