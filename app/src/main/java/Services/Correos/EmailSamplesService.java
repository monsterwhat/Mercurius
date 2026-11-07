package Services.Correos;

import Models.ComprobantesEmitidos;
import Services.ComprobantesEmitidosService;
import Services.DirectoryService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.File;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Datos REALES para las muestras de correo (plantillas y reportes).
 *
 * <p>Los reportes de muestra salen de la base (últimos movimientos, facturas,
 * artículos, departamentos, familias y stock), no de filas inventadas. El
 * correo de la plantilla de factura adjunta un PDF y un XML reales del último
 * comprobante emitido; cada reporte adjunta su XLSX generado con esas filas.</p>
 */
@ApplicationScoped
public class EmailSamplesService {

    private static final Logger LOG = Logger.getLogger(EmailSamplesService.class);
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @PersistenceContext
    @Nonnull
    EntityManager em;

    @Inject
    @Nonnull
    DirectoryService dirService;

    @Inject
    @Nonnull
    ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject
    @Nonnull
    DocumentoStrategyFactory strategyFactory;

    /** Una tabla de muestra: título, cabeceras y filas ya formateadas. */
    public record ReporteSample(String titulo, String[] headers, List<String[]> filas) {
    }

    @Nonnull
    public List<ReporteSample> reportes() {
        List<ReporteSample> lista = new ArrayList<>();
        lista.add(new ReporteSample("Movimientos de inventario",
                new String[] {"Fecha", "Artículo", "Tipo", "Cantidad"},
                filas("SELECT i.fechamovimiento, a.nombre, i.tipomovimiento, i.cantidad "
                        + "FROM inventario i JOIN articulos a ON a.codigo = i.articulo_codigo "
                        + "ORDER BY i.fechamovimiento DESC", 4)));
        lista.add(new ReporteSample("Facturación",
                new String[] {"Consecutivo", "Fecha", "Total"},
                filas("SELECT e.numero_consecutivo, e.fecha_emision, r.total_venta "
                        + "FROM comprobantesemitidos c "
                        + "JOIN encabezado e ON e.id = c.encabezado_id "
                        + "JOIN resumen_factura r ON r.id = c.resumen_id "
                        + "WHERE c.status = true ORDER BY e.fecha_emision DESC", 4)));
        lista.add(new ReporteSample("Artículos",
                new String[] {"Artículo", "Stock", "Departamento"},
                filas("SELECT a.nombre, s.stock, COALESCE(d.nombre, '—') "
                        + "FROM articulos a JOIN articulostock s ON s.codigobarra = a.codigobarra "
                        + "LEFT JOIN departamento d ON d.id = a.departamento_id "
                        + "WHERE s.stock >= 0 ORDER BY s.stock DESC", 4)));
        lista.add(new ReporteSample("Departamentos",
                new String[] {"Departamento", "Artículos"},
                filas("SELECT d.nombre, count(a.codigo) FROM departamento d "
                        + "LEFT JOIN articulos a ON a.departamento_id = d.id "
                        + "GROUP BY d.nombre ORDER BY count(a.codigo) DESC", 4)));
        lista.add(new ReporteSample("Familias",
                new String[] {"Familia", "Artículos"},
                filas("SELECT f.nombre, count(a.codigo) FROM familia f "
                        + "LEFT JOIN articulos a ON a.familia_id = f.id "
                        + "GROUP BY f.nombre ORDER BY count(a.codigo) DESC", 4)));
        lista.add(new ReporteSample("Inventarios",
                new String[] {"Artículo", "Stock", "Departamento"},
                filas("SELECT a.nombre, s.stock, COALESCE(d.nombre, '—') "
                        + "FROM articulos a JOIN articulostock s ON s.codigobarra = a.codigobarra "
                        + "LEFT JOIN departamento d ON d.id = a.departamento_id "
                        + "WHERE s.stock >= 0 ORDER BY s.stock ASC", 4)));
        return lista;
    }

    private List<String[]> filas(String sql, int limite) {
        List<String[]> out = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(sql).setMaxResults(limite).getResultList();
            for (Object[] row : rows) {
                String[] celdas = new String[row.length];
                for (int i = 0; i < row.length; i++) {
                    celdas[i] = formatear(row[i]);
                }
                out.add(celdas);
            }
        } catch (RuntimeException e) {
            LOG.warn("No se pudieron leer datos reales para la muestra: " + e.getMessage(), e);
        }
        return out;
    }

    /** Monto con formato de Costa Rica: 1.234,56. */
    private static String moneda(Object valor) {
        if (valor instanceof Number n) {
            String s = String.format(java.util.Locale.US, "%,.2f", n.doubleValue());
            return s.replace(",", "X").replace(".", ",").replace("X", ".");
        }
        return formatear(valor);
    }

    private static String formatear(Object valor) {        if (valor == null) {
            return "—";
        }
        if (valor instanceof Timestamp ts) {
            return ts.toLocalDateTime().format(FECHA);
        }
        if (valor instanceof java.time.LocalDateTime ldt) {
            return ldt.format(FECHA);
        }
        if (valor instanceof java.sql.Date d) {
            return d.toLocalDate().format(FECHA);
        }
        if (valor instanceof BigDecimal bd) {
            return bd.stripTrailingZeros().toPlainString();
        }
        if (valor instanceof Number n) {
            return n.toString();
        }
        String texto = valor.toString();
        return texto.length() > 60 ? texto.substring(0, 57) + "…" : texto;
    }

    /** HTML de la tabla de muestra (estilos en línea). */
    @Nonnull
    public String html(@Nonnull ReporteSample reporte) {
        StringBuilder th = new StringBuilder();
        for (String h : reporte.headers()) {
            th.append("<th style=\"padding:8px 10px;text-align:left;font-size:12px;color:#6b7280\">")
              .append(h).append("</th>");
        }
        StringBuilder tr = new StringBuilder();
        for (String[] fila : reporte.filas()) {
            tr.append("<tr>");
            for (String celda : fila) {
                tr.append("<td style=\"padding:8px 10px;border-bottom:1px solid #eef2f7;color:#374151\">")
                  .append(celda).append("</td>");
            }
            tr.append("</tr>");
        }
        if (reporte.filas().isEmpty()) {
            tr.append("<tr><td style=\"padding:12px;color:#6b7280\" colspan=\"")
              .append(reporte.headers().length).append("\">Sin datos disponibles.</td></tr>");
        }
        return "<h2 style=\"margin:0 0 8px;color:#0b3b8c;font-size:19px\">"
                + reporte.titulo() + "</h2>"
                + "<p style=\"margin:0 0 16px;color:#1f2937;font-size:14px\">"
                + "Datos reales del sistema al momento del envío.</p>"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"border:1px solid #e5e9f2;border-radius:8px\">"
                + "<tr style=\"background:#f8fafc\">" + th + "</tr>" + tr + "</table>";
    }

    /**
     * Variables de las plantillas con datos reales: cliente, último
     * comprobante y artículos con stock bajo.
     */
    @Nonnull
    public java.util.Map<String, String> variablesReales() {
        java.util.Map<String, String> v = new java.util.LinkedHashMap<>();
        v.put("cliente", "Cliente de ejemplo");
        v.put("consecutivo", "00100001010000000001");
        v.put("fecha", "04/11/2026");
        v.put("total", "₡0,00");
        v.put("cantidad", "3");
        v.put("detalle", "Sin artículos con stock bajo.");
        v.put("reporte", "Inventarios");
        v.put("periodo", "Noviembre 2026");
        v.put("asunto", "Aviso importante");
        v.put("mensaje", "Este es un texto de ejemplo para ver cómo queda el correo.");
        v.put("enlace", "https://mercurius.tamarindorents.com/");
        try {
            @SuppressWarnings("unchecked")
            List<String> clientes = em.createNativeQuery(
                    "SELECT name FROM clients WHERE name IS NOT NULL ORDER BY code LIMIT 1")
                    .getResultList();
            if (!clientes.isEmpty() && clientes.get(0) != null) {
                v.put("cliente", clientes.get(0));
            }
        } catch (RuntimeException e) {
            LOG.warn("Muestra: no se pudo leer un cliente real: " + e.getMessage(), e);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> docs = em.createNativeQuery(
                    "SELECT e.numero_consecutivo, e.fecha_emision, r.total_venta "
                            + "FROM comprobantesemitidos c "
                            + "JOIN encabezado e ON e.id = c.encabezado_id "
                            + "JOIN resumen_factura r ON r.id = c.resumen_id "
                            + "WHERE c.status = true ORDER BY e.fecha_emision DESC LIMIT 1")
                    .getResultList();
            if (!docs.isEmpty()) {
                Object[] d = docs.get(0);
                v.put("consecutivo", formatear(d[0]));
                v.put("fecha", formatear(d[1]));
                v.put("total", "₡" + moneda(d[2]));
            }
        } catch (RuntimeException e) {
            LOG.warn("Muestra: no se pudo leer el último comprobante: " + e.getMessage(), e);
        }
        try {
            @SuppressWarnings("unchecked")
            List<Object[]> bajos = em.createNativeQuery(
                    "SELECT a.nombre, s.stock FROM articulos a "
                            + "JOIN articulostock s ON s.codigobarra = a.codigobarra "
                            + "WHERE s.stock >= 0 AND s.stock <= 5 "
                            + "ORDER BY s.stock ASC LIMIT 4")
                    .getResultList();
            if (!bajos.isEmpty()) {
                StringBuilder detalle = new StringBuilder();
                for (Object[] b : bajos) {
                    if (detalle.length() > 0) {
                        detalle.append("<br>");
                    }
                    detalle.append(formatear(b[0])).append(" — quedan ")
                           .append(formatear(b[1]));
                }
                v.put("detalle", detalle.toString());
            }
        } catch (RuntimeException e) {
            LOG.warn("Muestra: no se pudo leer stock bajo real: " + e.getMessage(), e);
        }
        return v;
    }

    /** XLSX real del reporte, como adjunto de la muestra. */
    @Nonnull
    public List<File> adjuntosReporte(@Nonnull ReporteSample reporte) {        List<File> adjuntos = new ArrayList<>();
        try {
            List<Object[]> filas = new ArrayList<>();
            for (String[] fila : reporte.filas()) {
                filas.add(fila);
            }
            byte[] xlsx = Utils.ReportExporter.exportGenericExcel(
                    reporte.titulo(), reporte.headers(), filas);
            File file = File.createTempFile("muestra_" + reporte.titulo().replaceAll("\\W+", "_"), ".xlsx");
            java.nio.file.Files.write(file.toPath(), xlsx);
            adjuntos.add(file);
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warn("No se pudo generar el XLSX de muestra para " + reporte.titulo()
                    + ": " + e.getMessage(), e);
        }
        return adjuntos;
    }

    /**
     * PDF y XML reales del último comprobante emitido, para la plantilla de
     * factura (que promete ambos adjuntos).
     */
    @Nonnull
    public List<File> adjuntosFactura() {
        List<File> adjuntos = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            List<ComprobantesEmitidos> ultimos = em
                    .createQuery("SELECT c FROM ComprobantesEmitidos c ORDER BY c.id DESC")
                    .setMaxResults(1).getResultList();
            if (ultimos.isEmpty()) {
                return adjuntos;
            }
            ComprobantesEmitidos c = ultimos.get(0);
            File pdf = new File(dirService.getFacturasDirPath(),
                    "tiqueteElectronico_" + c.getId() + ".pdf");
            if (pdf.isFile()) {
                adjuntos.add(pdf);
            }
            if (c.getEncabezado() != null) {
                DocumentoStrategy strategy = strategyFactory.forCode(
                        c.getEncabezado().getCodigoDocumento());
                String xml = strategy.buildXml(c);
                File xmlFile = File.createTempFile("muestra_factura_" + c.getId(), ".xml");
                java.nio.file.Files.writeString(xmlFile.toPath(), xml);
                adjuntos.add(xmlFile);
            }
        } catch (RuntimeException | jakarta.xml.bind.JAXBException | java.io.IOException e) {
            LOG.warn("No se pudieron preparar los adjuntos de la factura de muestra: "
                    + e.getMessage(), e);
        }
        return adjuntos;
    }
}
