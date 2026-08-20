package Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Services.DirectoryService;
import Models.AppSettings;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Promocion;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Enums.Tipo_CodigoImpuesto;
import Models.ReportesFamiliasYDepartamentos;
import Models.StockAlert;
import Models.PagoEntry;
import Models.ProfitMarginSnapshot;
import Models.Users;
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
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import Services.AlertasService;
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

    @Nullable
    private String pdfUrl;

    @Nullable
    private String pdfLocalPath;
    @Inject
    DirectoryService dirService;
    @Inject
    AlertasService alertasService;

    public void generarPDFTiqueteElectronico(@Nonnull ComprobantesEmitidos tiqueteElectronico, @Nonnull AppSettings settings, @Nonnull List<ArticuloCarrito> carrito, @Nonnull Clients cliente, @Nonnull Users user, @Nonnull BigDecimal pago, @Nonnull BigDecimal vuelto, @Nullable List<PagoEntry> pagos) {
        // PDF generation logic here
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Add content to the document
            Document document = addContentToDocument(baos, settings, tiqueteElectronico, cliente, user, carrito, pago, vuelto, pagos);

            // Close the document after finishing adding content
            document.close();

            savePdfToFileSystem(baos, tiqueteElectronico);

        } catch (DocumentException | IOException e) {
            alertasService.registrarAlerta("Error", "Error: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.generarPDFTiqueteElectronico()", null, e.getLocalizedMessage());
        }

    }

    private Document addContentToDocument(ByteArrayOutputStream baos, AppSettings settings, ComprobantesEmitidos tiqueteElectronico, Clients cliente, Users user, List<ArticuloCarrito> carrito, BigDecimal pago, BigDecimal vuelto, @Nullable List<PagoEntry> pagos) throws DocumentException {
        Document document = new Document(new Rectangle(200f, 600f), 5, 5, 5, 5);
        PdfWriter.getInstance(document, baos);
        document.add(new Meta("charset", "UTF-8"));
        document.open();

        // Establecer el tamaño de la fuente
        com.lowagie.text.Font font = new com.lowagie.text.Font();
        font.setSize(8); // Establecer tamaño de fuente a 8 puntos

        Paragraph tiqueteTitulo = new Paragraph("TIQUETE ELECTRONICO", font);
        tiqueteTitulo.setAlignment(Element.ALIGN_CENTER);
        tiqueteTitulo.setLeading(10f);
        document.add(tiqueteTitulo);

        PdfPTable separator = new PdfPTable(1);

        PdfPCell cell = new PdfPCell();
        cell.setPaddingTop(20f);
        cell.setPaddingBottom(20f);
        cell.setBorder(PdfPCell.BOTTOM); // Only draw the bottom border
        cell.setBorderWidthBottom(1f); // Set the thickness of the line
        cell.setFixedHeight(3f); // Set the height of the cell to a thin line

        separator.addCell(cell);
        separator.setWidthPercentage(100);
        document.add(separator);

        String combinedTextHeader = String.join("\n",
                settings.getNombreNegocio(),
                settings.getNombre(),
                "Ced. " + settings.getIdentificacion(),
                "Tel. " + settings.getTelefono(),
                settings.getCorreoElectronicoTributacion(),
                settings.getDireccionCompleta()
        );

        Paragraph paragraph = new Paragraph(combinedTextHeader, font);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setLeading(10f);
        document.add(paragraph);

        //BLANK SEPARATOR
        // Add a blank paragraph with a specific height for spacing
        Paragraph blankSpace = new Paragraph();
        blankSpace.setSpacingBefore(4f); // Space before (if needed)
        blankSpace.setSpacingAfter(4f); // Space after (if needed)
        blankSpace.setLeading(10f);
        document.add(blankSpace);

        // Format the date
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");
        String formattedDate = dateFormat.format(new Date());

        // Combine all the text into a single string
        String combinedText = String.join("\n",
                "Fecha: " + formattedDate,
                "Consecutivo: " + tiqueteElectronico.getEncabezado().getNumeroConsecutivo(),
                "Clave numerica: " + tiqueteElectronico.getEncabezado().getClave(),
                "Numero: " + tiqueteElectronico.getId(),
                "Cliente: " + (cliente != null && cliente.getName() != null ? cliente.getName() : "CLIENTE CONTADO"),
                "Cajero: " + user.getUsername()
        );

        // Create a single paragraph for the combined text
        Paragraph combinedParagraph = new Paragraph(combinedText, font);
        combinedParagraph.setAlignment(Element.ALIGN_LEFT); // Align left
        combinedParagraph.setLeading(10f); // Set the line spacing
        document.add(combinedParagraph); // Add the paragraph to the document

        document.add(blankSpace);

        document.add(separator);

        PdfPTable descripcionTable = new PdfPTable(new float[]{1, 1, 1});
        descripcionTable.setWidthPercentage(100);

        // Create and configure the cell for Cantidad
        PdfPCell cantidadInfoCell = new PdfPCell(new Phrase("Cantidad", font));
        cantidadInfoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
        cantidadInfoCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        cantidadInfoCell.setPadding(1); // Optional: set padding for better spacing
        descripcionTable.addCell(cantidadInfoCell); // Add to table

        PdfPCell descripcionInfoCell = new PdfPCell(new Phrase("Descripcion", font));
        descripcionInfoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
        descripcionInfoCell.setHorizontalAlignment(Element.ALIGN_CENTER); // Align center
        descripcionInfoCell.setPadding(1); // Optional: set padding for better spacing
        descripcionTable.addCell(descripcionInfoCell); // Add to table

        PdfPCell montoInfoCell = new PdfPCell(new Phrase("Monto", font));
        montoInfoCell.setBorder(PdfPCell.NO_BORDER); // Set no border
        montoInfoCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        montoInfoCell.setPadding(1); // Optional: set padding for better spacing
        descripcionTable.addCell(montoInfoCell); // Add to table

        document.add(descripcionTable);

        document.add(separator); // Fancy separator

        PdfPTable articulosTable = new PdfPTable(new float[]{1, 3, 1});
        articulosTable.setWidthPercentage(100);
        articulosTable.setSpacingBefore(10f);

        // Agregar filas a la tabla
        for (ArticuloCarrito articulo : carrito) {
            String impuestoStr = articulo.getArticulo().getCodigoCabys().getImpuesto();
            int impuesto = 0;
            if (impuestoStr != null && !impuestoStr.isEmpty()) {
                try {
                    impuesto = new java.math.BigDecimal(impuestoStr).intValue();
                } catch (NumberFormatException ignored) {
                    // Non-integer rate (e.g. 0.5) — default to 0 for PDF letter mapping
                }
            }
            String codigoLetra = "E";
            try {
                codigoLetra = Tipo_CodigoImpuesto.getCodigoLetra(impuesto);
            } catch (IllegalArgumentException ignored) {
                // Unknown rate — default to "E" (exento)
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
            PdfPCell montoCell = new PdfPCell(new Phrase(articulo.getTotalArticulos().toPlainString() + " " + codigoLetra, font));
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
        PdfPCell descuentovalueCell = new PdfPCell(new Phrase(totalDescuento.toPlainString(), font));
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
        PdfPCell ivavalueCell = new PdfPCell(new Phrase(totalImpuesto.toPlainString(), font));
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

        PdfPCell valueCell = new PdfPCell(new Phrase(tiqueteElectronico.getResumen().getTotalComprobante().toString(), font));
        valueCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        valueCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        table.addCell(valueCell);

        document.add(table);

        document.add(separator); // End list separator

        PdfPTable paymentTable = new PdfPTable(2);
        paymentTable.setWidthPercentage(100);
        BigDecimal sumaMostrada = BigDecimal.ZERO;
        if (pagos != null && !pagos.isEmpty()) {
            for (PagoEntry pe : pagos) {
                if (pe.getMonto() == null || pe.getMonto().compareTo(BigDecimal.ZERO) <= 0) continue;
                String label = PagoEntry.metodoPagoLabel(pe.getMetodoPago()) + ":";
                PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
                labelCell.setBorder(PdfPCell.NO_BORDER);
                labelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
                paymentTable.addCell(labelCell);
                PdfPCell paymentValueCell = new PdfPCell(new Phrase(pe.getMonto().toString(), font));
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
            PdfPCell fallbackValueCell = new PdfPCell(new Phrase(pago.toString(), font));
            fallbackValueCell.setBorder(PdfPCell.NO_BORDER);
            fallbackValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            paymentTable.addCell(fallbackValueCell);
            sumaMostrada = pago;
        }
        PdfPCell totalLabelCell = new PdfPCell(new Phrase("Total Pagado:", font));
        totalLabelCell.setBorder(PdfPCell.NO_BORDER);
        totalLabelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        paymentTable.addCell(totalLabelCell);
        PdfPCell totalValueCell = new PdfPCell(new Phrase(sumaMostrada.toString(), font));
        totalValueCell.setBorder(PdfPCell.NO_BORDER);
        totalValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        paymentTable.addCell(totalValueCell);
        PdfPCell vueltoLabelCell = new PdfPCell(new Phrase("Vuelto:", font));
        vueltoLabelCell.setBorder(PdfPCell.NO_BORDER);
        vueltoLabelCell.setHorizontalAlignment(Element.ALIGN_LEFT);
        paymentTable.addCell(vueltoLabelCell);
        PdfPCell vueltoValueCell = new PdfPCell(new Phrase(vuelto.negate().toString(), font));
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

            PdfPCell taxValueCell = new PdfPCell(new Phrase(total.toPlainString(), font));
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

        // QR code for Hacienda V4.4 — encode the 50-digit Clave
        String clave = tiqueteElectronico.getHaciendaClave();
        if (clave != null && !clave.isBlank()) {
            try {
                byte[] qrBytes = QRCodeGenerator.generateQRCodeBytes(clave);
                Image qrImage = Image.getInstance(qrBytes);
                qrImage.setAlignment(Element.ALIGN_CENTER);
                qrImage.scaleToFit(120f, 120f);
                document.add(qrImage);

                Paragraph claveLabel = new Paragraph("Clave: " + clave, font);
                claveLabel.setAlignment(Element.ALIGN_CENTER);
                document.add(claveLabel);
            } catch (WriterException | IOException e) {
                // QR generation failed — continue without QR
            }
        }

        Paragraph gracias = new Paragraph("***GRACIAS POR SU PREFERENCIA!***", font);
        gracias.setAlignment(Element.ALIGN_JUSTIFIED_ALL);
        document.add(gracias);

        return document;
    }

    private void savePdfToFileSystem(ByteArrayOutputStream baos, ComprobantesEmitidos tiqueteElectronico) throws IOException {
        String fileName = "tiqueteElectronico_" + tiqueteElectronico.getId() + ".pdf";

        String dirPath = dirService.getFacturasDirPath();
        String filePath = dirPath + File.separator + fileName;

        // Create the directory if it doesn't exist
        File directory = new File(dirPath);
        if (!directory.exists()) {
            boolean created = directory.mkdirs(); // Create the directory
            if (!created) {
                throw new IOException("Failed to create directory: " + dirPath);
            }
        }

        // Write the ByteArrayOutputStream to a file
        try (OutputStream outputStream = new FileOutputStream(filePath)) {
            baos.writeTo(outputStream);
        }

        // Get the application's base URL
        FacesContext facesContext = FacesContext.getCurrentInstance();
        ExternalContext externalContext = facesContext.getExternalContext();
        String baseUrl = externalContext.getRequestScheme() + "://"
                + externalContext.getRequestServerName() + ":"
                + externalContext.getRequestServerPort()
                + externalContext.getRequestContextPath();

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
            alertasService.registrarAlerta("Error", "Error generating PDF: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.generarPDF()", null, e.getLocalizedMessage());
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
            alertasService.registrarAlerta("Error", "Error generating PDF: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.generarPDF()", null, e.getLocalizedMessage());
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
                    alertasService.registrarAlerta("Error", "Failed to create directory: " + parentDir.getAbsolutePath(), null, 0, "PDFGenerator.savePdfToFileSystem()", null, null);
                    return null;
                }
            }
            
            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                baos.writeTo(fos);
            }
        } catch (IOException e) {
            alertasService.registrarAlerta("Error", "Error saving PDF to file system: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.savePdfToFileSystem()", null, e.getLocalizedMessage());
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
            alertasService.registrarAlerta("Error", "Error generating PDF: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.generarPDF()", null, e.getLocalizedMessage());
        }

        return pdfFile; // Return the created File
    }

    @Nullable
    public File generarPDFStockAlerts(@Nonnull List<StockAlert> stockAlerts) {
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

            for (StockAlert alert : stockAlerts) {
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
            alertasService.registrarAlerta("Error", "Error generating PDF: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.generarPDF()", null, e.getLocalizedMessage());
        }

        return pdfFile;
    }

    @Nullable
    public File generarPDFProfitMarginSnapshots(@Nonnull List<ProfitMarginSnapshot> marginSnapshots) {
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

            for (ProfitMarginSnapshot snapshot : marginSnapshots) {
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
            alertasService.registrarAlerta("Error", "Error generating PDF: " + e.getLocalizedMessage(), null, 0, "PDFGenerator.generarPDF()", null, e.getLocalizedMessage());
        }

        return pdfFile;
    }

}
