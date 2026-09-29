package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import Models.DTO.ImportacionMasivaColumna;
import Models.DTO.ImportacionMasivaFilaResultado;
import Models.DTO.ImportacionMasivaResultado;
import Models.ImportacionMasivaEntidad;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the pure, CDI-free parts of
 * {@link ImportacionMasivaService}: locale-tolerant money/date/boolean parsing,
 * CSV and XLSX decoding, header folding + the required-column contract, and the
 * template round-trip. Plain JUnit — no CDI boot, no database, no service
 * injection, so it stays runnable in a plain unit phase.
 */
class ImportacionMasivaServiceTest {

    // ── parsearMonto: Costa Rican data ───────────────────────────────────────

    @Test
    void montoAceptaLosDosConveniosDeSeparadores() {
        assertThat(ImportacionMasivaService.parsearMonto("1234.56"))
                .isEqualByComparingTo("1234.56");
        assertThat(ImportacionMasivaService.parsearMonto("1,234.56"))
                .isEqualByComparingTo("1234.56");
        assertThat(ImportacionMasivaService.parsearMonto("1.234,56"))
                .isEqualByComparingTo("1234.56");
        assertThat(ImportacionMasivaService.parsearMonto("₡1.234,56"))
                .isEqualByComparingTo("1234.56");
        assertThat(ImportacionMasivaService.parsearMonto("1,234"))
                .isEqualByComparingTo("1234");        // grouping, not 1.234
        assertThat(ImportacionMasivaService.parsearMonto("1.234.567"))
                .isEqualByComparingTo("1234567");
        assertThat(ImportacionMasivaService.parsearMonto(" 1 234,5 "))
                .isEqualByComparingTo("1234.5");
        assertThat(ImportacionMasivaService.parsearMonto("-50,25"))
                .isEqualByComparingTo("-50.25");
    }

    @Test
    void montoRechazaEntradasInvalidasDeFormaExplicita() {
        for (String invalido : new String[]{"", "   ", "abc", "1a2", "12%", "1.2.3.4", "--5", "12.3456"}) {
            assertThatThrownBy(() -> ImportacionMasivaService.parsearMonto(invalido))
                    .as("'" + invalido + "' debe rechazarse")
                    .isInstanceOf(NumberFormatException.class);
        }
    }

    // ── parsearFecha / parsearBooleano / parsearEntero ────────────────────────

    @Test
    void fechaAceptaLosFormatosDeclaradosYRechazaFechasInexistentes() {
        assertThat(ImportacionMasivaService.parsearFecha("2026-09-01")).isNotNull();
        assertThat(ImportacionMasivaService.parsearFecha("01/09/2026")).isNotNull();
        assertThat(ImportacionMasivaService.parsearFecha("01-09-2026")).isNotNull();
        assertThat(ImportacionMasivaService.parsearFecha("2026/09/01")).isNotNull();
        assertThat(ImportacionMasivaService.parsearFecha("01/09/26")).isNotNull();
        // Strict resolution: no rollover into a different day.
        assertThatThrownBy(() -> ImportacionMasivaService.parsearFecha("2026-13-45"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportacionMasivaService.parsearFecha("32/01/2026"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportacionMasivaService.parsearFecha("hoy"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void booleanoEnEspanolYEnteroEstricto() {
        assertThat(ImportacionMasivaService.parsearBooleano("Sí")).isTrue();
        assertThat(ImportacionMasivaService.parsearBooleano("si")).isTrue();
        assertThat(ImportacionMasivaService.parsearBooleano("TRUE")).isTrue();
        assertThat(ImportacionMasivaService.parsearBooleano("1")).isTrue();
        assertThat(ImportacionMasivaService.parsearBooleano("No")).isFalse();
        assertThat(ImportacionMasivaService.parsearBooleano("0")).isFalse();
        assertThatThrownBy(() -> ImportacionMasivaService.parsearBooleano("quizá"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ImportacionMasivaService.parsearEntero("40")).isEqualTo(40);
        assertThat(ImportacionMasivaService.parsearEntero("40.0")).isEqualTo(40);
        assertThatThrownBy(() -> ImportacionMasivaService.parsearEntero("4.5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportacionMasivaService.parsearEntero("cuarenta"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Lectura de archivos ──────────────────────────────────────────────────

    @Test
    void csvSeLeeConComillasDelimitadorYColumnasIrregulares() throws Exception {
        String csv = "Nombre;Cedula;Email\n"
                + "\"Empresa; S.A.\";118820456;info@correo.cr\n"
                + "Siguiente;;\n"
                + ";;\n";
        List<List<String>> filas = ImportacionMasivaService.leerCsv(csv.getBytes(StandardCharsets.UTF_8));
        assertThat(filas).hasSize(4);
        assertThat(filas.get(0)).containsExactly("Nombre", "Cedula", "Email");
        // A ';' inside quotes must not split the field.
        assertThat(filas.get(1)).containsExactly("Empresa; S.A.", "118820456", "info@correo.cr");
        // A short row pads to nothing rather than throwing.
        assertThat(filas.get(2)).containsExactly("Siguiente", "", "");
        // A fully blank row is still surfaced, so the caller can report it as
        // skipped. Two delimiters are three empty fields — the same rule every
        // CSV reader applies (";;".split(";") is ["","",""]) — so the row keeps
        // the file's column shape instead of a miscounted two.
        assertThat(filas.get(3)).containsExactly("", "", "");
    }

    @Test
    void xlsxSeLeeConCeldasDeTextoYNumericas() throws Exception {
        byte[] bytes = xlsxDePrueba();
        List<List<String>> filas = ImportacionMasivaService.leerCeldas("articulos.xlsx", bytes);
        assertThat(filas).hasSize(2);
        assertThat(filas.get(0)).contains("Nombre", "Precio Costo sin IVA");
        // The column index comes from the HEADER row (0), applied to the data
        // row (1). Looking the header caption up in the data row always misses
        // (indexOf returns -1) and .get(-1) throws — which is what this test did
        // before.
        assertThat(filas.get(1).get(filas.get(0).indexOf("Nombre"))).isEqualTo("Leche entera 1 L");
        // Numeric cells are rendered locale-independently (dot decimal separator).
        int indicePrecio = filas.get(1).indexOf("1234.56");
        assertThat(indicePrecio).isGreaterThanOrEqualTo(0);
    }

    @Test
    void formatoNoSoportadoYWorkbookCorruptoFallanDeFormaExplicita() {
        assertThatThrownBy(() -> ImportacionMasivaService.leerCeldas("datos.pdf", new byte[]{1, 2, 3}))
                .isInstanceOf(ImportacionMasivaService.EsquemaInvalidoException.class)
                .hasMessageContaining(".xlsx");
        assertThatThrownBy(() -> ImportacionMasivaService.leerCeldas("roto.xlsx", new byte[]{9, 9, 9}))
                .isInstanceOf(ImportacionMasivaService.EsquemaInvalidoException.class);
    }

    // ── Esquema ───────────────────────────────────────────────────────────────

    @Test
    void encabezadoSeNormalizaIgnorandoAcentosYPuntuacion() {
        assertThat(ImportacionMasivaService.normalizarEncabezado("Código de Barras"))
                .isEqualTo(ImportacionMasivaService.normalizarEncabezado("codigo_barras"));
        assertThat(ImportacionMasivaService.normalizarEncabezado(" Actividad Económica "))
                .isEqualTo("actividadeconomica");
    }

    @Test
    void faltaDeColumnaObligatoriaFallaElArchivoEntero() {
        List<ImportacionMasivaColumna> columnas =
                new ImportacionMasivaService().columnas(ImportacionMasivaEntidad.CABYS);
        // "Impuesto" is required and absent -> whole-file rejection, in Spanish.
        assertThatThrownBy(() -> ImportacionMasivaService.mapearEncabezados(
                List.of("Codigo", "Descripcion"), columnas))
                .isInstanceOf(ImportacionMasivaService.EsquemaInvalidoException.class)
                .hasMessageContaining("Impuesto");
    }

    @Test
    void lasCuatroEntidadesDeclaranColumnasYLaPlantillaRoundTrip() throws Exception {
        ImportacionMasivaService servicio = new ImportacionMasivaService();
        for (ImportacionMasivaEntidad entidad : ImportacionMasivaEntidad.values()) {
            List<ImportacionMasivaColumna> columnas = servicio.columnas(entidad);
            assertThat(columnas).as("columnas de " + entidad.getClave()).isNotEmpty();
            assertThat(columnas).allSatisfy(c -> assertThat(c.getEncabezado()).isNotBlank());

            // The xlsx template header must satisfy its own required-column contract.
            byte[] plantilla = servicio.plantillaXlsx(entidad);
            List<List<String>> celdas = ImportacionMasivaService.leerCeldas(
                    "plantilla-" + entidad.getClave() + ".xlsx", plantilla);
            assertThat(celdas).as("plantilla de " + entidad.getClave()).isNotEmpty();
            ImportacionMasivaService.mapearEncabezados(celdas.get(0), columnas);

            // Same headers, minus the " *" required marker, in the csv template.
            String csv = servicio.plantillaCsv(entidad);
            List<List<String>> csvCeldas = ImportacionMasivaService.leerCsv(csv.getBytes(StandardCharsets.UTF_8));
            ImportacionMasivaService.mapearEncabezados(csvCeldas.get(0), columnas);
        }
    }

    @Test
    void plantillaXlsxTraeEncabezadoYFilaDeEjemplo() throws Exception {
        byte[] bytes = new ImportacionMasivaService().plantillaXlsx(ImportacionMasivaEntidad.CLIENTES);
        try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(libro.getNumberOfSheets()).isEqualTo(2);
            assertThat(libro.getSheetName(0)).isEqualTo("Clientes");
            assertThat(libro.getSheetName(1)).isEqualTo("Instrucciones");
            Row encabezado = libro.getSheetAt(0).getRow(0);
            Row ejemplo = libro.getSheetAt(0).getRow(1);
            assertThat(encabezado.getFirstCellNum()).isZero();
            assertThat(encabezado.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(encabezado.getCell(0).getStringCellValue()).isEqualTo("Nombre *");
            assertThat(ejemplo.getCell(0).getStringCellValue()).isEqualTo("Cliente de Prueba");
        }
    }

    // ── Resultado ─────────────────────────────────────────────────────────────

    @Test
    void elResumenCuadraConElDetallePorFila() {
        ImportacionMasivaResultado resultado = new ImportacionMasivaResultado(
                "clientes", "Clientes", "clientes.xlsx", true, List.of());
        resultado.getFilas().add(ImportacionMasivaFilaResultado.nuevo(2, "a", "Se creará"));
        resultado.getFilas().add(ImportacionMasivaFilaResultado.actualizado(3, "b", "Se actualizará"));
        resultado.getFilas().add(ImportacionMasivaFilaResultado.omitida(4, null, "Fila vacía"));
        resultado.getFilas().add(ImportacionMasivaFilaResultado.rechazada(5, "c", "Cédula duplicada"));
        resultado.recalcular();

        assertThat(resultado.getTotalFilas()).isEqualTo(4);
        assertThat(resultado.getCreados()).isEqualTo(1);
        assertThat(resultado.getActualizados()).isEqualTo(1);
        assertThat(resultado.getOmitidas()).isEqualTo(1);
        assertThat(resultado.getRechazadas()).isEqualTo(1);
        assertThat(resultado.getMensaje())
                .contains("Simulación")
                .contains("No se guardó ningún cambio");
        // Every row carries a reason: acceptance or rejection, never blank.
        assertThat(resultado.getFilas()).allSatisfy(f -> assertThat(f.getMotivo()).isNotBlank());
    }

    @Test
    void claveDeEntidadSeResuelveSinAcentosNiMayusculas() {
        assertThat(ImportacionMasivaEntidad.desdeClave("articulos"))
                .isEqualTo(ImportacionMasivaEntidad.ARTICULOS);
        assertThat(ImportacionMasivaEntidad.desdeClave("ARTÍCULOS"))
                .isEqualTo(ImportacionMasivaEntidad.ARTICULOS);
        assertThat(ImportacionMasivaEntidad.desdeClave(" Artículos "))
                .isEqualTo(ImportacionMasivaEntidad.ARTICULOS);
        assertThat(ImportacionMasivaEntidad.desdeClave("inventario")).isNull();
        assertThat(ImportacionMasivaEntidad.clavesDisponibles())
                .isEqualTo("clientes, articulos, cabys, precios");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /** Two-row workbook: mixed string/numeric cells, mirroring an export sheet. */
    private static byte[] xlsxDePrueba() throws Exception {
        try (Workbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Sheet hoja = libro.createSheet("Articulos");
            Row cabecera = hoja.createRow(0);
            cabecera.createCell(0).setCellValue("Nombre");
            cabecera.createCell(1).setCellValue("Precio Costo sin IVA");
            Row datos = hoja.createRow(1);
            datos.createCell(0).setCellValue("Leche entera 1 L");
            datos.createCell(1).setCellValue(1234.56d);
            libro.write(salida);
            return salida.toByteArray();
        }
    }
}
