package Utils;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.Departamento;
import Models.Familia;
import Models.Inventario;
import Models.ProfitMarginSnapshot;
import Models.StockAlert;
import Models.Users;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * T17 unit coverage for {@link ReportExporter}: format magic numbers
 * (PK\x03\x04 for XLSX zip containers, %PDF- for OpenPDF output), non-empty
 * payloads, legacy sheet-name parity and a many-row generic sheet read back
 * through POI. Plain JUnit — no CDI boot, no database.
 */
class ReportExporterTest {

    private static final byte[] ZIP_MAGIC = {'P', 'K', 0x03, 0x04};
    private static final String PDF_MAGIC = "%PDF-";

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static Users user(String name) {
        Users u = new Users();
        u.setUsername(name);
        return u;
    }

    private static ArticuloPrecio precio(String costo, String utilidadPct, String conUtilidad, String venta) {
        ArticuloPrecio p = new ArticuloPrecio();
        p.setPrecioCostoSinIVA(new BigDecimal(costo));
        p.setPorcentajeUtilidad(new BigDecimal(utilidadPct));
        p.setPrecioConUtilidad(new BigDecimal(conUtilidad));
        p.setPrecioFinal(new BigDecimal(venta));
        return p;
    }

    private static Articulos articulo(String nombre) {
        Articulos a = new Articulos();
        a.setNombre(nombre);

        Departamento depto = new Departamento();
        depto.setNombre("Bebidas");
        a.setDepartamento(depto);

        Familia familia = new Familia();
        familia.setNombre("Café");
        a.setFamilia(familia);

        Cabys cabys = new Cabys();
        cabys.setCodigo("1234567890101");
        a.setCodigoCabys(cabys);

        a.setUnidadMedida("Unidad");
        a.setUnidadMedidaComercial("Paquete");
        a.setPrecios(new ArrayList<>(List.of(precio("1000.00", "30.00", "1300.00", "1469.00"))));
        a.setUsuario(user("admin"));
        return a;
    }

    private static Inventario inventario(Articulos articulo, String cantidad) {
        Inventario inv = new Inventario();
        inv.setArticulo(articulo);
        inv.setCantidad(new BigDecimal(cantidad));
        inv.setTipoMovimiento("Entrada");
        inv.setFechaMovimiento(new Date(1756000000000L));
        inv.setUsuario(user("bodeguero"));
        return inv;
    }

    private static StockAlert alerta(int id) {
        StockAlert alert = new StockAlert();
        alert.setId(id);
        alert.setArticulo(articulo("Leche Entera 1L"));
        alert.setTipoAlerta("low_stock");
        alert.setCantidadActual(3);
        alert.setCantidadMinima(10);
        alert.setSugeridoReordenar(7);
        Departamento depto = new Departamento();
        depto.setNombre("Lácteos");
        alert.setDepartamento(depto);
        alert.setEstado("active");
        alert.setFechaCreacion(new Date(1756000000000L));
        alert.setNotas(null); // legacy fallback writes ""
        return alert;
    }

    private static ProfitMarginSnapshot snapshot(int id) {
        ProfitMarginSnapshot s = new ProfitMarginSnapshot();
        s.setId(id);
        s.setFechaSnapshot(new Date(1756000000000L));
        s.setDepartamento("Bebidas");
        s.setFamilia(null); // legacy fallback writes ""
        s.setMargenPromedio(new BigDecimal("15.75"));
        s.setTotalUtilidad(new BigDecimal("1000.10"));
        s.setTotalVentas(new BigDecimal("6600.00"));
        s.setCantidadArticulos(42);
        return s;
    }

    // ── magic-byte assertions ────────────────────────────────────────────────

    private static void assertZipMagic(byte[] bytes) {
        assertThat(bytes).startsWith(ZIP_MAGIC);
        assertThat(bytes.length).isPositive();
    }

    private static void assertPdfMagic(byte[] bytes) {
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo(PDF_MAGIC);
        assertThat(bytes.length).isPositive();
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    @Test
    void articulosPdfStartsWithPdfMagicAndIsNonEmpty() throws Exception {
        byte[] bytes = ReportExporter.exportArticulosPdf(List.of(articulo("Café 500g")));

        assertPdfMagic(bytes);
        assertThat(new String(bytes, bytes.length - 16, 16, StandardCharsets.US_ASCII)).contains("%%EOF");
    }

    @Test
    void articulosPdfRendersEveryArticleBlock() throws Exception {
        byte[] one = ReportExporter.exportArticulosPdf(List.of(articulo("Café 500g")));
        List<Articulos> varios = List.of(articulo("Café 500g"), articulo("Té Verde"), articulo("Cacao"));
        byte[] many = ReportExporter.exportArticulosPdf(varios);

        assertPdfMagic(one);
        assertPdfMagic(many);
        assertThat(many.length).isGreaterThan(one.length);
    }

    @Test
    void inventarioPdfStartsWithPdfMagicAndIsNonEmpty() throws Exception {
        Articulos articulo = articulo("Arroz 5kg");
        byte[] bytes = ReportExporter.exportInventarioPdf(
                List.of(inventario(articulo, "5.5"), inventario(articulo, "12")));

        assertPdfMagic(bytes);
        assertThat(new String(bytes, bytes.length - 16, 16, StandardCharsets.US_ASCII)).contains("%%EOF");
    }

    @Test
    void emptyDatasetsStillProduceValidFiles() throws Exception {
        byte[] emptyPdf = ReportExporter.exportInventarioPdf(List.of());
        byte[] emptyXlsx = ReportExporter.exportStockAlertsExcel(List.of());

        assertPdfMagic(emptyPdf);
        assertZipMagic(emptyXlsx);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(emptyXlsx))) {
            assertThat(wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    // ── Excel ────────────────────────────────────────────────────────────────

    @Test
    void stockAlertsExcelHasZipMagicAndLegacyLayout() throws IOException {
        byte[] bytes = ReportExporter.exportStockAlertsExcel(
                List.of(alerta(1), alerta(2), alerta(3)));

        assertZipMagic(bytes);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Alertas de Stock");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(header.getCell(11).getStringCellValue()).isEqualTo("Notas");

            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("Leche Entera 1L");
            assertThat(first.getCell(4).getNumericCellValue()).isEqualTo(3.0);
            assertThat(first.getCell(11).getStringCellValue()).isEmpty();
        }
    }

    @Test
    void profitMarginsExcelHasZipMagicAndLegacyLayout() throws IOException {
        byte[] bytes = ReportExporter.exportProfitMarginSnapshotsExcel(
                List.of(snapshot(7), snapshot(8)));

        assertZipMagic(bytes);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Snapshots Márgenes");
            assertThat(sheet).isNotNull();

            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("ID");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("% Margen Promedio");

            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            Row second = sheet.getRow(2);
            assertThat(second.getCell(0).getNumericCellValue()).isEqualTo(8.0);
            assertThat(second.getCell(2).getStringCellValue()).isEqualTo("Bebidas");
            assertThat(second.getCell(3).getStringCellValue()).isEmpty();
            assertThat(second.getCell(5).getNumericCellValue()).isEqualTo(1000.10);
        }
    }

    @Test
    void genericExcelHandlesManyRowsWithMixedCellTypes() throws IOException {
        String[] headers = {"Número", "Texto", "Decimal", "Sí/No"};
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            rows.add(new Object[]{i, "fila-" + i, new BigDecimal(i + ".25"), i % 2 == 0});
        }

        byte[] bytes = ReportExporter.exportGenericExcel("Masivo", headers, rows);

        assertZipMagic(bytes);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Masivo");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getLastRowNum()).isEqualTo(500);

            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(first.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(first.getCell(1).getStringCellValue()).isEqualTo("fila-1");

            Row last = sheet.getRow(500);
            assertThat(last.getCell(0).getNumericCellValue()).isEqualTo(500.0);
            assertThat(last.getCell(1).getStringCellValue()).isEqualTo("fila-500");
            assertThat(last.getCell(2).getNumericCellValue()).isEqualTo(500.25);
            assertThat(last.getCell(3).getBooleanCellValue()).isTrue();
        }
    }
}
