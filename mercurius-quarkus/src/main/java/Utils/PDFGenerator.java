package Utils;

import Controllers.Settings.SettingsDirController;
import Models.AppSettings;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Promocion;
import Models.Clients;
import Models.ComprobantesV44.ComprobantesEmitidos;
import Models.ComprobantesV44.Detalles.LineaDetalle;
import Models.ComprobantesV44.Enums.Tipo_CodigoImpuesto;
import Models.ReportesFamiliasYDepartamentos;
import Models.Users;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Meta;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
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

    private String pdfUrl;
    @Inject
    SettingsDirController dirController;

    public void generarPDFTiqueteElectronico(ComprobantesEmitidos tiqueteElectronico, AppSettings settings, List<ArticuloCarrito> carrito, Clients cliente, Users user, BigDecimal pago, BigDecimal vuelto) {
        // PDF generation logic here
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try {
            // Add content to the document
            Document document = addContentToDocument(baos, settings, tiqueteElectronico, cliente, user, carrito, pago, vuelto);

            // Close the document after finishing adding content
            document.close();

            savePdfToFileSystem(baos, tiqueteElectronico);

        } catch (DocumentException | IOException e) {
            System.out.println("Error: " + e.getLocalizedMessage());
        }

    }

    private Document addContentToDocument(ByteArrayOutputStream baos, AppSettings settings, ComprobantesEmitidos tiqueteElectronico, Clients cliente, Users user, List<ArticuloCarrito> carrito, BigDecimal pago, BigDecimal vuelto) throws DocumentException {
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
            int impuesto = articulo.getArticulo().getCodigoCabys().getImpuesto();
            String codigoLetra = Tipo_CodigoImpuesto.getCodigoLetra(impuesto);

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
        paymentTable.setWidthPercentage(100); // Set table width to 100% of the page width

        PdfPCell pagaConCell = new PdfPCell(new Phrase("Paga con: ", font));
        pagaConCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        pagaConCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        paymentTable.addCell(pagaConCell);

        PdfPCell pagoValueCell = new PdfPCell(new Phrase(pago.toString(), font));
        pagoValueCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        pagoValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        paymentTable.addCell(pagoValueCell);

        PdfPCell vueltoCell = new PdfPCell(new Phrase("Vuelto: ", font));
        vueltoCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        vueltoCell.setHorizontalAlignment(Element.ALIGN_LEFT); // Align left
        paymentTable.addCell(vueltoCell);

        PdfPCell vueltoValueCell = new PdfPCell(new Phrase(vuelto.negate().toString(), font));
        vueltoValueCell.setBorder(PdfPCell.NO_BORDER); // Optional: Remove border for a cleaner look
        vueltoValueCell.setHorizontalAlignment(Element.ALIGN_RIGHT); // Align right
        paymentTable.addCell(vueltoValueCell);

        document.add(paymentTable);

        document.add(separator); // End list separator

        Map<Integer, BigDecimal> totalTaxes = ArticuloCarrito.calculateTotalTaxForUniqueRates(carrito);
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

        Paragraph autorizacion = new Paragraph("Autorizado mediante resolucion No. DGT-R033-2019 del dia 20 de junio de 2019. Version FE 4.3", font);
        autorizacion.setAlignment(Element.ALIGN_CENTER);
        document.add(autorizacion);

        Paragraph gracias = new Paragraph("***GRACIAS POR SU PREFERENCIA!***", font);
        gracias.setAlignment(Element.ALIGN_JUSTIFIED_ALL);
        document.add(gracias);

        return document;
    }

    private void savePdfToFileSystem(ByteArrayOutputStream baos, ComprobantesEmitidos tiqueteElectronico) throws IOException {
        String fileName = "tiqueteElectronico_" + tiqueteElectronico.getId() + ".pdf";

        // Use the directory path from the dirController
        String dirPath = dirController.getFacturasDirPath();
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

        // Construct the URL to serve the PDF
        this.pdfUrl = baseUrl + "/facturas/" + fileName;
    }

    public File generarPDFReportesDepartamentos(List<ReportesFamiliasYDepartamentos> reportes, List<Date> range) {
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
            System.out.println("Error generating PDF: " + e.getLocalizedMessage());
        }

        return pdfFile; // Return the created File
    }

    public File generarPDFReportesFamilias(List<ReportesFamiliasYDepartamentos> reportes, List<Date> range) {
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
            System.out.println("Error generating PDF: " + e.getLocalizedMessage());
        }

        return pdfFile; // Return the created File
    }

    private File savePdfToFileSystem(ByteArrayOutputStream baos, String filename) {
        File pdfFile = null; // Initialize the File object
        try {
            // Ensure the PDF directory exists
            dirController.createPDFDir();
            
            pdfFile = new File(dirController.getPDFDirPath(), filename + ".pdf");
            
            // Ensure parent directory exists
            File parentDir = pdfFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                if (!parentDir.mkdirs()) {
                    System.err.println("Failed to create directory: " + parentDir.getAbsolutePath());
                    return null;
                }
            }
            
            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                baos.writeTo(fos);
            }
        } catch (IOException e) {
            System.out.println("Error saving PDF to file system: " + e.getLocalizedMessage());
        }
        return pdfFile; // Return the created File
    }

    public File generarPDFReportesVentasXCajero(List<ComprobantesEmitidos> reportes, String username, List<Date> range) {
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
            System.out.println("Error generating PDF: " + e.getLocalizedMessage());
        }

        return pdfFile; // Return the created File
    }

}
