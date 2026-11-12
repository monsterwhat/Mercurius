package Controllers.Api.App;

import Models.ComprobantesEmitidos;
import Models.DTO.ApiResponse;
import Services.AppSettingsService;
import Services.FacturaImportService;
import Utils.Parsers.FacturaBackupXml;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

/**
 * Importa los respaldos XML del sistema anterior como ventas (documentos
 * emitidos) en Mercurius.
 *
 * <p>El ZIP se procesa de forma idempotente por {@code haciendaClave}: los
 * documentos que ya existen se omiten. Con {@code simular=true} (por defecto)
 * solo cuenta lo que haría, sin escribir. Los documentos que no son ventas
 * (notas de crédito/débito, recibos) se ignoran.</p>
 */
@Path("/api/app/importacion-facturas")
@RolesAllowed("admin")
@Produces(MediaType.APPLICATION_JSON)
public class ImportacionFacturasResource {

    private static final Logger LOG = Logger.getLogger(ImportacionFacturasResource.class);
    private static final int LOTE = 200;

    @Inject
    FacturaImportService importService;

    @Inject
    AppSettingsService appSettingsService;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importar(
            @RestForm("archivo") @Nullable FileUpload archivo,
            @RestForm("usuario") @Nullable String usuario,
            @RestForm("simular") @DefaultValue("true") boolean simular) {
        if (archivo == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "Debe adjuntar un ZIP con XML."))
                    .build();
        }
        String cajero = usuario == null || usuario.isBlank() ? "importado" : usuario.trim();
        int leidas = 0;
        int ventas = 0;
        int nuevas = 0;
        int omitidas = 0;
        int noVentas = 0;
        int errores = 0;
        int importadas = 0;
        List<String> detalleErrores = new ArrayList<>();

        try {
            byte[] contenido = Files.readAllBytes(archivo.uploadedFile());
            Set<String> claves = importService.clavesExistentes();
            List<ComprobantesEmitidos> lote = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(contenido))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".xml")) {
                        continue;
                    }
                    leidas++;
                    try {
                        String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        String tipo = FacturaBackupXml.tipoDocumento(xml);
                        if (!FacturaBackupXml.esVenta(tipo)) {
                            noVentas++;
                            continue;
                        }
                        ventas++;
                        ComprobantesEmitidos comprobante = FacturaBackupXml.parse(xml, cajero);
                        if (!claves.add(comprobante.getHaciendaClave())) {
                            omitidas++;
                            continue;
                        }
                        nuevas++;
                        if (!simular) {
                            lote.add(comprobante);
                            if (lote.size() >= LOTE) {
                                importadas += importService.persistirLote(lote);
                                lote.clear();
                            }
                        }
                    } catch (Exception e) {
                        errores++;
                        if (detalleErrores.size() < 20) {
                            detalleErrores.add(entry.getName() + ": " + e.getMessage());
                        }
                    }
                }
            }
            if (!simular && !lote.isEmpty()) {
                importadas += importService.persistirLote(lote);
            }
        } catch (IOException e) {
            LOG.warn("No se pudo leer el ZIP de facturas: " + e.getMessage()
                    + " | source=ImportacionFacturasResource.importar()");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("READ_ERROR", "No se pudo leer el archivo: " + e.getMessage()))
                    .build();
        }

        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("archivo", archivo.fileName());
        resultado.put("cajero", cajero);
        resultado.put("simulacion", simular);
        resultado.put("leidas", leidas);
        resultado.put("ventas", ventas);
        resultado.put("nuevas", nuevas);
        resultado.put("importadas", importadas);
        resultado.put("omitidas", omitidas);
        resultado.put("noVentas", noVentas);
        resultado.put("errores", errores);
        resultado.put("detalleErrores", detalleErrores);
        LOG.info("Importacion de facturas: " + resultado
                + " | source=ImportacionFacturasResource.importar()");
        return Response.ok(ApiResponse.ok(resultado)).build();
    }

    /**
     * Limpieza puntual: elimina las facturas de compra (08) importadas como
     * ventas. Es idempotente y solo afecta al usuario indicado.
     */
    @POST
    @Path("/limpiar-compras")
    public Response limpiarCompras(@QueryParam("usuario") @DefaultValue("importado") String usuario) {
        int eliminadas = importService.eliminarComprasImportadas(usuario);
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("usuario", usuario);
        resultado.put("eliminadas", eliminadas);
        LOG.info("Limpieza de compras importadas: " + resultado
                + " | source=ImportacionFacturasResource.limpiarCompras()");
        return Response.ok(ApiResponse.ok(resultado)).build();
    }

    /**
     * Limpieza de compras que entraron con raíz de factura normal: se borran
     * las importadas cuyo receptor es la propia empresa (cédula configurada).
     */
    @POST
    @Path("/limpiar-receptor-propio")
    public Response limpiarReceptorPropio(
            @QueryParam("usuario") @DefaultValue("importado") String usuario) {
        Models.ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
        String cedula = settings == null ? null : settings.getIdentificacion();
        if (cedula == null || cedula.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR",
                            "Falta la identificación del emisor en la configuración."))
                    .build();
        }
        int eliminadas = importService.eliminarImportadasConReceptorPropio(usuario, cedula.trim());
        Map<String, Object> resultado = new LinkedHashMap<>();
        resultado.put("usuario", usuario);
        resultado.put("identificacion", cedula);
        resultado.put("eliminadas", eliminadas);
        LOG.info("Limpieza de importadas con receptor propio: " + resultado
                + " | source=ImportacionFacturasResource.limpiarReceptorPropio()");
        return Response.ok(ApiResponse.ok(resultado)).build();
    }
}
