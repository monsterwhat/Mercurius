package Utils;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Inventario;
import Models.ProfitMarginSnapshot;
import Models.StockAlert;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Meta;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Static report builders (T17): produce report BYTES instead of writing to
 * servlet responses or JSF {@code ExternalContext}s.
 *
 * <p>Every method here is a byte-for-byte behavioral port of the generation
 * logic that used to live inline in the legacy JSF exporters:</p>
 * <ul>
 *   <li>{@link #exportArticulosPdf} ← Controllers/ArticulosController.exportPDF()</li>
 *   <li>{@link #exportInventarioPdf} ← Controllers/InventarioController.exportPDF()</li>
 *   <li>{@link #exportStockAlertsExcel} ← Utils/ExcelExporter.exportStockAlertsToExcel()
 *       (called from StockAlertController.exportToExcel())</li>
 *   <li>{@link #exportProfitMarginSnapshotsExcel} ← Utils/ExcelExporter.exportProfitMarginSnapshotsToExcel()
 *       (called from ProfitAnalysisController.exportToExcel())</li>
 * </ul>
 *
 * <p>The paragraph/cell sequences, sheet names, header captions and null
 * fallbacks are kept identical to the originals so the legacy methods can
 * delegate without any observable drift. Callers decide what to do with the
 * returned bytes (stream them, persist them, print them).</p>
 *
 * @author Al
 */
public final class ReportExporter {

    /** Shared OpenPDF page geometry used by both legacy PDF reports. */
    private static final Rectangle PDF_PAGE = new Rectangle(200f, 600f);

    private static final String SEPARADOR = "-------------------------------------";

    private ReportExporter() {}

    // ────────────────────────────── PDF (OpenPDF) ──────────────────────────────

    /**
     * "Reporte de Articulos Activos" — per-article block layout, ported 1:1
     * from ArticulosController.exportPDF(). Same NPE semantics as the legacy
     * loop (a null departamento with a non-null familia still throws).
     */
    @Nonnull
    public static byte[] exportArticulosPdf(@Nonnull List<Articulos> articulos) throws DocumentException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PDF_PAGE, 5, 5, 5, 5);
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();

        // Set font size
        Font font = new Font();
        font.setSize(8); // Set font size to 8 points

        // Add fancy title
        document.add(new Paragraph("Reporte de Articulos Activos", font));
        document.add(new Paragraph(SEPARADOR, font));

        int totalItems = articulos.size();
        int currentItem = 1;
        for (Articulos articulo : articulos) {
            String itemInfo = currentItem + "/" + totalItems;
            document.add(new Paragraph(itemInfo, font));

            document.add(new Paragraph("Art: " + articulo.getNombre(), font));
            if (articulo.getFamilia() != null) {
                document.add(new Paragraph("Dept: " + articulo.getDepartamento().getNombre() + " Fam: " + articulo.getFamilia().getNombre(), font));
            } else {
                document.add(new Paragraph("Dept: " + articulo.getDepartamento().getNombre() + " Familia sin definir", font));
            }
            if (articulo.getCodigoCabys() != null) {
                document.add(new Paragraph("%Imp: " + articulo.getCodigoCabys().getCodigo(), font));
            } else {
                document.add(new Paragraph("Cabys sin definir", font));
            }
            document.add(new Paragraph("Und: " + articulo.getUnidadMedida() + " UndComercial: " + articulo.getUnidadMedidaComercial(), font));

            // Show only the latest price, assuming it's the last in the list
            if (articulo.getPrecios() != null && !articulo.getPrecios().isEmpty()) {
                ArticuloPrecio latestPrecio = articulo.getPrecios().get(articulo.getPrecios().size() - 1);

                document.add(new Paragraph("Costo: " + latestPrecio.getPrecioCostoSinIVA(), font));
                document.add(new Paragraph("%Util: " + latestPrecio.getPorcentajeUtilidad(), font));
                document.add(new Paragraph("C/Util: " + latestPrecio.getPrecioConUtilidad(), font));
                document.add(new Paragraph("Venta: " + latestPrecio.getPrecioFinal(), font));
            } else {
                document.add(new Paragraph("No hay precios definidos", font));
            }

            document.add(new Paragraph("Creador: " + articulo.getUsuario().getUsername(), font));
            document.add(new Paragraph("\n", font));

            currentItem++;
        }
        document.close();
        return baos.toByteArray();
    }

    /**
     * "Reporte de Ajustes Activos" — per-inventory-movement block layout,
     * ported 1:1 from InventarioController.exportPDF().
     */
    @Nonnull
    public static byte[] exportInventarioPdf(@Nonnull List<Inventario> inventarios) throws DocumentException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PDF_PAGE, 5, 5, 5, 5);
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();

        // Set font size
        Font font = new Font();
        font.setSize(8); // Set font size to 5 points

        // Add fancy title
        document.add(new Paragraph("Reporte de Ajustes Activos", font));
        document.add(new Paragraph(SEPARADOR, font));

        int totalItems = inventarios.size();
        int currentItem = 1;
        for (Inventario inventario : inventarios) {
            String itemInfo = currentItem + "/" + totalItems;
            document.add(new Paragraph(itemInfo, font));

            document.add(new Paragraph("Art: " + inventario.getArticulo().getNombre(), font));
            document.add(new Paragraph("Can: " + inventario.getCantidad() + "  Tipo: " + inventario.getTipoMovimiento(), font));
            document.add(new Paragraph("Fecha: " + inventario.getFechaMovimiento(), font));
            document.add(new Paragraph("Creador: " + inventario.getUsuario().getUsername(), font));
            document.add(new Paragraph("\n", font));

            currentItem++;
        }

        document.close();
        return baos.toByteArray();
    }

    /**
     * Price-label sheet ("Etiquetas de Precio") for the /api/app/etiquetas
     * surface — one block per label unit, repeated {@code cantidades} times
     * per article (missing entry = 1, non-positive = skipped).
     *
     * <p>Parity note: the legacy etiquetas print action was a client-side
     * {@code window.print()} over the getFilteredArticulos() table — no
     * server-side PDF ever existed — so there are no legacy bytes to mirror.
     * This method is the canonical byte producer going forward and keeps the
     * legacy table's field set (codigo, nombre, codigo de barra, precio
     * final) in the house style of the two PDF ports above: same page
     * geometry, 8pt font, numbered blocks and the shared separator line.</p>
     */
    @Nonnull
    public static byte[] exportEtiquetasPdf(@Nonnull List<Articulos> articulos,
                                            @Nonnull Map<Long, Integer> cantidades) throws DocumentException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PDF_PAGE, 5, 5, 5, 5);
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();

        Font font = new Font();
        font.setSize(8);

        int totalUnidades = 0;
        for (Articulos articulo : articulos) {
            totalUnidades += unidadesDe(articulo, cantidades);
        }

        int unidadActual = 1;
        for (Articulos articulo : articulos) {
            int unidades = unidadesDe(articulo, cantidades);
            for (int i = 0; i < unidades; i++) {
                document.add(new Paragraph(unidadActual + "/" + totalUnidades, font));
                document.add(new Paragraph("Art: " + articulo.getNombre(), font));
                document.add(new Paragraph("Cod: " + articulo.getCodigo()
                        + "  CB: " + (articulo.getCodigoBarra() != null
                                ? articulo.getCodigoBarra() : "-"), font));
                // getLastPrecioArticulo() NPEs on price-less articles; guard it
                // with the same "-" fallback the etiquetas table used.
                BigDecimal precioFinal = articulo.getLastPrecio() != null
                        ? articulo.getLastPrecio().getPrecioFinal() : null;
                document.add(new Paragraph("Precio: "
                        + (precioFinal != null ? precioFinal : "-"), font));
                document.add(new Paragraph(SEPARADOR, font));
                unidadActual++;
            }
        }

        document.close();
        return baos.toByteArray();
    }

    /** Label units for one article (missing = 1, non-positive = 0). */
    private static int unidadesDe(@Nonnull Articulos articulo, @Nonnull Map<Long, Integer> cantidades) {
        Integer cantidad = cantidades.get(articulo.getCodigo());
        if (cantidad == null) {
            return 1;
        }
        return Math.max(cantidad, 0);
    }

    // ─────────────────────────── Excel (POI XSSF) ───────────────────────────

    /**
     * "Alertas de Stock" workbook — same sheet name, headers, cell order and
     * null fallbacks as ExcelExporter.exportStockAlertsToExcel().
     */
    @Nonnull
    public static byte[] exportStockAlertsExcel(@Nonnull List<StockAlert> stockAlerts) throws IOException {
        String[] headers = {"ID", "Artículo", "Código", "Tipo Alerta", "Cantidad Actual",
                          "Cantidad Mínima", "Sugerido Reordenar", "Departamento",
                          "Estado", "Fecha Creación", "Fecha Resolución", "Notas"};

        List<Object[]> rows = new ArrayList<>(stockAlerts.size());
        for (StockAlert alert : stockAlerts) {
            rows.add(new Object[]{
                alert.getId(),
                alert.getArticulo() != null ? alert.getArticulo().getNombre() : "",
                alert.getArticulo() != null ? alert.getArticulo().getCodigoBarra() : "",
                alert.getTipoAlerta(),
                alert.getCantidadActual() != null ? Double.valueOf(alert.getCantidadActual().doubleValue()) : Double.valueOf(0.0),
                alert.getCantidadMinima() != null ? Double.valueOf(alert.getCantidadMinima().doubleValue()) : Double.valueOf(0.0),
                alert.getSugeridoReordenar() != null ? Double.valueOf(alert.getSugeridoReordenar().doubleValue()) : Double.valueOf(0.0),
                alert.getDepartamento() != null ? alert.getDepartamento().getNombre() : "",
                alert.getEstado(),
                alert.getFechaCreacion() != null ? alert.getFechaCreacion().toString() : "",
                alert.getFechaResolucion() != null ? alert.getFechaResolucion().toString() : "",
                alert.getNotas() != null ? alert.getNotas() : ""
            });
        }

        return exportGenericExcel("Alertas de Stock", headers, rows);
    }

    /**
     * "Snapshots Márgenes" workbook — same sheet name, headers, cell order and
     * null fallbacks as ExcelExporter.exportProfitMarginSnapshotsToExcel().
     */
    @Nonnull
    public static byte[] exportProfitMarginSnapshotsExcel(@Nonnull List<ProfitMarginSnapshot> marginSnapshots) throws IOException {
        String[] headers = {"ID", "Fecha", "Departamento", "Familia", "% Margen Promedio",
                          "Total Utilidad", "Total Ventas", "Cant. Artículos"};

        List<Object[]> rows = new ArrayList<>(marginSnapshots.size());
        for (ProfitMarginSnapshot snapshot : marginSnapshots) {
            rows.add(new Object[]{
                snapshot.getId(),
                snapshot.getFechaSnapshot() != null ? snapshot.getFechaSnapshot().toString() : "",
                snapshot.getDepartamento() != null ? snapshot.getDepartamento() : "",
                snapshot.getFamilia() != null ? snapshot.getFamilia() : "",
                snapshot.getMargenPromedio() != null ? Double.valueOf(snapshot.getMargenPromedio().doubleValue()) : Double.valueOf(0.0),
                snapshot.getTotalUtilidad() != null ? Double.valueOf(snapshot.getTotalUtilidad().doubleValue()) : Double.valueOf(0.0),
                snapshot.getTotalVentas() != null ? Double.valueOf(snapshot.getTotalVentas().doubleValue()) : Double.valueOf(0.0),
                snapshot.getCantidadArticulos() != null ? Double.valueOf(snapshot.getCantidadArticulos().doubleValue()) : Double.valueOf(0.0)
            });
        }

        return exportGenericExcel("Snapshots Márgenes", headers, rows);
    }

    /**
     * Dataset-agnostic XLSX builder: one sheet, a styled header row and one
     * row per {@code Object[]} entry. Cell typing follows the legacy
     * ExcelExporter conventions — {@link Number} values become numeric cells,
     * {@link Boolean} values become boolean cells, everything else is written
     * as text and {@code null} becomes a blank cell.
     */
    @Nonnull
    public static byte[] exportGenericExcel(@Nonnull String sheetName, @Nonnull String[] headers, @Nonnull List<Object[]> rows) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(sheetName);

            createHeaderRow(sheet, headers);

            int rowNum = 1;
            for (Object[] rowData : rows) {
                Row row = sheet.createRow(rowNum++);
                for (int col = 0; col < rowData.length; col++) {
                    writeCell(row.createCell(col), rowData[col]);
                }
            }

            autoSizeColumns(sheet, headers.length);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            workbook.write(baos);
            return baos.toByteArray();
        }
    }

    // ───────────────────────── shared POI helpers ─────────────────────────
    // (visual parity with the styles used by the legacy ExcelExporter)

    private static void writeCell(@Nonnull Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(value.toString());
        }
    }

    @Nonnull
    private static CellStyle createHeaderStyle(@Nonnull Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        org.apache.poi.ss.usermodel.Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setBorderTop(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setBorderLeft(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setBorderRight(org.apache.poi.ss.usermodel.BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private static void createHeaderRow(@Nonnull Sheet sheet, @Nonnull String[] headers) {
        CellStyle headerStyle = createHeaderStyle(sheet.getWorkbook());
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
    }

    private static void autoSizeColumns(@Nonnull Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }
}
