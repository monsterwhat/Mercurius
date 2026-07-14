package Utils;

import jakarta.annotation.Nonnull;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.ComprobantesRecibidos;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import Models.Departamento;
import Models.Familia;
import Models.Inventario;
import Models.ProfitMarginHistory;
import Models.ProfitMarginSnapshot;
import Models.StockAlert;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExcelExporter {

    private static final String DATE_PATTERN = "dd/MM/yyyy HH:mm";

    @Nonnull
    private CellStyle createHeaderStyle(@Nonnull Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    @Nonnull
    private CellStyle createDateStyle(@Nonnull Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(DATE_PATTERN));
        return style;
    }

    @Nonnull
    private CellStyle createDecimalStyle(@Nonnull Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        return style;
    }

    @Nonnull
    private Row createHeaderRow(@Nonnull Sheet sheet, @Nonnull String[] headers) {
        CellStyle headerStyle = createHeaderStyle(sheet.getWorkbook());
        Row row = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        return row;
    }

    private void autoSizeColumns(@Nonnull Sheet sheet, int columnCount) {
        for (int i = 0; i < columnCount; i++) {
            sheet.autoSizeColumn(i);
        }
    }

    @Nonnull
    private File writeWorkbook(@Nonnull Workbook workbook, @Nonnull String filePath) throws IOException {
        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }
        return file;
    }

    @Nonnull
    public File exportInventoryToExcel(@Nonnull List<Inventario> inventarios, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Inventario");

        String[] headers = {"Código", "Artículo", "Usuario", "Cantidad", "Unidades Recomendadas", "Tipo Movimiento", "Fecha Movimiento", "Notas", "Status", "Processed"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Inventario inventario : inventarios) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(inventario.getCodigo());
            row.createCell(1).setCellValue(inventario.getArticulo().getNombre());
            row.createCell(2).setCellValue(inventario.getUsuario().getUsername());
            row.createCell(3).setCellValue(inventario.getCantidad().doubleValue());
            row.createCell(4).setCellValue(inventario.getUnidadesRecomendadasFactura().doubleValue());
            row.createCell(5).setCellValue(inventario.getTipoMovimiento());
            row.createCell(6).setCellValue(inventario.getFechaMovimiento() != null ? inventario.getFechaMovimiento().toString() : "");
            row.createCell(7).setCellValue(inventario.getNotas());
            row.createCell(8).setCellValue(inventario.getStatus());
            row.createCell(9).setCellValue(inventario.getProcessed());
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportComprobantesToExcel(@Nonnull List<ComprobantesRecibidos> comprobantes, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Comprobantes");

        String[] headers = {"ID", "Código Actividad", "Clave", "Número Consecutivo", "Fecha Emisión", "Condición Venta", "Plazo Crédito",
                            "Condición Venta Otros", "Emisor", "Receptor", "Total Venta", "Total Impuesto", "Total Comprobante"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (ComprobantesRecibidos comprobante : comprobantes) {
            Row row = sheet.createRow(rowNum++);

            if (comprobante.getEncabezado() != null) {
                Encabezado encabezado = comprobante.getEncabezado();
                row.createCell(0).setCellValue(comprobante.getId());
                row.createCell(1).setCellValue(encabezado.getCodigoActividadEmisor());
                row.createCell(2).setCellValue(encabezado.getClave());
                row.createCell(3).setCellValue(encabezado.getNumeroConsecutivo());
                row.createCell(4).setCellValue(encabezado.getFechaEmision() != null ? encabezado.getFechaEmision().toString() : "");
                row.createCell(5).setCellValue(encabezado.getCondicionVenta());
                row.createCell(6).setCellValue(encabezado.getPlazoCredito());
                row.createCell(7).setCellValue(encabezado.getCondicionVentaOtros());
                row.createCell(8).setCellValue(encabezado.getEmisor() != null ? encabezado.getEmisor().getNombre() : "");
                row.createCell(9).setCellValue(encabezado.getReceptor() != null ? encabezado.getReceptor().getNombre() : "");
            }

            if (comprobante.getResumen() != null) {
                ResumenFactura resumen = comprobante.getResumen();
                row.createCell(10).setCellValue(resumen.getTotalVenta() != null ? resumen.getTotalVenta().doubleValue() : 0.0);
                row.createCell(11).setCellValue(resumen.getTotalImpuesto() != null ? resumen.getTotalImpuesto().doubleValue() : 0.0);
                row.createCell(12).setCellValue(resumen.getTotalComprobante() != null ? resumen.getTotalComprobante().doubleValue() : 0.0);
            }
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportArticulosToExcel(@Nonnull List<Articulos> articulosList, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Articulos");

        String[] headers = {"Código", "Código Cabys", "Recomendación Cabys", "Nombre", "Código de Barras",
                            "Unidad de Medida", "Unidad de Medida Comercial", "Departamento", "Familia",
                            "Precio Costo sin IVA", "Precio Costo con IVA", "Porcentaje de Utilidad", "Precio Final",
                            "Status", "Fecha Creación", "Usuario"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Articulos articulo : articulosList) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(articulo.getCodigo());
            row.createCell(1).setCellValue(articulo.getCodigoCabys() != null ? articulo.getCodigoCabys().getCodigo() : "");
            row.createCell(2).setCellValue(articulo.getRecomendacionCabys());
            row.createCell(3).setCellValue(articulo.getNombre());
            row.createCell(4).setCellValue(articulo.getCodigoBarra());
            row.createCell(5).setCellValue(articulo.getUnidadMedida());
            row.createCell(6).setCellValue(articulo.getUnidadMedidaComercial());
            row.createCell(7).setCellValue(articulo.getDepartamento() != null ? articulo.getDepartamento().getNombre() : "");
            row.createCell(8).setCellValue(articulo.getFamilia() != null ? articulo.getFamilia().getNombre() : "");
            if (articulo.getPrecios() != null && !articulo.getPrecios().isEmpty()) {
                ArticuloPrecio latestPrecio = articulo.getPrecios().get(articulo.getPrecios().size() - 1);

                row.createCell(9).setCellValue(latestPrecio.getPrecioCostoSinIVA() != null ? latestPrecio.getPrecioCostoSinIVA().doubleValue() : 0.0);
                row.createCell(10).setCellValue(latestPrecio.getPrecioFinal() != null ? latestPrecio.getPrecioFinal().doubleValue() : 0.0);
                row.createCell(11).setCellValue(latestPrecio.getPorcentajeUtilidad() != null ? latestPrecio.getPorcentajeUtilidad().doubleValue() : 0.0);
                row.createCell(12).setCellValue(latestPrecio.getPrecioConUtilidad() != null ? latestPrecio.getPrecioConUtilidad().doubleValue() : 0.0);
            } else {
                row.createCell(9).setCellValue(0.0);
                row.createCell(10).setCellValue(0.0);
                row.createCell(11).setCellValue(0.0);
                row.createCell(12).setCellValue(0.0);
            }
            row.createCell(13).setCellValue(articulo.isStatus());
            row.createCell(14).setCellValue(articulo.getFecha() != null ? articulo.getFecha().toString() : "");
            row.createCell(15).setCellValue(articulo.getUsuario() != null ? articulo.getUsuario().getUsername(): "");
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportDepartamentosToExcel(@Nonnull List<Departamento> departamentoList, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Departamentos");

        String[] headers = {"ID", "Nombre", "Status", "Usuario", "Fecha de Creación"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Departamento departamento : departamentoList) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(departamento.getId());
            row.createCell(1).setCellValue(departamento.getNombre());
            row.createCell(2).setCellValue(departamento.getStatus());
            row.createCell(3).setCellValue(departamento.getUsuario() != null ? departamento.getUsuario().getUsername(): "");
            row.createCell(4).setCellValue(departamento.getFecha() != null ? departamento.getFecha().toString() : "");
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportFamiliasToExcel(@Nonnull List<Familia> familiaList, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Familias");

        String[] headers = {"ID", "Nombre", "Status", "Usuario", "Fecha de Creación"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (Familia familia : familiaList) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(familia.getId());
            row.createCell(1).setCellValue(familia.getNombre());
            row.createCell(2).setCellValue(familia.getStatus());
            row.createCell(3).setCellValue(familia.getUsuario() != null ? familia.getUsuario().getUsername(): "");
            row.createCell(4).setCellValue(familia.getFecha() != null ? familia.getFecha().toString() : "");
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportMovimientosToExcel(@Nonnull List<Inventario> inventarios, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();

        Map<String, List<Inventario>> inventariosPorUsuario = inventarios.stream()
                .collect(Collectors.groupingBy(inventario -> inventario.getUsuario().getUsername()));

        for (Map.Entry<String, List<Inventario>> entry : inventariosPorUsuario.entrySet()) {
            String usuario = entry.getKey();
            List<Inventario> inventariosUsuario = entry.getValue();

            Sheet sheet = workbook.createSheet(usuario);
            String[] headers = {"Código", "Artículo", "Usuario", "Cantidad", "Unidades Recomendadas", "Tipo Movimiento", "Fecha Movimiento", "Notas", "Status", "Processed"};
            createHeaderRow(sheet, headers);

            int rowNum = 1;
            for (Inventario inventario : inventariosUsuario) {
                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue(inventario.getCodigo());
                row.createCell(1).setCellValue(inventario.getArticulo().getNombre());
                row.createCell(2).setCellValue(inventario.getUsuario().getUsername());
                row.createCell(3).setCellValue(inventario.getCantidad() != null ? inventario.getCantidad().doubleValue() : 0.0);
                row.createCell(4).setCellValue(inventario.getUnidadesRecomendadasFactura() != null ? inventario.getUnidadesRecomendadasFactura().doubleValue() : 0.0);
                row.createCell(5).setCellValue(inventario.getTipoMovimiento());
                row.createCell(6).setCellValue(inventario.getFechaMovimiento() != null ? inventario.getFechaMovimiento().toString() : "");
                row.createCell(7).setCellValue(inventario.getNotas());
                row.createCell(8).setCellValue(inventario.getStatus());
                row.createCell(9).setCellValue(inventario.getProcessed());
            }

            autoSizeColumns(sheet, headers.length);
        }

        return writeWorkbook(workbook, filePath);
    }
    
    @Nonnull
    public File exportStockAlertsToExcel(@Nonnull List<StockAlert> stockAlerts, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Alertas de Stock");

        String[] headers = {"ID", "Artículo", "Código", "Tipo Alerta", "Cantidad Actual", 
                          "Cantidad Mínima", "Sugerido Reordenar", "Departamento", 
                          "Estado", "Fecha Creación", "Fecha Resolución", "Notas"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (StockAlert alert : stockAlerts) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(alert.getId());
            row.createCell(1).setCellValue(alert.getArticulo() != null ? alert.getArticulo().getNombre() : "");
            row.createCell(2).setCellValue(alert.getArticulo() != null ? alert.getArticulo().getCodigoBarra() : "");
            row.createCell(3).setCellValue(alert.getTipoAlerta());
            row.createCell(4).setCellValue(alert.getCantidadActual() != null ? alert.getCantidadActual().doubleValue() : 0.0);
            row.createCell(5).setCellValue(alert.getCantidadMinima() != null ? alert.getCantidadMinima().doubleValue() : 0.0);
            row.createCell(6).setCellValue(alert.getSugeridoReordenar() != null ? alert.getSugeridoReordenar().doubleValue() : 0.0);
            row.createCell(7).setCellValue(alert.getDepartamento() != null ? alert.getDepartamento().getNombre() : "");
            row.createCell(8).setCellValue(alert.getEstado());
            row.createCell(9).setCellValue(alert.getFechaCreacion() != null ? alert.getFechaCreacion().toString() : "");
            row.createCell(10).setCellValue(alert.getFechaResolucion() != null ? alert.getFechaResolucion().toString() : "");
            row.createCell(11).setCellValue(alert.getNotas() != null ? alert.getNotas() : "");
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportProfitMarginHistoryToExcel(@Nonnull List<ProfitMarginHistory> marginHistory, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Historial Márgenes");

        String[] headers = {"ID", "Artículo", "Código", "Fecha", "Precio Costo", "Precio Venta", 
                          "% Utilidad", "Precio c/Utilidad", "Margen Real", "Cant. Vendida", "Total Ingresos"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (ProfitMarginHistory history : marginHistory) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue((long) history.getId());
            row.createCell(1).setCellValue(history.getArticulo() != null ? history.getArticulo().getNombre() : "");
            row.createCell(2).setCellValue(history.getArticulo() != null ? history.getArticulo().getCodigoBarra() : "");
            row.createCell(3).setCellValue(history.getFecha() != null ? history.getFecha().toString() : "");
            row.createCell(4).setCellValue(history.getPrecioCosto() != null ? history.getPrecioCosto().doubleValue() : 0.0);
            row.createCell(5).setCellValue(history.getPrecioVenta() != null ? history.getPrecioVenta().doubleValue() : 0.0);
            row.createCell(6).setCellValue(history.getPorcentajeUtilidad() != null ? history.getPorcentajeUtilidad().doubleValue() : 0.0);
            row.createCell(7).setCellValue(history.getPrecioConUtilidad() != null ? history.getPrecioConUtilidad().doubleValue() : 0.0);
            row.createCell(8).setCellValue(history.getMargenReal() != null ? history.getMargenReal().doubleValue() : 0.0);
            row.createCell(9).setCellValue(history.getCantidadVendida() != null ? history.getCantidadVendida().doubleValue() : 0.0);
            row.createCell(10).setCellValue(history.getTotalIngresos() != null ? history.getTotalIngresos().doubleValue() : 0.0);
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }

    @Nonnull
    public File exportProfitMarginSnapshotsToExcel(@Nonnull List<ProfitMarginSnapshot> marginSnapshots, @Nonnull String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Snapshots Márgenes");

        String[] headers = {"ID", "Fecha", "Departamento", "Familia", "% Margen Promedio", 
                          "Total Utilidad", "Total Ventas", "Cant. Artículos"};
        createHeaderRow(sheet, headers);

        int rowNum = 1;
        for (ProfitMarginSnapshot snapshot : marginSnapshots) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(snapshot.getId());
            row.createCell(1).setCellValue(snapshot.getFechaSnapshot() != null ? snapshot.getFechaSnapshot().toString() : "");
            row.createCell(2).setCellValue(snapshot.getDepartamento() != null ? snapshot.getDepartamento() : "");
            row.createCell(3).setCellValue(snapshot.getFamilia() != null ? snapshot.getFamilia() : "");
            row.createCell(4).setCellValue(snapshot.getMargenPromedio() != null ? snapshot.getMargenPromedio().doubleValue() : 0.0);
            row.createCell(5).setCellValue(snapshot.getTotalUtilidad() != null ? snapshot.getTotalUtilidad().doubleValue() : 0.0);
            row.createCell(6).setCellValue(snapshot.getTotalVentas() != null ? snapshot.getTotalVentas().doubleValue() : 0.0);
            row.createCell(7).setCellValue(snapshot.getCantidadArticulos() != null ? snapshot.getCantidadArticulos().doubleValue() : 0.0);
        }

        autoSizeColumns(sheet, headers.length);
        return writeWorkbook(workbook, filePath);
    }
      
}
