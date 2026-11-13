package Utils;

import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Services.DirectoryService;
import Models.ConfiguracionAplicacion;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Promocion;
import Models.Clientes;
import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Enums.Tipo_CodigoImpuesto;
import Models.ReportesFamiliasYDepartamentos;
import Models.AlertaStock;
import Models.EntradaPago;
import Models.CorteMargen;
import Models.Usuarios;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.Meta;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.google.zxing.WriterException;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/**
 *
 * @author Al
 */
@RequestScoped
@Data
public class PDFGenerator {

    private static final Logger LOG = Logger.getLogger(PDFGenerator.class);

    @Nullable
    private String pdfUrl;

    @Nullable
    private String pdfLocalPath;
    @Inject
    DirectoryService dirService;

    public void generarPDFTiqueteElectronico(@Nonnull ComprobantesEmitidos tiqueteElectronico, @Nonnull ConfiguracionAplicacion settings, @Nonnull List<ArticuloCarrito> carrito, @Nonnull Clientes cliente, @Nonnull Usuarios user, @Nonnull BigDecimal pago, @Nonnull BigDecimal vuelto, @Nullable List<EntradaPago> pagos) {
        // PDF generation logic here
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Add content to the document
            Document document = addContentToDocument(baos, settings, tiqueteElectronico, cliente, user, carrito, pago, vuelto, pagos);

            // Close the document after finishing adding content
            document.close();

            savePdfToFileSystem(baos, tiqueteElectronico);

        } catch (DocumentException | IOException e) {
            // La excepcion va en el log: sin ella, un 500 PDF_NO_GENERADO
            // arrives a PosResource sin ninguna causa registrada y el fallo
            // queda sin explicar. (Fue justo lo que paso con la carrera de
            // mkdirs de arriba: tres hilos en el mismo milisegundo y ni una
            // linea con el motivo.)
            LOG.warn("failed to generar p d f tiquete electronico: " + e, e);
        }

    }

    /**
     * PDF propio de una Nota de Credito. La NC no pasa por el POS: se arma en
     * {@code DevolucionesResource}, asi que necesita su propia impresion con
     * el mismo rollo de 72 mm. Se guarda como {@code factura_<clave>.pdf},
     * que es el nombre que usa el boton Imprimir NC.
     */
    public void generarPDFNotaCredito(@Nonnull ComprobantesEmitidos nc,
                                      @Nonnull ConfiguracionAplicacion settings) {
        generarPDFComprobante(nc, settings);
    }

    /**
     * PDF imprimible de CUALQUIER comprobante emitido, armado desde las lineas
     * y totales persistidos. La NC se guarda como factura_<clave>.pdf (el
     * nombre que usa Imprimir NC); el resto como tiqueteElectronico_<id>.pdf
     * (el nombre que ya sirve el POS). Tambien es el fallback del correo y del
     * boton Imprimir del historial.
     */
    public void generarPDFComprobante(@Nonnull ComprobantesEmitidos comprobante,
                                      @Nonnull ConfiguracionAplicacion settings) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String codigo = comprobante.getEncabezado() != null
                ? comprobante.getEncabezado().getCodigoDocumento() : null;
        String clave = comprobante.getEncabezado() != null
                ? comprobante.getEncabezado().getClave() : null;
        boolean esNc = "02".equals(codigo);
        String fileName = esNc
                ? "factura_" + (clave == null || clave.isBlank() ? comprobante.getId() : clave) + ".pdf"
                : "tiqueteElectronico_" + comprobante.getId() + ".pdf";
        String titulo = esNc ? "NOTA DE CREDITO ELECTRONICA" : tituloDocumento(codigo);
        try {
            Document document = buildComprobanteDocument(baos, comprobante, settings, titulo);
            document.close();
            saveFacturaPdfToFileSystem(baos, fileName);
        } catch (DocumentException | IOException e) {
            LOG.warn("No se pudo generar el PDF del comprobante: " + e.getMessage()
                    + " | codigo=" + codigo + " | clave=" + clave
                    + " | source=PDFGenerator.generarPDFComprobante()");
        }
    }

    private Document buildComprobanteDocument(ByteArrayOutputStream baos,
                                             ComprobantesEmitidos nc,
                                             ConfiguracionAplicacion settings,
                                             String titulo) throws DocumentException {
        List<LineaDetalle> lineas = nc.getDetalles() != null
                && nc.getDetalles().getLineasDetalle() != null
                ? nc.getDetalles().getLineasDetalle() : List.of();
        float altoPagina = 566.93f + Math.max(0, lineas.size() - 3) * 14f;
        Document document = new Document(new Rectangle(204.09f, altoPagina), 6f, 6f, 6f, 6f);
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();

        com.lowagie.text.Font font = new com.lowagie.text.Font();
        font.setSize(9f);
        float leading = 10.5f;
        com.lowagie.text.Font titleFont = new com.lowagie.text.Font(font);
        titleFont.setSize(11f);
        titleFont.setStyle(com.lowagie.text.Font.BOLD);

        Paragraph tituloPdf = new Paragraph(titulo, titleFont);
        tituloPdf.setAlignment(Element.ALIGN_CENTER);
        tituloPdf.setLeading(13f);
        document.add(tituloPdf);
        document.add(separadorPdf());

        java.util.List<String> headerLines = new java.util.ArrayList<>();
        addHeaderLine(headerLines, settings.getNombreNegocio());
        if (settings.getNombre() != null
                && !settings.getNombre().equalsIgnoreCase(settings.getNombreNegocio())) {
            addHeaderLine(headerLines, settings.getNombre());
        }
        if (settings.getIdentificacion() != null) {
            addHeaderLine(headerLines, "Identificacion: " + settings.getIdentificacion());
        }
        if (settings.getTelefono() != null) {
            addHeaderLine(headerLines, "Tel. " + settings.getTelefono());
        }
        addHeaderLine(headerLines, settings.getCorreoElectronicoTributacion());
        addHeaderLine(headerLines, settings.getDireccionCompleta());
        Paragraph header = new Paragraph(String.join("\n", headerLines), font);
        header.setAlignment(Element.ALIGN_CENTER);
        header.setLeading(leading);
        document.add(header);
        document.add(espacioPdf(leading));

        String fecha = nc.getEncabezado() != null && nc.getEncabezado().getFechaEmision() != null
                ? nc.getEncabezado().getFechaEmision()
                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                : "";
        String consecutivo = nc.getEncabezado() != null
                ? nc.getEncabezado().getNumeroConsecutivo() : "";
        String clave = nc.getEncabezado() != null ? nc.getEncabezado().getClave() : "";
        String original = "";
        String motivo = "";
        if (nc.getInformacionReferencia() != null && !nc.getInformacionReferencia().isEmpty()) {
            var ref = nc.getInformacionReferencia().get(0);
            original = ref.getNumero() == null ? "" : ref.getNumero();
            motivo = ref.getRazon() == null ? "" : ref.getRazon();
        }
        String cliente = nc.getEncabezado() != null && nc.getEncabezado().getReceptor() != null
                ? nc.getEncabezado().getReceptor().getNombre() : "Cliente contado";
        java.util.List<String> infoLines = new java.util.ArrayList<>();
        infoLines.add("Fecha: " + fecha);
        infoLines.add("Consecutivo: " + consecutivo);
        infoLines.add("Clave: " + clave);
        infoLines.add("Cliente: " + (cliente == null ? "Cliente contado" : cliente));
        if ("02".equals(nc.getEncabezado() != null ? nc.getEncabezado().getCodigoDocumento() : null)) {
            infoLines.add("Factura original: " + original);
            infoLines.add("Motivo: " + motivo);
        } else {
            infoLines.add("Estado Hacienda: " + (nc.getHaciendaEstado() == null
                    ? "" : nc.getHaciendaEstado()));
            infoLines.add("Condicion de venta: " + (nc.getEncabezado() != null
                    && nc.getEncabezado().getCondicionVenta() != null
                    ? nc.getEncabezado().getCondicionVenta() : ""));
        }
        String info = String.join("\n", infoLines);
        Paragraph infoP = new Paragraph(info, font);
        infoP.setLeading(leading);
        document.add(infoP);
        document.add(espacioPdf(leading));
        document.add(separadorPdf());

        PdfPTable table = new PdfPTable(new float[]{1.1f, 2.5f, 1.4f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        for (LineaDetalle linea : lineas) {
            table.addCell(celdaPdf(linea.getCantidad() == null ? "" : linea.getCantidad().toString(),
                    font, Element.ALIGN_LEFT));
            table.addCell(celdaPdf(linea.getDetalle(), font, Element.ALIGN_CENTER));
            BigDecimal monto = linea.getMontoTotalLinea() != null
                    ? linea.getMontoTotalLinea()
                    : (linea.getMontoTotal() != null ? linea.getMontoTotal() : BigDecimal.ZERO);
            table.addCell(celdaPdf(moneda(nc, monto) + " " + letraImpuesto(linea),
                    font, Element.ALIGN_RIGHT));
        }
        document.add(table);
        document.add(separadorPdf());

        BigDecimal descuentos = nc.getResumen() != null && nc.getResumen().getTotalDescuentos() != null
                ? nc.getResumen().getTotalDescuentos() : BigDecimal.ZERO;
        BigDecimal iva = nc.getResumen() != null && nc.getResumen().getTotalImpuesto() != null
                ? nc.getResumen().getTotalImpuesto() : BigDecimal.ZERO;
        BigDecimal total = nc.getResumen() != null && nc.getResumen().getTotalComprobante() != null
                ? nc.getResumen().getTotalComprobante() : BigDecimal.ZERO;
        PdfPTable totales = new PdfPTable(2);
        totales.setWidthPercentage(100);
        String monedaDoc = monedaCodigo(nc);
        filaTotalPdf(totales, "Descuentos:", descuentos, font, monedaDoc);
        filaTotalPdf(totales, "IVA:", iva, font, monedaDoc);
        filaTotalPdf(totales, "Total:", total, font, monedaDoc);
        document.add(totales);
        return document;
    }

    private static @Nonnull String letraImpuesto(@Nonnull LineaDetalle linea) {
        if (linea.getImpuestos() == null || linea.getImpuestos().isEmpty()
                || linea.getImpuestos().get(0).getTarifa() == null) {
            return "E";
        }
        try {
            return Tipo_CodigoImpuesto.getCodigoLetra(
                    linea.getImpuestos().get(0).getTarifa().intValue());
        } catch (IllegalArgumentException e) {
            LOG.warn("Tasa de impuesto sin codigo de letra en NC, el PDF mostrara E: "
                    + linea.getImpuestos().get(0).getTarifa()
                    + " | source=PDFGenerator.letraImpuesto()");
            return "E";
        }
    }

    private static PdfPTable separadorPdf() {
        PdfPTable separador = new PdfPTable(1);
        PdfPCell cell = new PdfPCell();
        cell.setPaddingTop(3f);
        cell.setPaddingBottom(3f);
        cell.setBorder(PdfPCell.BOTTOM);
        cell.setBorderWidthBottom(1f);
        cell.setFixedHeight(1f);
        separador.addCell(cell);
        separador.setWidthPercentage(100);
        return separador;
    }

    private static Paragraph espacioPdf(float leading) {
        Paragraph p = new Paragraph();
        p.setSpacingBefore(4f);
        p.setSpacingAfter(4f);
        p.setLeading(leading);
        return p;
    }

    private static PdfPCell celdaPdf(@Nullable String texto,
                                     com.lowagie.text.Font font,
                                     int alineacion) {
        PdfPCell cell = new PdfPCell(new Phrase(texto == null ? "" : texto, font));
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setHorizontalAlignment(alineacion);
        cell.setPadding(1);
        return cell;
    }

    /** Moneda del comprobante; CRC si el resumen no la trae (datos históricos). */
    private static @Nonnull String monedaCodigo(@Nullable ComprobantesEmitidos c) {
        if (c != null && c.getResumen() != null
                && c.getResumen().getCodigoMoneda() != null
                && c.getResumen().getCodigoMoneda().getCodigoMoneda() != null) {
            return c.getResumen().getCodigoMoneda().getCodigoMoneda();
        }
        return "CRC";
    }

    private static @Nonnull String moneda(@Nullable ComprobantesEmitidos c, @Nullable Object valor) {
        return FormatoVista.moneda(valor, monedaCodigo(c));
    }

    private static void filaTotalPdf(@Nonnull PdfPTable table, @Nonnull String etiqueta,
                                     @Nonnull BigDecimal valor,
                                     com.lowagie.text.Font font,
                                     @Nonnull String monedaCodigo) {
        PdfPCell label = new PdfPCell(new Phrase(etiqueta, font));
        label.setBorder(PdfPCell.NO_BORDER);
        label.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(label);
        PdfPCell value = new PdfPCell(new Phrase(
                FormatoVista.moneda(valor, monedaCodigo), font));
        value.setBorder(PdfPCell.NO_BORDER);
        value.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(value);
    }

    /**
     * Titulo impreso segun el CodigoDocumento. Antes TODO salia como
     * "TIQUETE ELECTRONICO", incluida una factura, una nota de debito o un
     * recibo de pago.
     */
    @Nonnull
    private static String tituloDocumento(@Nullable String codigoDocumento) {
        if (codigoDocumento == null) {
            return "TIQUETE ELECTRONICO";
        }
        return switch (codigoDocumento) {
            case "01" -> "FACTURA ELECTRONICA";
            case "02" -> "NOTA DE CREDITO ELECTRONICA";
            case "03" -> "NOTA DE DEBITO ELECTRONICA";
            case "05" -> "FACTURA ELECTRONICA DE EXPORTACION";
            case "08" -> "FACTURA ELECTRONICA DE COMPRA";
            case "10" -> "RECIBO ELECTRONICO DE PAGO";
            default -> "TIQUETE ELECTRONICO";
        };
    }

    private static void addHeaderLine(@Nonnull java.util.List<String> lines, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value);
        }
    }

    private Document addContentToDocument(ByteArrayOutputStream baos, ConfiguracionAplicacion settings, ComprobantesEmitidos tiqueteElectronico, Clientes cliente, Usuarios user, List<ArticuloCarrito> carrito, BigDecimal pago, BigDecimal vuelto, @Nullable List<EntradaPago> pagos) throws DocumentException {
        // Todo se imprime en el rollo térmico de 80 mm (3nStar POS). El ancho
        // de página se ajusta a los 72 mm imprimibles (no a los 80 mm del
        // papel) para que el driver no encoja el texto; el alto base es
        // 200 mm y crece con las líneas. El driver debe usar una forma de
        // papel de 72 x 200 mm (o continua) y escala 100%.
        String codigoDocumento = tiqueteElectronico.getEncabezado().getCodigoDocumento();
        int lineas = carrito == null ? 0 : carrito.size();
        float altoPagina = 566.93f + Math.max(0, lineas - 3) * 14f; // 200 mm base
        Rectangle pageSize = new Rectangle(204.09f, altoPagina); // 72 mm
        Document document = new Document(pageSize, 6f, 6f, 6f, 6f);
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();

        com.lowagie.text.Font font = new com.lowagie.text.Font();
        font.setSize(9f);
        float leading = 10.5f;

        com.lowagie.text.Font titleFont = new com.lowagie.text.Font(font);
        titleFont.setSize(11f);
        titleFont.setStyle(com.lowagie.text.Font.BOLD);

        Paragraph tiqueteTitulo = new Paragraph(
                tituloDocumento(codigoDocumento), titleFont);
        tiqueteTitulo.setAlignment(Element.ALIGN_CENTER);
        tiqueteTitulo.setLeading(13f);
        document.add(tiqueteTitulo);

        PdfPTable separator = new PdfPTable(1);

        PdfPCell cell = new PdfPCell();
        // Antes eran 20+20+3 = 43 pt por separador; seis separadores dejaban
        // un blanco enorme en la hoja. Ahora la línea es compacta.
        cell.setPaddingTop(3f);
        cell.setPaddingBottom(3f);
        cell.setBorder(PdfPCell.BOTTOM); // Only draw the bottom border
        cell.setBorderWidthBottom(1f); // Set the thickness of the line
        cell.setFixedHeight(1f); // Set the height of the cell to a thin line

        separator.addCell(cell);
        separator.setWidthPercentage(100);
        document.add(separator);

        java.util.List<String> headerLines = new java.util.ArrayList<>();
        addHeaderLine(headerLines, settings.getNombreNegocio());
        if (settings.getNombre() != null
                && !settings.getNombre().equalsIgnoreCase(settings.getNombreNegocio())) {
            addHeaderLine(headerLines, settings.getNombre());
        }
        if (settings.getIdentificacion() != null) {
            addHeaderLine(headerLines, "Identificación: " + settings.getIdentificacion());
        }
        if (settings.getTelefono() != null) {
            addHeaderLine(headerLines, "Tel. " + settings.getTelefono());
        }
        addHeaderLine(headerLines, settings.getCorreoElectronicoTributacion());
        addHeaderLine(headerLines, settings.getDireccionCompleta());
        String combinedTextHeader = String.join("\n", headerLines);

        Paragraph paragraph = new Paragraph(combinedTextHeader, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setLeading(leading);
        document.add(paragraph);

        //BLANK SEPARATOR
        // Add a blank paragraph with a specific height for spacing
        Paragraph blankSpace = new Paragraph();
        blankSpace.setSpacingBefore(4f); // Space before (if needed)
        blankSpace.setSpacingAfter(4f); // Space after (if needed)
        blankSpace.setLeading(leading);
        document.add(blankSpace);

        // Format the date
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        String formattedDate = dateFormat.format(new Date());

        // Combine all the text into a single string
        String combinedText = String.join("\n",
                "Fecha: " + formattedDate,
                "Consecutivo: " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo(),
                "Clave: " + tiqueteElectronico.getEncabezado().getClave(),
                "Número: " + tiqueteElectronico.getId(),
                "Cliente: " + (cliente != null && cliente.getName() != null ? cliente.getName() : "Cliente contado"),
                "Cajero: " + user.getUsername()
        );

        // Create a single paragraph for the combined text
        Paragraph combinedParagraph = new Paragraph(combinedText, font);
        combinedParagraph.setAlignment(Element.ALIGN_LEFT); // Align left
        combinedParagraph.setLeading(leading); // Set the line spacing
        document.add(combinedParagraph); // Add the paragraph to the document

        document.add(blankSpace);

        document.add(separator);

        float[] columnasArticulos = new float[]{1.1f, 2.5f, 1.4f};
        PdfPTable descripcionTable = new PdfPTable(columnasArticulos);
        descripcionTable.setWidthPercentage(100);

        // Create and configure the cell for Cantidad
        PdfPCell cantidadInfoCell = new PdfPCell(new Phrase("Cantidad", font));
        cantidadInfoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
        cantidadInfoCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        cantidadInfoCell.setPadding(1); // Optional: set padding for better spacing
        descripcionTable.addCell(cantidadInfoCell); // Add to table

        PdfPCell descripcionInfoCell = new PdfPCell(new Phrase("Descripción", font));
        descripcionInfoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
        descripcionInfoCell.setHorizontalAlignment(Element.ALIGN_CENTER); // Align center
        descripcionInfoCell.setPadding(1); // Optional: set padding for better spacing
        descripcionTable.addCell(descripcionInfoCell); // Add to table

        PdfPCell montoInfoCell = new PdfPCell(new Phrase("Total", font));
        montoInfoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
        montoInfoCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        montoInfoCell.setPadding(1); // Optional: set padding for better spacing
        descripcionTable.addCell(montoInfoCell); // Add to table

        document.add(descripcionTable);

        document.add(separator); // Fancy separator

        PdfPTable articulosTable = new PdfPTable(columnasArticulos);
        articulosTable.setWidthPercentage(100);
        articulosTable.setSpacingBefore(4f);

        // Agregar filas a la tabla
        for (ArticuloCarrito articulo : carrito) {
            String impuestoStr = articulo.getArticulo().getCodigoCabys().getImpuesto();
            int impuesto = 0;
            if (impuestoStr != null && !impuestoStr.isEmpty()) {
                try {
                    impuesto = new java.math.BigDecimal(impuestoStr).intValue();
                } catch (NumberFormatException ignored) {
                    // A non-integer rate (e.g. 0.5) cannot map to a letter code,
                    // so the line prints as-is with the default mapping. The 0
                    // here only drives the letter lookup, never the amounts —
                    // but a bad catalog value should still be visible.
                    LOG.warn("Impuesto no entero en CABYS, el codigo de letra del PDF usara el valor por defecto: '"
                            + impuestoStr + "'"
                            + " | source=PDFGenerator.generarPDF()");
                }
            }
            String codigoLetra = "E";
            try {
                codigoLetra = Tipo_CodigoImpuesto.getCodigoLetra(impuesto);
            } catch (IllegalArgumentException ignored) {
                // An unmapped rate prints as "E" (exento). Visible, because a
                // wrong letter on a printed fiscal document misstates the tax
                // treatment to whoever reads it.
                LOG.warn("Tasa de impuesto sin codigo de letra, el PDF mostrara E: " + impuesto
                        + " | source=PDFGenerator.generarPDF()");
            }

            // Create and configure the cell for Cantidad
            PdfPCell cantidadCell = new PdfPCell(new Phrase(articulo.getCantidad().toString(), font));
            cantidadCell.setBorder(PdfPCell.NO_BORDER); // Set no border
            cantidadCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
            cantidadCell.setPadding(1); // Optional: set padding for better spacing
            articulosTable.addCell(cantidadCell); // Add to table

            // Create and configure the cell for Descripcion
            PdfPCell descripcionCell = new PdfPCell(new Phrase(articulo.getArticulo().getNombre(), font));
            descripcionCell.setBorder(PdfPCell.NO_BORDER); // Set no border
            descripcionCell.setHorizontalAlignment(Element.ALIGN_CENTER); // Align center
            descripcionCell.setPadding(1); // Optional: set padding for better spacing
            articulosTable.addCell(descripcionCell); // Add to table

            // Create and configure the cell for Monto
            PdfPCell montoCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, articulo.getTotalArticulos()) + " " + codigoLetra, font));
            montoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
            montoCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
            montoCell.setPadding(1); // Optional: set padding for better spacing
            articulosTable.addCell(montoCell); // Add to table
        }

        document.add(articulosTable);

        document.add(separator); // End list separator

        PdfPTable descuentosTable = new PdfPTable(2);
        descuentosTable.setWidthPercentage(100); // Set table width to 100% of the page width

        PdfPCell descuentostotalCell = new PdfPCell(new Phrase("Descuentos: ", font));
        descuentostotalCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        descuentostotalCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        descuentosTable.addCell(descuentostotalCell);

        BigDecimal totalDescuento = tiqueteElectronico.getResumen().getTotalDescuentos();
        PdfPCell descuentovalueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, totalDescuento), font));
        descuentovalueCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        descuentovalueCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        descuentosTable.addCell(descuentovalueCell);

        document.add(descuentosTable);

        // Create a set to store unique promotion names.
        Set<String> promoNames = new HashSet<>();

        // Loop through articulosCarrito.
        // Loop through articulosCarrito.
        for (ArticuloCarrito articulo : carrito) {
            // Check if the articulo is part of any promotion.
            if (articulo.isPromo() && articulo.getPromociones() != null && !articulo.getPromociones().isEmpty()) {
                for (Promocion promo : articulo.getPromociones()) {
                    if (promo != null && promo.getNombre() != null) {
                        promoNames.add(promo.getNombre()); // Set prevents duplicates
                    }
                }
            }
        }

        for (String promoName : promoNames) {
            Paragraph promo = new Paragraph(promoName, font);
            promo.setAlignment(Element.ALIGN_LEFT);
            document.add(promo);
        }

        document.add(separator);

        PdfPTable ivaTable = new PdfPTable(2);
        ivaTable.setWidthPercentage(100); // Set table width to 100% of the page width

        PdfPCell ivatotalCell = new PdfPCell(new Phrase("IVA: ", font));
        ivatotalCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        ivatotalCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        ivaTable.addCell(ivatotalCell);

        BigDecimal totalImpuesto = tiqueteElectronico.getResumen().getTotalImpuesto();
        PdfPCell ivavalueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, totalImpuesto), font));
        ivavalueCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        ivavalueCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        ivaTable.addCell(ivavalueCell);

        document.add(ivaTable);

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100); // Set table width to 100% of the page width

        PdfPCell totalCell = new PdfPCell(new Phrase("Total: ", font));
        totalCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        totalCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        table.addCell(totalCell);

        PdfPCell valueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, tiqueteElectronico.getResumen().getTotalComprobante()), font));
        valueCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        table.addCell(valueCell);

        document.add(table);

        document.add(separator); // End list separator

        PdfPTable paymentTable = new PdfPTable(2);
        paymentTable.setWidthPercentage(100);
        BigDecimal sumaMostrada = BigDecimal.ZERO;
        if (pagos != null && !pagos.isEmpty()) {
            for (EntradaPago pe : pagos) {
                if (pe.getMonto() == null || pe.getMonto().compareTo(BigDecimal.ZERO) <= 0) continue;
                String label = EntradaPago.metodoPagoLabel(pe.getMetodoPago()) + ":";
                PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
                labelCell.setBorder(PdfPCell.NO_BORDER);
                labelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
                paymentTable.addCell(labelCell);
                PdfPCell paymentValueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, pe.getMonto()), font));
                paymentValueCell.setBorder(PdfPCell.NO_BORDER);
                paymentValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                paymentTable.addCell(paymentValueCell);
                sumaMostrada = sumaMostrada.add(pe.getMonto());
            }
        } else {
            PdfPCell labelCell = new PdfPCell(new Phrase("Paga con:", font));
            labelCell.setBorder(PdfPCell.NO_BORDER);
            labelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
            paymentTable.addCell(labelCell);
            PdfPCell fallbackValueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, pago), font));
            fallbackValueCell.setBorder(PdfPCell.NO_BORDER);
            fallbackValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            paymentTable.addCell(fallbackValueCell);
            sumaMostrada = pago;
        }
        PdfPCell totalLabelCell = new PdfPCell(new Phrase("Total Pagado:", font));
        totalLabelCell.setBorder(PdfPCell.NO_BORDER);
        totalLabelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        paymentTable.addCell(totalLabelCell);
        PdfPCell totalValueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, sumaMostrada), font));
        totalValueCell.setBorder(PdfPCell.NO_BORDER);
        totalValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        paymentTable.addCell(totalValueCell);
        PdfPCell vueltoLabelCell = new PdfPCell(new Phrase("Vuelto:", font));
        vueltoLabelCell.setBorder(PdfPCell.NO_BORDER);
        vueltoLabelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        paymentTable.addCell(vueltoLabelCell);
        PdfPCell vueltoValueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, vuelto.negate()), font));
        vueltoValueCell.setBorder(PdfPCell.NO_BORDER);
        vueltoValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        paymentTable.addCell(vueltoValueCell);

        document.add(paymentTable);

        document.add(separator); // End list separator

        Map<BigDecimal, BigDecimal> totalTaxes = ArticuloCarrito.calculateTotalTaxForUniqueRates(carrito);
        // Create a table for taxes
        PdfPTable taxTable = new PdfPTable(2);
        taxTable.setWidthPercentage(100);

        totalTaxes.forEach((rate, total) -> {
            PdfPCell taxLabelCell = new PdfPCell(new Phrase("Impuesto: " + rate + "%", font));
            taxLabelCell.setBorder(PdfPCell.NO_BORDER);
            taxLabelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
            taxTable.addCell(taxLabelCell);

            PdfPCell taxValueCell = new PdfPCell(new Phrase(moneda(tiqueteElectronico, total), font));
            taxValueCell.setBorder(PdfPCell.NO_BORDER);
            taxValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            taxTable.addCell(taxValueCell);
        });

        document.add(taxTable);

        Paragraph codificacion = new Paragraph("Detalle de impuesto:", font);
        codificacion.setAlignment(Element.ALIGN_CENTER);
        document.add(codificacion);

        Paragraph codificacion2 = new Paragraph("'E':0% 'U':1% 'D':2% 'T':13%", font);
        codificacion2.setAlignment(Element.ALIGN_CENTER);
        document.add(codificacion2);

        Paragraph autorizacion = new Paragraph("Autorizado mediante resolucion No. DGT-R033-2019 del dia 20 de junio de 2019. Version FE 4.4", font);
        autorizacion.setAlignment(Element.ALIGN_CENTER);
        document.add(autorizacion);

        // QR code for Hacienda V4.4 — encode the 50-character Clave
        String clave = tiqueteElectronico.getHaciendaClave();
        if (clave != null && !clave.isBlank()) {
            try {
                byte[] qrBytes = QRCodeGenerator.generateQRCodeBytes(clave);
                Image qrImage = Image.getInstance(qrBytes);
                qrImage.setAlignment(Element.ALIGN_CENTER);
                float qrSize = 90f;
                qrImage.scaleToFit(qrSize, qrSize);
                document.add(qrImage);

                Paragraph claveLabel = new Paragraph("Clave: " + clave, font);
                claveLabel.setAlignment(Element.ALIGN_CENTER);
                document.add(claveLabel);
            } catch (WriterException | IOException e) {
                // The v4.4 Hacienda PDF carries a QR with the verification URL;
                // without it the document still prints but loses machine
                // readability, so a failed QR is warn-worthy, not silent.
                LOG.warn("No se pudo generar el QR del comprobante, el PDF sale sin QR: "
                        + e.getMessage()
                        + " | source=PDFGenerator.generarPDF()");
            }
        }

        Paragraph gracias = new Paragraph("***GRACIAS POR SU PREFERENCIA!***", font);
        gracias.setAlignment(Element.ALIGN_CENTER);
        document.add(gracias);

        return document;
    }

    private void savePdfToFileSystem(ByteArrayOutputStream baos, ComprobantesEmitidos tiqueteElectronico) throws IOException {
        saveFacturaPdfToFileSystem(baos, "tiqueteElectronico_" + tiqueteElectronico.getId() + ".pdf");
    }

    private void saveFacturaPdfToFileSystem(ByteArrayOutputStream baos, String fileName) throws IOException {
        String dirPath = dirService.getFacturasDirPath();
        String filePath = dirPath + File.separator + fileName;

        // Create the directory if it doesn't exist.
        //
        // Idempotencia bajo concurrencia: `if (!exists()) { if (!mkdirs()) throw }`
        // es un TOCTOU. Diez cajeros simultaneos entraban los diez con
        // exists() == false, uno creaba el arbol de directorios y los otros nueve
        // recibian mkdirs() == false, que ese codigo confundia con un fallo real
        // -> IOException tragado mas abajo -> ningun PDF en disco ->
        // PosResource.facturar respondia 500 PDF_NO_GENERADO y la venta, ya
        // cobrada y facturada, se perdia. Se comprueba isDirectory() DE NUEVO
        // despues del mkdirs(): si otro hilo lo creo, el directorio esta y se
        // sigue. Solo se propaga el error si el directorio sigue sin existir.
        File directory = new File(dirPath);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Failed to create directory: " + dirPath);
        }

        // Write the ByteArrayOutputStream to a file
        try (OutputStream outputStream = new FileOutputStream(filePath)) {
            baos.writeTo(outputStream);
        }

        // Application root (empty when deployed at "/")
        String baseUrl = "";

        // Store local path (for direct file access, avoids URL round-trip)
        this.pdfLocalPath = filePath;

        // Construct the URL to serve the PDF
        this.pdfUrl = baseUrl + "/facturas/" + fileName;
    }

    @Nullable
    public File generarPDFReportesDepartamentos(@Nonnull List<ReportesFamiliasYDepartamentos> reportes, @Nonnull List<Date> range) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        File pdfFile = null; // Initialize the File object
        BigDecimal totalSum = BigDecimal.ZERO; // Variable to store the total sum

        try {
            try ( // Create the PDF document
                    Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5)) {
                PdfWriter.getInstance(document, baos);
                document.add(new Meta("charset", "UTF-8"));
                document.open();
                // Set font size
                com.lowagie.text.Font font = new com.lowagie.text.Font();
                font.setSize(8);
                // Title
                Paragraph title = new Paragraph("Reporte de Ventas por Departamentos del " + range.get(0) + " hasta el " + range.get(1), font);
                title.setAlignment(Element.ALIGN_CENTER);
                title.setLeading(10f);
                document.add(title);
                // Blank separator
                document.add(new Paragraph("\n"));
                // Create table for report data
                PdfPTable reportTable = new PdfPTable(new float[]{2, 2, 1}); // Adjust column ratios as needed
                reportTable.setWidthPercentage(100);
                reportTable.setSpacingBefore(10f);
                // Table headers
                reportTable.addCell(new Phrase("Departamento", font));
                reportTable.addCell(new Phrase("Total", font));
                reportTable.addCell(new Phrase("Porcentaje", font));
                // Add data to the table
                for (ReportesFamiliasYDepartamentos reporte : reportes) {
                    reportTable.addCell(new Phrase(reporte.getNombre(), font));
                    reportTable.addCell(new Phrase(reporte.getCantidad().toString(), font));
                    reportTable.addCell(new Phrase(reporte.getPorcentaje().toString(), font));

                    // Sum the totals
                    totalSum = totalSum.add(reporte.getCantidad());
                }
                // Add total sum row
                reportTable.addCell(new Phrase("Total", font));
                reportTable.addCell(new Phrase(totalSum.toString(), font));
                reportTable.addCell(new Phrase("100.00", font)); // Empty cell for percentage

                document.add(reportTable);
                // Close the document
            }

            // Save the PDF to the file system
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm");
            String formattedDate = ZonedDateTime.now().format(formatter);
            pdfFile = savePdfToFileSystem(baos, "ReporteVentasXDepartamento_" + formattedDate); // Get the File

        } catch (DocumentException e) {
            LOG.warn("failed to generar p d f");
        }

        return pdfFile; // Return the created File
    }

    @Nullable
    public File generarPDFReportesFamilias(@Nonnull List<ReportesFamiliasYDepartamentos> reportes, @Nonnull List<Date> range) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        File pdfFile = null; // Initialize the File object
        BigDecimal totalSum = BigDecimal.ZERO; // Variable to store the total sum

        try {
            try ( // Create the PDF document
                    Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5)) {
                PdfWriter.getInstance(document, baos);
                document.add(new Meta("charset", "UTF-8"));
                document.open();
                // Set font size
                com.lowagie.text.Font font = new com.lowagie.text.Font();
                font.setSize(8);
                // Title
                Paragraph title = new Paragraph("Reporte de Ventas por Familias del " + range.get(0) + " hasta el " + range.get(1), font);
                title.setAlignment(Element.ALIGN_CENTER);
                title.setLeading(10f);
                document.add(title);
                // Blank separator
                document.add(new Paragraph("\n"));
                // Create table for report data
                PdfPTable reportTable = new PdfPTable(new float[]{2, 2, 1}); // Adjust column ratios as needed
                reportTable.setWidthPercentage(100);
                reportTable.setSpacingBefore(10f);
                // Table headers
                reportTable.addCell(new Phrase("Familia", font));
                reportTable.addCell(new Phrase("Total", font));
                reportTable.addCell(new Phrase("Porcentaje", font));
                // Add data to the table
                for (ReportesFamiliasYDepartamentos reporte : reportes) {
                    reportTable.addCell(new Phrase(reporte.getNombre(), font));
                    reportTable.addCell(new Phrase(reporte.getCantidad().toString(), font));
                    reportTable.addCell(new Phrase(reporte.getPorcentaje().toString(), font));

                    // Sum the totals
                    totalSum = totalSum.add(reporte.getCantidad());
                }
                // Add total sum row
                reportTable.addCell(new Phrase("Total", font));
                reportTable.addCell(new Phrase(totalSum.toString(), font));
                reportTable.addCell(new Phrase("100.00", font)); // Empty cell for percentage

                document.add(reportTable);
                // Close the document
            }

            // Save the PDF to the file system
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm");
            String formattedDate = ZonedDateTime.now().format(formatter);
            pdfFile = savePdfToFileSystem(baos, "ReporteVentasXFamilia_" + formattedDate); // Get the File

        } catch (DocumentException e) {
            LOG.warn("failed to generar p d f");
        }

        return pdfFile; // Return the created File
    }

    private File savePdfToFileSystem(ByteArrayOutputStream baos, String filename) {
        File pdfFile = null; // Initialize the File object
        try {
            // Ensure the PDF directory exists
            dirService.createPDFDir();
            
            pdfFile = new File(dirService.getPDFDirPath(), filename + ".pdf");
            
            // Ensure parent directory exists
            File parentDir = pdfFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    LOG.warn("failed to save pdf to file system");
                    return null;
                }
            }
            
            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                baos.writeTo(fos);
            }
        } catch (IOException e) {
            LOG.warn("failed to save pdf to file system");
        }
        return pdfFile; // Return the created File
    }

    @Nullable
    public File generarPDFReportesVentasXCajero(@Nonnull List<ComprobantesEmitidos> reportes, @Nonnull String username, @Nonnull List<Date> range) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        File pdfFile = null; // Initialize the File object
        BigDecimal totalSum = BigDecimal.ZERO; // Variable to store the total sum

        try {
            try ( // Create the PDF document
                    Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5)) {
                PdfWriter.getInstance(document, baos);
                document.add(new Meta("charset", "UTF-8"));
                document.open();
                // Set font size
                com.lowagie.text.Font font = new com.lowagie.text.Font();
                font.setSize(8);
                // Title
                Paragraph title = new Paragraph("Reporte de Ventas por Cajero del " + range.get(0) + " hasta " + range.get(1), font);
                title.setAlignment(Element.ALIGN_CENTER);
                title.setLeading(10f);
                document.add(title);
                // Blank separator
                document.add(new Paragraph("\n"));
                // Create table for report data
                PdfPTable reportTable = new PdfPTable(new float[]{2, 1, 2}); // Adjust column ratios as needed
                reportTable.setWidthPercentage(100);
                reportTable.setSpacingBefore(10f);
                // Table headers
                reportTable.addCell(new Phrase("Articulo", font));
                reportTable.addCell(new Phrase("Cantidad", font));
                reportTable.addCell(new Phrase("Total", font));
                // Add data to the table

                for (ComprobantesEmitidos facturaEmitida : reportes) {
                    if (facturaEmitida.getDetalles() != null && facturaEmitida.getDetalles().getLineasDetalle() != null) {
                        for (LineaDetalle linea : facturaEmitida.getDetalles().getLineasDetalle()) {
                            if (linea.getMontoTotalLinea() != null) {
                                totalSum = totalSum.add(linea.getMontoTotalLinea());
                                reportTable.addCell(new Phrase(linea.getDetalle(), font));
                                reportTable.addCell(new Phrase(linea.getCantidad().toString(), font));
                                reportTable.addCell(new Phrase(linea.getMontoTotalLinea().toString(), font));
                            }
                        }
                    }
                }

                // Add total sum row
                reportTable.addCell(new Phrase("TOTAL FINAL", font));
                reportTable.addCell(new Phrase("*", font));
                reportTable.addCell(new Phrase(totalSum.toString(), font));

                document.add(reportTable);
                // Close the document
            }

            // Save the PDF to the file system
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm");
            String formattedDate = ZonedDateTime.now().format(formatter);
            pdfFile = savePdfToFileSystem(baos, "ReporteVentasXCajero_" + username + "_" + formattedDate); // Get the File

        } catch (DocumentException e) {
            LOG.warn("failed to generar p d f");
        }

        return pdfFile; // Return the created File
    }

    @Nullable
    public File generarPDFStockAlerts(@Nonnull List<AlertaStock> stockAlerts) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        File pdfFile = null;

        try {
            Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5);
            PdfWriter.getInstance(document, baos);
            document.add(new Meta("charset", "UTF-8"));
            document.open();

            com.lowagie.text.Font font = new com.lowagie.text.Font();
            font.setSize(8);

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");

            Paragraph title = new Paragraph("Reporte de Alertas de Stock", font);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setLeading(10f);
            document.add(title);

            document.add(new Paragraph("\n"));

            PdfPTable reportTable = new PdfPTable(new float[]{1, 2, 2, 1, 1});
            reportTable.setWidthPercentage(100);
            reportTable.setSpacingBefore(10f);

            reportTable.addCell(new Phrase("ID", font));
            reportTable.addCell(new Phrase("Artículo", font));
            reportTable.addCell(new Phrase("Tipo Alerta", font));
            reportTable.addCell(new Phrase("Cant. Actual", font));
            reportTable.addCell(new Phrase("Estado", font));

            for (AlertaStock alert : stockAlerts) {
                reportTable.addCell(new Phrase(String.valueOf(alert.getId()), font));
                reportTable.addCell(new Phrase(alert.getArticulo() != null ? alert.getArticulo().getNombre() : "", font));
                reportTable.addCell(new Phrase(alert.getTipoAlerta() != null ? alert.getTipoAlerta() : "", font));
                reportTable.addCell(new Phrase(alert.getCantidadActual() != null ? alert.getCantidadActual().toString() : "0", font));
                reportTable.addCell(new Phrase(alert.getEstado() != null ? alert.getEstado() : "", font));
            }

            document.add(reportTable);

            document.close();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm");
            String formattedDate = ZonedDateTime.now().format(formatter);
            pdfFile = savePdfToFileSystem(baos, "ReporteStockAlerts_" + formattedDate);

        } catch (DocumentException e) {
            LOG.warn("failed to generar p d f");
        }

        return pdfFile;
    }

    @Nullable
    public File generarPDFProfitMarginSnapshots(@Nonnull List<CorteMargen> marginSnapshots) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        File pdfFile = null;

        try {
            Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5);
            PdfWriter.getInstance(document, baos);
            document.add(new Meta("charset", "UTF-8"));
            document.open();

            com.lowagie.text.Font font = new com.lowagie.text.Font();
            font.setSize(8);

            SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");

            Paragraph title = new Paragraph("Reporte de Márgenes de Ganancia", font);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setLeading(10f);
            document.add(title);

            document.add(new Paragraph("\n"));

            PdfPTable reportTable = new PdfPTable(new float[]{2, 2, 2, 2, 2});
            reportTable.setWidthPercentage(100);
            reportTable.setSpacingBefore(10f);

            reportTable.addCell(new Phrase("Fecha", font));
            reportTable.addCell(new Phrase("Departamento", font));
            reportTable.addCell(new Phrase("Familia", font));
            reportTable.addCell(new Phrase("% Margen", font));
            reportTable.addCell(new Phrase("Total Ventas", font));

            for (CorteMargen snapshot : marginSnapshots) {
                reportTable.addCell(new Phrase(snapshot.getFechaSnapshot() != null ? dateFormat.format(snapshot.getFechaSnapshot()) : "", font));
                reportTable.addCell(new Phrase(snapshot.getDepartamento() != null ? snapshot.getDepartamento() : "", font));
                reportTable.addCell(new Phrase(snapshot.getFamilia() != null ? snapshot.getFamilia() : "", font));
                reportTable.addCell(new Phrase(snapshot.getMargenPromedio() != null ? snapshot.getMargenPromedio().toString() + "%" : "0%", font));
                reportTable.addCell(new Phrase(snapshot.getTotalVentas() != null ? snapshot.getTotalVentas().toString() : "0", font));
            }

            document.add(reportTable);

            document.close();

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd_MM_yyyy_HH_mm");
            String formattedDate = ZonedDateTime.now().format(formatter);
            pdfFile = savePdfToFileSystem(baos, "ReporteProfitMargins_" + formattedDate);

        } catch (DocumentException e) {
            LOG.warn("failed to generar p d f");
        }

        return pdfFile;
    }

}
