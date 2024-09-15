package Utils;

import Models.ArticuloPrecio;
import Models.Articulos;
import Models.Comprobantes.ComprobanteFinal;
import Models.Comprobantes.Encabezado.Encabezado;
import Models.Comprobantes.Resumen.ResumenFactura;
import Models.Departamento;
import Models.Familia;
import Models.Inventario;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ExcelExporter {

    public File exportInventoryToExcel(List<Inventario> inventarios, String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Inventario");

        String[] headers = {"Código", "Artículo", "Usuario", "Cantidad", "Unidades Recomendadas", "Tipo Movimiento", "Fecha Movimiento", "Notas", "Status", "Processed"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        int rowNum = 1;
        for (Inventario inventario : inventarios) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(inventario.getCodigo());
            row.createCell(1).setCellValue(inventario.getArticulo().getNombre());
            row.createCell(2).setCellValue(inventario.getUsuario().getUsername());
            row.createCell(3).setCellValue(inventario.getCantidad().doubleValue());
            row.createCell(4).setCellValue(inventario.getUnidadesRecomendadasFactura());
            row.createCell(5).setCellValue(inventario.getTipoMovimiento());
            row.createCell(6).setCellValue(inventario.getFechaMovimiento().toString());
            row.createCell(7).setCellValue(inventario.getNotas());
            row.createCell(8).setCellValue(inventario.getStatus());
            row.createCell(9).setCellValue(inventario.getProcessed());
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }

        return file;
    }

    public File exportComprobantesToExcel(List<ComprobanteFinal> comprobantes, String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Comprobantes");

        String[] headers = {"ID", "Código Actividad", "Clave", "Número Consecutivo", "Fecha Emisión", "Condición Venta", "Plazo Crédito",
                            "Emisor", "Receptor", "Total Venta", "Total Impuesto", "Total Comprobante"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        int rowNum = 1;
        for (ComprobanteFinal comprobante : comprobantes) {
            Row row = sheet.createRow(rowNum++);

            if (comprobante.getEncabezado() != null) {
                Encabezado encabezado = comprobante.getEncabezado();
                row.createCell(0).setCellValue(comprobante.getId());
                row.createCell(1).setCellValue(encabezado.getCodigoActividad());
                row.createCell(2).setCellValue(encabezado.getClave());
                row.createCell(3).setCellValue(encabezado.getNumeroConsecutivo());
                row.createCell(4).setCellValue(encabezado.getFechaEmision());
                row.createCell(5).setCellValue(encabezado.getCondicionVenta());
                row.createCell(6).setCellValue(encabezado.getPlazoCredito());
                row.createCell(7).setCellValue(encabezado.getEmisor().getNombre());
                row.createCell(8).setCellValue(encabezado.getReceptor().getNombre());
            }

            if (comprobante.getResumen() != null) {
                ResumenFactura resumen = comprobante.getResumen();
                row.createCell(9).setCellValue(resumen.getTotalVenta().doubleValue());
                row.createCell(10).setCellValue(resumen.getTotalImpuesto().doubleValue());
                row.createCell(11).setCellValue(resumen.getTotalComprobante().doubleValue());
            }
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }

        return file;
    }

    public File exportArticulosToExcel(List<Articulos> articulosList, String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Articulos");

        String[] headers = {"Código", "Código Cabys", "Recomendación Cabys", "Nombre", "Detalles", "Código de Barras",
                            "Unidad de Medida", "Unidad de Medida Comercial", "Departamento", "Familia",
                            "Precio Costo sin IVA", "Precio Costo con IVA", "Porcentaje de Utilidad", "Precio Final",
                            "Status", "Fecha Creación", "Usuario"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        int rowNum = 1;
        for (Articulos articulo : articulosList) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(articulo.getCodigo());
            row.createCell(1).setCellValue(articulo.getCodigoCabys() != null ? articulo.getCodigoCabys().getCodigo() : "");
            row.createCell(2).setCellValue(articulo.getRecomendacionCabys());
            row.createCell(3).setCellValue(articulo.getNombre());
            row.createCell(4).setCellValue(articulo.getDetalles());
            row.createCell(5).setCellValue(articulo.getCodigoBarra());
            row.createCell(6).setCellValue(articulo.getUnidadMedida());
            row.createCell(7).setCellValue(articulo.getUnidadMedidaComercial());
            row.createCell(8).setCellValue(articulo.getDepartamento() != null ? articulo.getDepartamento().getNombre() : "");
            row.createCell(9).setCellValue(articulo.getFamilia() != null ? articulo.getFamilia().getNombre() : "");
            // Show only the latest price, assuming it's the last in the list
            if (articulo.getPrecios() != null && !articulo.getPrecios().isEmpty()) {
                ArticuloPrecio latestPrecio = articulo.getPrecios().get(articulo.getPrecios().size() - 1);

                row.createCell(10).setCellValue(latestPrecio.getPrecioCostoSinIVA().doubleValue());
                row.createCell(11).setCellValue(latestPrecio.getPrecioCostoConIVA().doubleValue());
                row.createCell(12).setCellValue(latestPrecio.getPorcentajeUtilidad().doubleValue());
                row.createCell(13).setCellValue(latestPrecio.getPrecioFinal().doubleValue());
            } else {
                row.createCell(10).setCellValue(0.0);
                row.createCell(11).setCellValue(0.0);
                row.createCell(12).setCellValue(0.0);
                row.createCell(13).setCellValue(0.0);
            }
            row.createCell(14).setCellValue(articulo.isStatus());
            row.createCell(15).setCellValue(articulo.getFecha());
            row.createCell(16).setCellValue(articulo.getUsuario() != null ? articulo.getUsuario().getUsername(): "");
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }

        return file;
    }

    public File exportDepartamentosToExcel(List<Departamento> departamentoList, String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Departamentos");

        String[] headers = {"ID", "Nombre", "Status", "Usuario", "Fecha de Creación"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        int rowNum = 1;
        for (Departamento departamento : departamentoList) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(departamento.getId());
            row.createCell(1).setCellValue(departamento.getNombre());
            row.createCell(2).setCellValue(departamento.getStatus());
            row.createCell(3).setCellValue(departamento.getUsuario() != null ? departamento.getUsuario().getUsername(): "");
            row.createCell(4).setCellValue(departamento.getFecha()); // Format date as needed
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }

        return file;
    }

    public File exportFamiliasToExcel(List<Familia> familiaList, String filePath) throws IOException {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Familias");

        String[] headers = {"ID", "Nombre", "Status", "Usuario", "Fecha de Creación"};
        Row headerRow = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
        }

        int rowNum = 1;
        for (Familia familia : familiaList) {
            Row row = sheet.createRow(rowNum++);

            row.createCell(0).setCellValue(familia.getId());
            row.createCell(1).setCellValue(familia.getNombre());
            row.createCell(2).setCellValue(familia.getStatus());
            row.createCell(3).setCellValue(familia.getUsuario() != null ? familia.getUsuario().getUsername(): "");
            row.createCell(4).setCellValue(familia.getFecha());
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }

        return file;
    }
    
    public File exportMovimientosToExcel(List<Inventario> inventarios, String filePath) throws IOException {
    Workbook workbook = new XSSFWorkbook();
    
        // Agrupar inventarios por usuario
        Map<String, List<Inventario>> inventariosPorUsuario = inventarios.stream()
                .collect(Collectors.groupingBy(inventario -> inventario.getUsuario().getUsername()));

        for (Map.Entry<String, List<Inventario>> entry : inventariosPorUsuario.entrySet()) {
            String usuario = entry.getKey();
            List<Inventario> inventariosUsuario = entry.getValue();

            Sheet sheet = workbook.createSheet(usuario);
            String[] headers = {"Código", "Artículo", "Usuario", "Cantidad", "Unidades Recomendadas", "Tipo Movimiento", "Fecha Movimiento", "Notas", "Status", "Processed"};
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
            }

            int rowNum = 1;
            for (Inventario inventario : inventariosUsuario) {
                Row row = sheet.createRow(rowNum++);

                row.createCell(0).setCellValue(inventario.getCodigo());
                row.createCell(1).setCellValue(inventario.getArticulo().getNombre());
                row.createCell(2).setCellValue(inventario.getUsuario().getUsername());
                row.createCell(3).setCellValue(inventario.getCantidad().doubleValue());
                row.createCell(4).setCellValue(inventario.getUnidadesRecomendadasFactura());
                row.createCell(5).setCellValue(inventario.getTipoMovimiento());
                row.createCell(6).setCellValue(inventario.getFechaMovimiento().toString());
                row.createCell(7).setCellValue(inventario.getNotas());
                row.createCell(8).setCellValue(inventario.getStatus());
                row.createCell(9).setCellValue(inventario.getProcessed());
            }

            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
        }

        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }

        return file;
    }
    
}
