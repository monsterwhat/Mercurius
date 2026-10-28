package Services;

import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Models.Usuarios;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
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
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.time.LocalDateTime;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class ComprobantesEmitidosService extends GService<ComprobantesEmitidos> {
    
    private static final Logger LOG = Logger.getLogger(ComprobantesEmitidosService.class);

    @Override
    protected @Nonnull Class<ComprobantesEmitidos> getEntityClass() {
        return ComprobantesEmitidos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=ComprobantesEmitidosService.create() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public @Nullable ComprobantesEmitidos createAndReturn(@Nonnull ComprobantesEmitidos entity) {
        try {
            em.persist(entity);
            em.flush(); // Ensure the entity is persisted and gets an ID
            em.refresh(entity); // Refresh to get any database-generated values
            return entity;
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=ComprobantesEmitidosService.createAndReturn() | despues=" + e.getMessage());
            return null;
        }
    }

    @Override
    @Transactional
    public void delete(@Nonnull ComprobantesEmitidos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getId());
            }

            if (entity != null) {
                em.remove(entity);
                em.flush();
            } else {
                LOG.info("Entity not found for delete | source=ComprobantesEmitidosService.delete()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=ComprobantesEmitidosService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull ComprobantesEmitidos entity) {
        try {
            em.merge(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=ComprobantesEmitidosService.update() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void softDelete(@Nonnull ComprobantesEmitidos entity) {
        try {
            // Find the item by its ID
            ComprobantesEmitidos existingItem = em.find(getEntityClass(), entity.getId());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
                em.flush();
            } else {
                LOG.info("Entity not found for softDelete | source=ComprobantesEmitidosService.softDelete()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error soft deleting entity: " + e.getMessage() + " | source=ComprobantesEmitidosService.softDelete() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void toggle(@Nonnull ComprobantesEmitidos entity){
        try {
            // Find the item by its ID
            ComprobantesEmitidos existingItem = em.find(getEntityClass(), entity.getId());

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
                LOG.info("Entity not found for toggle | source=ComprobantesEmitidosService.toggle()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error toggling entity: " + e.getMessage() + " | source=ComprobantesEmitidosService.toggle() | despues=" + e.getMessage());
        }
    }
    
    /**
     * Conteos por estado de Hacienda (SQL). El historico completo son cientos
     * de miles de comprobantes: contarlos en Java exige materializar la tabla.
     */
    public @Nonnull java.util.Map<String, Long> countByHaciendaEstado() {
        java.util.Map<String, Long> salida = new java.util.LinkedHashMap<>();
        try {
            var query = em.createNativeQuery(
                    "SELECT COALESCE(haciendaestado, 'SIN_ESTADO') AS e, count(*) AS c"
                            + " FROM comprobantesemitidos GROUP BY 1 ORDER BY 2 DESC");
            @SuppressWarnings("unchecked")
            List<Object[]> filas = (List<Object[]>) query.getResultList();
            for (Object[] f : filas) {
                salida.put(String.valueOf(f[0]), ((Number) f[1]).longValue());
            }
        } catch (PersistenceException e) {
            LOG.warn("Error contando por estado de Hacienda: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.countByHaciendaEstado() | despues=" + e.getMessage());
        }
        return salida;
    }

    /** Total de comprobantes emitidos. */
    public long countEmitidos() {
        try {
            Object v = em.createNativeQuery("SELECT count(*) FROM comprobantesemitidos").getSingleResult();
            return v instanceof Number n ? n.longValue() : 0L;
        } catch (PersistenceException e) {
            LOG.warn("Error contando comprobantes: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.countEmitidos() | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * Comprobantes emitidos cuyo consecutivo contiene el texto (acotado).
     * Reemplaza el listAll() + filtrado en Java.
     */
    public @Nonnull List<ComprobantesEmitidos> listByConsecutivoLike(@Nullable String texto, int max) {
        String like = "%" + (texto == null ? "" : texto.trim().toUpperCase(java.util.Locale.ROOT)) + "%";
        if (texto == null || texto.trim().isEmpty()) {
            return java.util.List.of();
        }
        try {
            var q = em.createNativeQuery(
                    "SELECT f.id FROM comprobantesemitidos f JOIN encabezado e ON e.id = f.encabezado_id"
                            + " WHERE UPPER(COALESCE(e.numero_consecutivo,'')) LIKE :q"
                            + " ORDER BY e.fecha_emision DESC NULLS LAST LIMIT :lim");
            q.setParameter("q", like);
            q.setParameter("lim", Math.max(max, 1));
            @SuppressWarnings("unchecked")
            List<Number> ids = (List<Number>) q.getResultList();
            return cargarPorIds(ids);
        } catch (PersistenceException e) {
            LOG.warn("Error buscando por consecutivo: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listByConsecutivoLike() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    /** Comprobantes emitidos cuyo receptor coincide con el texto (acotado). */
    public @Nonnull List<ComprobantesEmitidos> listByReceptorLike(@Nullable String texto, int max) {
        if (texto == null || texto.trim().isEmpty()) {
            return java.util.List.of();
        }
        String like = "%" + texto.trim().toUpperCase(java.util.Locale.ROOT) + "%";
        try {
            var q = em.createNativeQuery(
                    "SELECT f.id FROM comprobantesemitidos f JOIN encabezado e ON e.id = f.encabezado_id"
                            + " LEFT JOIN receptor rc ON rc.id = e.receptor_id"
                            + " WHERE UPPER(COALESCE(rc.nombre,'')) LIKE :q"
                            + " ORDER BY e.fecha_emision DESC NULLS LAST LIMIT :lim");
            q.setParameter("q", like);
            q.setParameter("lim", Math.max(max, 1));
            @SuppressWarnings("unchecked")
            List<Number> ids = (List<Number>) q.getResultList();
            return cargarPorIds(ids);
        } catch (PersistenceException e) {
            LOG.warn("Error buscando por receptor: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listByReceptorLike() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    /** Comprobantes emitidos de un tipo de documento concreto (notas de credito). */
    public @Nonnull List<ComprobantesEmitidos> listByCodigoDocumento(@Nullable String codigo, int max) {
        if (codigo == null || codigo.isBlank()) {
            return java.util.List.of();
        }
        try {
            var q = em.createNativeQuery(
                    "SELECT f.id FROM comprobantesemitidos f JOIN encabezado e ON e.id = f.encabezado_id"
                            + " WHERE e.codigo_documento = :cod"
                            + " ORDER BY e.fecha_emision DESC NULLS LAST LIMIT :lim");
            q.setParameter("cod", codigo);
            q.setParameter("lim", Math.max(max, 1));
            @SuppressWarnings("unchecked")
            List<Number> ids = (List<Number>) q.getResultList();
            return cargarPorIds(ids);
        } catch (PersistenceException e) {
            LOG.warn("Error buscando por tipo de documento: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listByCodigoDocumento() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Comprobantes cuyo consecutivo CONTIENE el texto, resuelto en SQL.
     *
     * <p>Sustituye el {@code listAll()} + {@code String.contains()} de la
     * busqueda de devoluciones: con el historico completo ese camino
     * materializaba cientos de miles de filas para quedarse con las primeras
     * coincidencias. El {@code LIKE} de PostgreSQL distingue mayusculas igual
     * que {@code contains}, y las filas van con encabezado, receptor y resumen
     * ya cargados para que el mapeo de la respuesta no dispare una consulta
     * por fila.</p>
     *
     * @param q     texto buscado dentro de {@code encabezado.numeroConsecutivo}
     * @param limit maximo de filas devueltas (se fuerza a {@code >= 1})
     * @return las coincidencias mas recientes ({@code fechaEmision DESC}); lista
     *         vacia si {@code q} esta en blanco o si la consulta falla
     */
    public @Nonnull List<ComprobantesEmitidos> findByIdContainingConsecutivo(@Nullable String q, int limit) {
        if (q == null || q.trim().isEmpty()) {
            return java.util.List.of();
        }
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f"
                            + " LEFT JOIN FETCH f.resumen"
                            + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.receptor"
                            + " WHERE e.numeroConsecutivo LIKE :q"
                            + " ORDER BY e.fechaEmision DESC NULLS LAST, f.id DESC",
                    ComprobantesEmitidos.class);
            query.setParameter("q", "%" + q.trim() + "%");
            query.setMaxResults(Math.max(limit, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error buscando por consecutivo: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.findByIdContainingConsecutivo() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Comprobantes cuyo receptor cumple el criterio de la busqueda legacy.
     *
     * <p><b>Ojo con la direccion del {@code LIKE}:</b> el legacy
     * ({@code DevolucionesController.buscarFactura}) se quedaba con las
     * facturas cuyo nombre de receptor estaba CONTENIDO en el criterio
     * ({@code criterio.contains(receptor.nombre)}), no al reves. Se conserva esa
     * semantica invirtiendo los lados del {@code LIKE} en vez de "corregirla":
     * cambiar la direccion altera resultados (buscar "Cliente" pasaria de no
     * devolver nada a devolver todas las facturas de "Cliente Contado").
     * {@code rc.nombre IS NOT NULL} replica el {@code continue} del legacy sobre
     * receptores sin nombre, que un {@code CONCAT} dejaria pasar como cadena
     * vacia.</p>
     *
     * @param q     criterio que debe contener el nombre del receptor
     * @param limit maximo de filas devueltas (se fuerza a {@code >= 1})
     * @return las coincidencias mas recientes ({@code fechaEmision DESC}); lista
     *         vacia si {@code q} esta en blanco o si la consulta falla
     */
    public @Nonnull List<ComprobantesEmitidos> findByClientNameLike(@Nullable String q, int limit) {
        if (q == null || q.trim().isEmpty()) {
            return java.util.List.of();
        }
        // El criterio se compara YA en minusculas (el LIKE invertido pone el
        // criterio a la izquierda y el nombre del receptor a la derecha).
        String needle = q.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f"
                            + " LEFT JOIN FETCH f.resumen"
                            + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.receptor rc"
                            + " WHERE rc.nombre IS NOT NULL"
                            + " AND :q LIKE CONCAT('%', LOWER(rc.nombre), '%')"
                            + " ORDER BY e.fechaEmision DESC NULLS LAST, f.id DESC",
                    ComprobantesEmitidos.class);
            query.setParameter("q", needle);
            query.setMaxResults(Math.max(limit, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error buscando por nombre de cliente: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.findByClientNameLike() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    /** Carga las filas indicadas con encabezado, resumen y emisor. */
    private @Nonnull List<ComprobantesEmitidos> cargarPorIds(@Nonnull List<Number> ids) {
        if (ids.isEmpty()) {
            return java.util.List.of();
        }
        List<Long> longs = new java.util.ArrayList<>(ids.size());
        for (Number n : ids) {
            longs.add(n.longValue());
        }
        TypedQuery<ComprobantesEmitidos> q = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f"
                        + " LEFT JOIN FETCH f.resumen"
                        + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.emisor"
                        + " WHERE f.id IN :ids",
                ComprobantesEmitidos.class);
        q.setParameter("ids", longs);
        return q.getResultList();
    }

    /**
     * Comprobantes con clave (documentos electronicos).
     *
     * <p>El historico importado del sistema viejo (vsuper) son facturas
     * pre-electronicas: no tienen clave de 50 digitos, no se enviaron nunca a
     * Hacienda y no se pueden sustituir ni creditar. Los tableros operativos
     * (recibos, tributacion, devoluciones, factura publica) deben trabajar solo
     * con documentos electronicos; el historico se ve en los reportes, que si
     * filtran por fecha.</p>
     */
    public @Nonnull List<ComprobantesEmitidos> listAllConClave() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesEmitidos f"
                        + " LEFT JOIN FETCH f.resumen"
                        + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.emisor"
                        + " WHERE e.clave IS NOT NULL",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all electronic comprobantes: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listAllConClave() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public @Nonnull List<ComprobantesEmitidos> listAll() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT DISTINCT f FROM ComprobantesEmitidos f LEFT JOIN FETCH f.resumen LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.emisor",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=ComprobantesEmitidosService.listAll() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Listado PAGINADO (el historico puede tener cientos de miles de
    // comprobantes: materializar la tabla entera desborda el heap y el
    // timeout, asi que el filtro de bucket va a SQL y solo se materializa la
    // pagina pedida).
    // ════════════════════════════════════════════════════════════════════

    /** Una pagina de comprobantes emitidos + el total del bucket. */
    public record PaginaEmitidos(List<ComprobantesEmitidos> filas, long total) { }

    /** Predicado SQL del bucket (mismo criterio que las versiones en Java). */
    private static @Nonnull String bucketWhere(@Nonnull String bucket) {
        return switch (bucket) {
            case "pagadas" -> " AND UPPER(COALESCE(e.estado,'')) = 'ACEPTADO'";
            case "procesadas" -> " AND f.haciendaestado IS NOT NULL AND UPPER(f.haciendaestado) <> 'PENDIENTE'";
            case "vencidos" -> " AND e.condicion_venta = '02' AND e.fecha_emision IS NOT NULL"
                    + " AND e.plazo_credito IS NOT NULL AND btrim(e.plazo_credito) ~ '^[0-9]+$'"
                    + " AND (e.fecha_emision::date + btrim(e.plazo_credito)::int)::date <= CURRENT_DATE"
                    + " AND UPPER(COALESCE(e.estado,'')) NOT IN ('RECHAZADO','ACEPTADO')";
            default -> "";
        };
    }

    /** Filtro de busqueda (mismos campos que la version en Java). */
    private static @Nonnull String qWhere(@Nullable String q) {
        String texto = q == null ? "" : q.trim();
        if (texto.isEmpty()) {
            return "";
        }
        String like = "%" + texto.toUpperCase(java.util.Locale.ROOT) + "%";
        return " AND (UPPER(COALESCE(e.codigo_actividad_emisor,'')) LIKE :q"
                + " OR UPPER(COALESCE(e.condicion_venta,'')) LIKE :q"
                + " OR UPPER(COALESCE(em.nombre,'')) LIKE :q"
                + " OR UPPER(COALESCE(em.nombre_comercial,'')) LIKE :q"
                + " OR UPPER(COALESCE(ie.numero,'')) LIKE :q"
                + " OR UPPER(COALESCE(e.fecha_emision::text,'')) LIKE :q"
                + " OR UPPER(COALESCE(e.numero_consecutivo,'')) LIKE :q"
                + " OR UPPER(COALESCE(rc.nombre,'')) LIKE :q)";
    }

    /** Columna de ordenamiento (solo claves permitidas). */
    private static @Nonnull String orderColumn(@Nullable String sort) {
        if (sort == null) {
            return "f.id";
        }
        return switch (sort) {
            case "consecutivo" -> "e.numero_consecutivo";
            case "fechaEmision" -> "e.fecha_emision";
            case "emisorNombre" -> "em.nombre";
            case "totalComprobante" -> "r.total_comprobante";
            case "totalImpuesto" -> "r.total_impuesto";
            case "haciendaEstado" -> "f.haciendaestado";
            default -> "f.id";
        };
    }

    private static @Nonnull String fromWhere(@Nonnull String bucket, @Nullable String q) {
        return " FROM comprobantesemitidos f"
                + " JOIN encabezado e ON e.id = f.encabezado_id"
                + " LEFT JOIN resumen_factura r ON r.id = f.resumen_id"
                + " LEFT JOIN emisor em ON em.id = e.emisor_id"
                + " LEFT JOIN identificacion_emisor ie ON ie.id = em.identificacion_emisor_id"
                + " LEFT JOIN receptor rc ON rc.id = e.receptor_id"
                + " WHERE 1=1" + bucketWhere(bucket) + qWhere(q);
    }

    /**
     * Numero de comprobantes del bucket (con el filtro de busqueda).
     */
    public long countEmitidos(@Nonnull String bucket, @Nullable String q) {
        try {
            var query = em.createNativeQuery("SELECT count(*)" + fromWhere(bucket, q));
            if (q != null && !q.trim().isEmpty()) {
                query.setParameter("q", "%" + q.trim().toUpperCase(java.util.Locale.ROOT) + "%");
            }
            Object v = query.getSingleResult();
            return v instanceof Number n ? n.longValue() : 0L;
        } catch (PersistenceException e) {
            LOG.warn("Error contando comprobantes emitidos: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.countEmitidos() | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * Pagina de comprobantes emitidos con el filtro de bucket en SQL.
     *
     * @param first indice de la primera fila (0-based)
     * @param max   numero maximo de filas
     */
    public @Nonnull PaginaEmitidos listPagedEmitidos(@Nonnull String bucket, int first, int max,
                                                     @Nullable String sort, @Nullable String dir,
                                                     @Nullable String q) {
        long total = countEmitidos(bucket, q);
        if (total <= 0 || max <= 0) {
            return new PaginaEmitidos(java.util.List.of(), total);
        }
        try {
            String order = orderColumn(sort);
            String direccion = "desc".equalsIgnoreCase(dir == null ? "" : dir.trim()) ? "DESC" : "ASC";
            var query = em.createNativeQuery("SELECT f.id" + fromWhere(bucket, q)
                    + " ORDER BY " + order + " " + direccion + ", f.id ASC LIMIT :lim OFFSET :off");
            if (q != null && !q.trim().isEmpty()) {
                query.setParameter("q", "%" + q.trim().toUpperCase(java.util.Locale.ROOT) + "%");
            }
            query.setParameter("lim", max);
            query.setParameter("off", Math.max(first, 0));
            @SuppressWarnings("unchecked")
            List<Number> ids = (List<Number>) query.getResultList();
            if (ids.isEmpty()) {
                return new PaginaEmitidos(java.util.List.of(), total);
            }
            List<Long> longs = new java.util.ArrayList<>(ids.size());
            for (Number n : ids) {
                longs.add(n.longValue());
            }
            TypedQuery<ComprobantesEmitidos> q2 = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f"
                            + " LEFT JOIN FETCH f.resumen"
                            + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.emisor"
                            + " WHERE f.id IN :ids",
                    ComprobantesEmitidos.class);
            q2.setParameter("ids", longs);
            return new PaginaEmitidos(q2.getResultList(), total);
        } catch (PersistenceException e) {
            LOG.warn("Error listando comprobantes emitidos paginados: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listPagedEmitidos() | despues=" + e.getMessage());
            return new PaginaEmitidos(java.util.List.of(), total);
        }
    }
      
    public @Nullable List<ComprobantesEmitidos> listAllEmitidosBy(@Nonnull Usuarios user) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc " +
                "WHERE f.user = :user",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user.getUsername());
            List<ComprobantesEmitidos> result = query.getResultList();
            LOG.info("Query returned " + result.size() + " invoices for user " + user.getUsername() + " | source=ComprobantesEmitidosService.listAllEmitidosBy()");
            return result;
        } catch (PersistenceException e) {
            LOG.warn("Error listing entities for user: " + e.getMessage() + " | source=ComprobantesEmitidosService.listAllEmitidosBy() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nullable List<ComprobantesEmitidos> listAllEmitidosBy(@Nonnull Usuarios user, @Nonnull Date startDate, @Nonnull Date endDate) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH ld.codigosComerciales cc " +
                "LEFT JOIN FETCH ld.descuentos des " +
                "LEFT JOIN FETCH ld.impuestos imp " +
                "LEFT JOIN FETCH d.otrosCargos oc " +
                "WHERE f.user = :user " +
                "AND f.encabezado.fechaEmision BETWEEN :startDate AND :endDate",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user.getUsername());
            query.setParameter("startDate", startDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            query.setParameter("endDate", endDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime());
            List<ComprobantesEmitidos> result = query.getResultList();
            LOG.info("Query returned " + result.size() + " invoices for user " + user.getUsername() + " between " + startDate + " and " + endDate + " | source=ComprobantesEmitidosService.listAllEmitidosBy()");
            return result;
        } catch (PersistenceException e) {
            LOG.warn("Error listing entities for user with date range: " + e.getMessage() + " | source=ComprobantesEmitidosService.listAllEmitidosBy() | despues=" + e.getMessage());
            return null;
        }
    }

    public boolean findByNumeroConsecutivo(@Nonnull String numeroConsecutivo) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery("SELECT f FROM ComprobantesEmitidos f WHERE f.encabezado.numeroConsecutivo = :numeroConsecutivo", ComprobantesEmitidos.class);
            query.setParameter("numeroConsecutivo", numeroConsecutivo);
            // Attempt to get a single result
            ComprobantesEmitidos factura = query.getSingleResult();
            // If a result is found, return true
            return factura != null;
        } catch (NoResultException e) {
            // If no result is found, catch the NoResultException and return false
            return false;
        } catch (PersistenceException e) {
            LOG.warn("Error finding entity by numeroConsecutivo: " + e.getMessage() + " | source=ComprobantesEmitidosService.findByNumeroConsecutivo() | despues=" + e.getMessage());
            return false;
        }
    }
    
    public @Nullable ComprobantesEmitidos findLastTransactionByUser(@Nonnull Usuarios user) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.detalles d " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user.getUsername());
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (PersistenceException e) {
            LOG.warn("Error finding last transaction for user: " + e.getMessage() + " | source=ComprobantesEmitidosService.findLastTransactionByUser() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nonnull List<ComprobantesEmitidos> findComprobantesAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<ComprobantesEmitidos> cq = cb.createQuery(ComprobantesEmitidos.class);
        Root<ComprobantesEmitidos> comprobanteRoot = cq.from(ComprobantesEmitidos.class);
        Join<ComprobantesEmitidos, Encabezado> encabezado = comprobanteRoot.join("encabezado");

        Predicate datePredicate = cb.greaterThan(encabezado.get("fechaEmision"), fecha);
        cq.where(datePredicate);

        TypedQuery<ComprobantesEmitidos> query = em.createQuery(cq);
        return query.getResultList();
    }

    /**
 * Comprobantes emitidos del rango, solo los ELECTRONICOS (con clave).
 *
 * <p>La declaracion mensual de IVA solo puede sumar comprobantes
 *electronicos: el historico importado del sistema viejo no es fiscal y hace
 * que la pagina cargue cientos de miles de filas.</p>
 */
public @Nullable List<ComprobantesEmitidos> listByDateRangeConClave(@Nonnull Date start, @Nonnull Date end) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "AND e.clave IS NOT NULL " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesEmitidos.class
            );
            LocalDateTime startLdt = start.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime endLdt = end.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            query.setParameter("start", startLdt);
            query.setParameter("end", endLdt);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing by date range: " + e.getMessage() + " | source=ComprobantesEmitidosService.listByDateRangeConClave() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> listByDateRange(@Nonnull Date start, @Nonnull Date end) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE e.fechaEmision BETWEEN :start AND :end " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesEmitidos.class
            );
            LocalDateTime startLdt = start.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            LocalDateTime endLdt = end.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            query.setParameter("start", startLdt);
            query.setParameter("end", endLdt);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing by date range: " + e.getMessage() + " | source=ComprobantesEmitidosService.listByDateRange() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasPendientes() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND (e.estado IS NULL OR e.estado = 'PENDIENTE') " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding facturas pendientes: " + e.getMessage() + " | source=ComprobantesEmitidosService.findFacturasPendientes() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasAceptadas() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND e.estado = 'ACEPTADO' " +
                "AND e.clave IS NOT NULL " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding facturas aceptadas: " + e.getMessage() + " | source=ComprobantesEmitidosService.findFacturasAceptadas() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasRechazadas() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND e.estado = 'RECHAZADO' " +
                "AND e.clave IS NOT NULL " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding facturas rechazadas: " + e.getMessage() + " | source=ComprobantesEmitidosService.findFacturasRechazadas() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nonnull Long countFacturasPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                "LEFT JOIN f.encabezado e " +
                "WHERE f.status = true " +
                "AND (e.estado IS NULL OR e.estado = 'PENDIENTE') " +
                "AND e.clave IS NOT NULL",
                Long.class
            );
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting facturas pendientes: " + e.getMessage() + " | source=ComprobantesEmitidosService.countFacturasPendientes() | despues=" + e.getMessage());
            return 0L;
        }
    }

    public @Nonnull Long countFacturasAceptadas() {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                            "LEFT JOIN f.encabezado e " +
                            "WHERE f.status = true " +
                            "AND e.estado = 'ACEPTADO' " +
                            "AND e.clave IS NOT NULL",
                    Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting facturas aceptadas: " + e.getMessage() + " | source=ComprobantesEmitidosService.countFacturasAceptadas() | despues=" + e.getMessage());
            return 0L;
        }
    }

    public @Nonnull Long countFacturasRechazadas() {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                            "LEFT JOIN f.encabezado e " +
                            "WHERE f.status = true " +
                            "AND e.estado = 'RECHAZADO' " +
                            "AND e.clave IS NOT NULL",
                    Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting facturas rechazadas: " + e.getMessage() + " | source=ComprobantesEmitidosService.countFacturasRechazadas() | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * Comprobantes de un bucket de consultas, acotados y filtrados en SQL.
     *
     * <p>Solo documentos electronicos (con clave): el historico importado nunca
     * fue a Hacienda. Antes se cargaba el bucket completo y se mapeaba en Java
     * (con una consulta por receptor): con miles de comprobantes la pagina de
     * consultas tardaba 10 s.</p>
     *
     * @param bucket  "pendientes", "aceptadas" o "rechazadas"
     * @param offset  primera fila (0-based)
     * @param max     numero maximo de filas
     * @param q       filtro de busqueda (consecutivo, cliente, total, estado)
     */
    public @Nonnull List<ComprobantesEmitidos> findFacturasParaTabla(@Nonnull String bucket, int offset,
                                                                     int max, @Nullable String q) {
        String estado = switch (bucket) {
            case "aceptadas" -> " AND e.estado = 'ACEPTADO'";
            case "rechazadas" -> " AND e.estado = 'RECHAZADO'";
            default -> " AND (e.estado IS NULL OR e.estado = 'PENDIENTE')";
        };
        StringBuilder sql = new StringBuilder(
                "SELECT f.id FROM comprobantesemitidos f "
                        + "JOIN encabezado e ON e.id = f.encabezado_id "
                        + "LEFT JOIN resumen_factura r ON r.id = f.resumen_id "
                        + "LEFT JOIN receptor rc ON rc.id = e.receptor_id "
                        + "WHERE f.status = true AND e.clave IS NOT NULL")
                .append(estado);
        String texto = q == null ? "" : q.trim();
        if (!texto.isEmpty()) {
            sql.append(" AND (UPPER(COALESCE(e.numero_consecutivo,'')) LIKE :q")
                    .append(" OR UPPER(COALESCE(rc.nombre,'')) LIKE :q")
                    .append(" OR UPPER(COALESCE(e.motivo_rechazo,'')) LIKE :q")
                    .append(" OR UPPER(COALESCE(f.haciendaestado,'')) LIKE :q")
                    .append(" OR COALESCE(e.fecha_emision::text,'') LIKE :q")
                    .append(" OR COALESCE(r.total_comprobante::text,'') LIKE :q)");
        }
        sql.append(" ORDER BY e.fecha_emision DESC NULLS LAST, f.id DESC LIMIT :lim OFFSET :off");
        try {
            var query = em.createNativeQuery(sql.toString());
            if (!texto.isEmpty()) {
                query.setParameter("q", "%" + texto.toUpperCase(java.util.Locale.ROOT) + "%");
            }
            query.setParameter("lim", Math.max(max, 1));
            query.setParameter("off", Math.max(offset, 0));
            @SuppressWarnings("unchecked")
            List<Number> ids = (List<Number>) query.getResultList();
            if (ids.isEmpty()) {
                return java.util.List.of();
            }
            List<Long> longs = new java.util.ArrayList<>(ids.size());
            for (Number n : ids) {
                longs.add(n.longValue());
            }
            TypedQuery<ComprobantesEmitidos> q2 = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f"
                            + " LEFT JOIN FETCH f.resumen"
                            + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.emisor LEFT JOIN FETCH e.receptor"
                            + " WHERE f.id IN :ids",
                    ComprobantesEmitidos.class);
            q2.setParameter("ids", longs);
            return q2.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing comprobantes de consulta: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.findFacturasParaTabla() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    // --- Hacienda-dashboard counters (by haciendaEstado, not encabezado estado) ---
    public @Nonnull Long countHaciendaAceptadas() {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                            "WHERE UPPER(f.haciendaEstado) = 'ACEPTADO'",
                    Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting hacienda aceptadas: " + e.getMessage() + " | source=ComprobantesEmitidosService.countHaciendaAceptadas() | despues=" + e.getMessage());
            return 0L;
        }
    }

    public @Nonnull Long countHaciendaRechazadas() {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                            "WHERE UPPER(f.haciendaEstado) = 'RECHAZADO'",
                    Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting hacienda rechazadas: " + e.getMessage() + " | source=ComprobantesEmitidosService.countHaciendaRechazadas() | despues=" + e.getMessage());
            return 0L;
        }
    }

    public @Nonnull Long countHaciendaPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                            "WHERE f.haciendaEstado IS NULL " +
                            "OR f.haciendaEstado = '' " +
                            "OR UPPER(f.haciendaEstado) = 'PENDIENTE'",
                    Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting hacienda pendientes: " + e.getMessage() + " | source=ComprobantesEmitidosService.countHaciendaPendientes() | despues=" + e.getMessage());
            return 0L;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Instantánea del dashboard / landing de Tributación (agregados SQL)
    //
    // El dashboard hidrataba el grafo completo de comprobantes con
    // listAllConClave() para contar en Java: con 211k facturas eso pasaba de
    // 25 s. Todo lo que es contador o monto se resuelve ahora con COUNT/SUM/
    // GROUP BY y las dos listas acotadas viajan con LIMIT.
    // ════════════════════════════════════════════════════════════════════

    /** Un día de la serie "Últimos 7 días" del dashboard. */
    public record SerieDia(java.time.LocalDate dia, long emitidos, long aceptados) { }

    /**
     * Instantánea del dashboard de Tributación ({@code /app/tributacion} y
     * {@code /app/tributacion/dashboard}).
     *
     * <p>Mismos números y mismas listas que la versión que materializaba
     * {@link #listAllConClave()}: el universo son los comprobantes con clave
     * ({@code encabezado.clave IS NOT NULL}), y el estado se normaliza sobre
     * {@code comprobante.haciendaEstado} igual que el código Java
     * ({@code equalsIgnoreCase}, con {@code null}/vacío como PENDIENTE).</p>
     *
     * @param total            comprobantes con clave
     * @param aceptados        {@code haciendaEstado = ACEPTADO}
     * @param rechazados       {@code haciendaEstado = RECHAZADO}
     * @param pendientes       {@code haciendaEstado} null, vacío o PENDIENTE
     * @param enviadosSinRespuesta {@code haciendaEstado = ENVIADO}
     * @param emitidosHoy      con {@code fechaEmision} en el día de negocio
     * @param montoAceptados   suma de {@code totalComprobante} de los aceptados
     * @param montoPendientes  suma de {@code totalComprobante} de los pendientes
     * @param ultimaEmision    mayor {@code fechaEmision} (null sin emisiones)
     * @param serie            días con emisiones dentro de la ventana de 7 días
     * @param pendientesEnvio  hasta {@code limitePendientes} pendientes, más recientes primero
     * @param rechazadosRecientes hasta {@code limiteRechazados} rechazados por fecha de respuesta
     */
    public record DashboardTributacion(
            long total,
            long aceptados,
            long rechazados,
            long pendientes,
            long enviadosSinRespuesta,
            long emitidosHoy,
            long aceptadosHoy,
            long rechazadosHoy,
            long emitidosUltimos7,
            java.math.BigDecimal montoAceptados,
            java.math.BigDecimal montoPendientes,
            java.time.LocalDate ultimaEmision,
            List<SerieDia> serie,
            List<ComprobantesEmitidos> pendientesEnvio,
            List<ComprobantesEmitidos> rechazadosRecientes) { }

    /**
     * Contadores, montos y serie diaria en agregados SQL + las dos listas
     * acotadas del dashboard.
     *
     * <p>Los límites de fecha llegan ya calculados por el llamador (la zona de
     * negocio es {@code America/Costa_Rica} y vive en el recurso): así el
     * {@code BETWEEN} de {@code fechaEmision} es idéntico al que hacía el
     * filtrado en Java y no depende de la zona del host ni de la sesión SQL.
     * La serie se agrupa con {@code to_char} sobre un {@code timestamp sin
     * zona}, que no aplica conversión de zona horaria.</p>
     *
     * @param inicioHoy    inicio del día de negocio actual
     * @param finHoy       inicio del día siguiente (exclusivo)
     * @param inicioSerie  inicio de la ventana de 7 días (inclusive)
     * @param limitePendientes filas máximas de "Pendientes de envío"
     * @param limiteRechazados filas máximas de "Rechazados recientes"
     */
    public @Nonnull DashboardTributacion dashboardTributacion(
            @Nonnull LocalDateTime inicioHoy,
            @Nonnull LocalDateTime finHoy,
            @Nonnull LocalDateTime inicioSerie,
            int limitePendientes,
            int limiteRechazados) {
        long total = 0L;
        long aceptados = 0L;
        long rechazados = 0L;
        long pendientes = 0L;
        long enviadosSinRespuesta = 0L;
        long emitidosHoy = 0L;
        long aceptadosHoy = 0L;
        long rechazadosHoy = 0L;
        java.math.BigDecimal montoAceptados = java.math.BigDecimal.ZERO;
        java.math.BigDecimal montoPendientes = java.math.BigDecimal.ZERO;
        java.time.LocalDate ultimaEmision = null;
        try {
            var query = em.createNativeQuery(
                    "SELECT count(*),"
                            + " count(*) FILTER (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'ACEPTADO'),"
                            + " count(*) FILTER (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'RECHAZADO'),"
                            + " count(*) FILTER (WHERE f.haciendaestado IS NULL"
                            + "     OR f.haciendaestado = ''"
                            + "     OR UPPER(f.haciendaestado) = 'PENDIENTE'),"
                            + " count(*) FILTER (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'ENVIADO'),"
                            + " count(*) FILTER (WHERE e.fecha_emision >= :inicioHoy"
                            + "     AND e.fecha_emision < :finHoy),"
                            + " count(*) FILTER (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'ACEPTADO'"
                            + "     AND e.fecha_emision >= :inicioHoy AND e.fecha_emision < :finHoy),"
                            + " count(*) FILTER (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'RECHAZADO'"
                            + "     AND e.fecha_emision >= :inicioHoy AND e.fecha_emision < :finHoy),"
                            + " COALESCE(sum(r.total_comprobante) FILTER"
                            + "     (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'ACEPTADO'), 0),"
                            + " COALESCE(sum(r.total_comprobante) FILTER"
                            + "     (WHERE f.haciendaestado IS NULL"
                            + "         OR f.haciendaestado = ''"
                            + "         OR UPPER(f.haciendaestado) = 'PENDIENTE'), 0),"
                            + " max(e.fecha_emision)"
                            + " FROM comprobantesemitidos f"
                            + " JOIN encabezado e ON e.id = f.encabezado_id"
                            + " LEFT JOIN resumen_factura r ON r.id = f.resumen_id"
                            + " WHERE e.clave IS NOT NULL");
            query.setParameter("inicioHoy", inicioHoy);
            query.setParameter("finHoy", finHoy);
            Object[] fila = (Object[]) query.getSingleResult();
            total = numero(fila[0]);
            aceptados = numero(fila[1]);
            rechazados = numero(fila[2]);
            pendientes = numero(fila[3]);
            enviadosSinRespuesta = numero(fila[4]);
            emitidosHoy = numero(fila[5]);
            aceptadosHoy = numero(fila[6]);
            rechazadosHoy = numero(fila[7]);
            montoAceptados = decimal(fila[8]);
            montoPendientes = decimal(fila[9]);
            ultimaEmision = aFecha(fila[10]);
        } catch (PersistenceException e) {
            LOG.warn("Error calculando los contadores del dashboard de tributación: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.dashboardTributacion() | despues=" + e.getMessage());
        }

        List<SerieDia> serie = new java.util.ArrayList<>();
        long emitidosUltimos7 = 0L;
        try {
            var query = em.createNativeQuery(
                    "SELECT to_char(e.fecha_emision, 'YYYY-MM-DD'),"
                            + " count(*),"
                            + " count(*) FILTER"
                            + "     (WHERE UPPER(COALESCE(f.haciendaestado,'')) = 'ACEPTADO')"
                            + " FROM comprobantesemitidos f"
                            + " JOIN encabezado e ON e.id = f.encabezado_id"
                            + " WHERE e.clave IS NOT NULL"
                            + " AND e.fecha_emision >= :inicioSerie"
                            + " AND e.fecha_emision < :finHoy"
                            + " GROUP BY 1 ORDER BY 1");
            query.setParameter("inicioSerie", inicioSerie);
            query.setParameter("finHoy", finHoy);
            @SuppressWarnings("unchecked")
            List<Object[]> filas = (List<Object[]>) query.getResultList();
            for (Object[] f : filas) {
                java.time.LocalDate dia = f[0] == null ? null : java.time.LocalDate.parse(String.valueOf(f[0]));
                if (dia == null) {
                    continue;
                }
                long emitidos = numero(f[1]);
                serie.add(new SerieDia(dia, emitidos, numero(f[2])));
                emitidosUltimos7 += emitidos;
            }
        } catch (PersistenceException e) {
            LOG.warn("Error calculando la serie de 7 días del dashboard: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.dashboardTributacion() | despues=" + e.getMessage());
        }

        return new DashboardTributacion(total, aceptados, rechazados, pendientes,
                enviadosSinRespuesta, emitidosHoy, aceptadosHoy, rechazadosHoy,
                emitidosUltimos7, montoAceptados, montoPendientes, ultimaEmision,
                serie,
                listarPendientesEnvio(limitePendientes),
                listarRechazadosRecientes(limiteRechazados));
    }

    /** "Pendientes de envío" del dashboard: acotado, más recientes primero. */
    private @Nonnull List<ComprobantesEmitidos> listarPendientesEnvio(int max) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f"
                            + " LEFT JOIN FETCH f.resumen"
                            + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.receptor"
                            + " WHERE e.clave IS NOT NULL"
                            + " AND (f.haciendaEstado IS NULL"
                            + "     OR f.haciendaEstado = ''"
                            + "     OR UPPER(f.haciendaEstado) = 'PENDIENTE')"
                            + " ORDER BY e.fechaEmision DESC NULLS LAST, f.id DESC",
                    ComprobantesEmitidos.class);
            query.setMaxResults(Math.max(max, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listando pendientes de envío del dashboard: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listarPendientesEnvio() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    /** "Rechazados recientes" del dashboard: acotado, última respuesta primero. */
    private @Nonnull List<ComprobantesEmitidos> listarRechazadosRecientes(int max) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f"
                            + " LEFT JOIN FETCH f.resumen"
                            + " LEFT JOIN FETCH f.encabezado e LEFT JOIN FETCH e.receptor"
                            + " WHERE e.clave IS NOT NULL"
                            + " AND UPPER(f.haciendaEstado) = 'RECHAZADO'"
                            + " ORDER BY f.haciendaFechaRespuesta DESC NULLS LAST, f.id DESC",
                    ComprobantesEmitidos.class);
            query.setMaxResults(Math.max(max, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listando rechazados recientes del dashboard: " + e.getMessage()
                    + " | source=ComprobantesEmitidosService.listarRechazadosRecientes() | despues=" + e.getMessage());
            return java.util.List.of();
        }
    }

    private static long numero(@Nullable Object valor) {
        return valor instanceof Number n ? n.longValue() : 0L;
    }

    private static @Nonnull java.math.BigDecimal decimal(@Nullable Object valor) {
        if (valor instanceof java.math.BigDecimal b) {
            return b;
        }
        return valor instanceof Number n ? new java.math.BigDecimal(n.toString()) : java.math.BigDecimal.ZERO;
    }

    private static @Nullable java.time.LocalDate aFecha(@Nullable Object valor) {
        if (valor instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (valor instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (valor instanceof java.time.LocalDate ld) {
            return ld;
        }
        if (valor instanceof java.time.OffsetDateTime odt) {
            return odt.toLocalDateTime().toLocalDate();
        }
        return null;
    }

    public @Nullable List<ComprobantesEmitidos> findByClave(@Nonnull String clave) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f WHERE f.haciendaClave = :clave",
                ComprobantesEmitidos.class
            );
            query.setParameter("clave", clave);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding by clave: " + e.getMessage() + " | source=ComprobantesEmitidosService.findByClave() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nonnull List<ComprobantesEmitidos> findFacturasPendientesEnvio() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.detalles d " +
                "LEFT JOIN FETCH d.lineasDetalle ld " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.status = true " +
                "AND f.haciendaEstado = 'PENDIENTE' " +
                "ORDER BY e.fechaEmision ASC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding facturas pendientes de envio: " + e.getMessage()
                + " | source=ComprobantesEmitidosService.findFacturasPendientesEnvio()"
                + " | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasParaVerificarEstado() {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE f.status = true " +
                "AND f.haciendaEstado = 'ENVIADO' " +
                "AND e.clave IS NOT NULL " +
                "AND (f.haciendaFechaRespuesta IS NULL " +
                "     OR f.haciendaFechaEnvio IS NULL " +
                "     OR f.haciendaFechaRespuesta < f.haciendaFechaEnvio) " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding facturas para verificar estado: " + e.getMessage() + " | source=ComprobantesEmitidosService.findFacturasParaVerificarEstado() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable List<ComprobantesEmitidos> findFacturasSinRespuesta3Horas() {
        try {
            LocalDateTime hace3Horas = LocalDateTime.now().minusHours(3);
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "WHERE f.status = true " +
                "AND f.haciendaEstado = 'ENVIADO' " +
                "AND e.clave IS NOT NULL " +
                "AND f.haciendaFechaEnvio IS NOT NULL " +
                "AND f.haciendaFechaEnvio < :hace3Horas " +
                "AND (f.haciendaFechaRespuesta IS NULL OR f.haciendaFechaRespuesta < f.haciendaFechaEnvio) " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            query.setParameter("hace3Horas", hace3Horas);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error finding facturas sin respuesta 3h: " + e.getMessage() + " | source=ComprobantesEmitidosService.findFacturasSinRespuesta3Horas() | despues=" + e.getMessage());
            return null;
        }
    }

    // --- Reporte "Facturas Emitidas": DB-side filtering, sorting and paging ---
    private static String facturasReporteWhere(@Nullable String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            return "";
        }
        return " WHERE (" +
                "LOWER(COALESCE(e.numeroConsecutivo, '')) LIKE :needle OR " +
                "LOWER(COALESCE(e.condicionVenta, '')) LIKE :needle OR " +
                "LOWER(COALESCE(f.user, '')) LIKE :needle OR " +
                "LOWER(COALESCE(f.haciendaEstado, '')) LIKE :needle OR " +
                "LOWER(COALESCE(em.nombre, '')) LIKE :needle OR " +
                "LOWER(COALESCE(FUNCTION('to_char', e.fechaEmision, 'YYYY-MM-DD HH24:MI:SS'), '')) LIKE :needle" +
                ")";
    }

    private static void bindFacturasReporteNeedle(@Nonnull TypedQuery<?> query, @Nullable String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        if (!needle.isEmpty()) {
            query.setParameter("needle", "%" + needle + "%");
        }
    }

    private static String facturasReporteOrder(@Nullable String sort, @Nullable String dir) {
        String column = switch (sort == null ? "" : sort) {
            case "consecutivo" -> "e.numeroConsecutivo";
            case "fecha" -> "e.fechaEmision";
            case "emisor" -> "f.user";
            case "condicion" -> "e.condicionVenta";
            case "estado" -> "f.haciendaEstado";
            case "total" -> "r.totalComprobante";
            default -> null;
        };
        if (column == null) {
            return "";
        }
        boolean desc = "desc".equalsIgnoreCase(dir);
        return " ORDER BY " + column + (desc ? " DESC" : " ASC") + " NULLS FIRST";
    }

    public @Nonnull Long countFacturasReporte(@Nullable String q) {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(f) FROM ComprobantesEmitidos f " +
                            "LEFT JOIN f.encabezado e " +
                            "LEFT JOIN e.emisor em " +
                            facturasReporteWhere(q),
                    Long.class);
            bindFacturasReporteNeedle(query, q);
            Long result = query.getSingleResult();
            return result != null ? result : 0L;
        } catch (PersistenceException e) {
            LOG.warn("Error counting facturas reporte: " + e.getMessage() + " | source=ComprobantesEmitidosService.countFacturasReporte() | despues=" + e.getMessage());
            return 0L;
        }
    }

    public @Nullable List<ComprobantesEmitidos> listFacturasReportePage(
            @Nullable String sort, @Nullable String dir, @Nullable String q, int offset, int pageSize) {
        try {
            // Sin DISTINCT: resumen, encabezado y emisor son relaciones a uno,
            // no duplican filas. Con el historico completo (750 mil
            // comprobantes) el DISTINCT obligaba a ordenar y deduplicar todo
            // el conjunto antes de aplicar el LIMIT.
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                    "SELECT f FROM ComprobantesEmitidos f " +
                            "LEFT JOIN FETCH f.resumen r " +
                            "LEFT JOIN FETCH f.encabezado e " +
                            "LEFT JOIN FETCH e.emisor em " +
                            facturasReporteWhere(q) +
                            facturasReporteOrder(sort, dir),
                    ComprobantesEmitidos.class);
            bindFacturasReporteNeedle(query, q);
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(pageSize, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing facturas reporte page: " + e.getMessage() + " | source=ComprobantesEmitidosService.listFacturasReportePage() | despues=" + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

}
