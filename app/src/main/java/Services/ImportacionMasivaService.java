package Services;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.ClienteActividad;
import Models.Clientes;
import Models.Departamento;
import Models.DTO.ImportacionMasivaColumna;
import Models.DTO.ImportacionMasivaFilaResultado;
import Models.DTO.ImportacionMasivaResultado;
import Models.Enums.TipoRefrigeracion;
import Models.Familia;
import Models.ImportacionMasivaEntidad;
import Models.Usuarios;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jboss.logging.Logger;

/**
 * Bulk import of the four writable catalogs — clientes, artículos, CABYS and
 * precios — the exact mirror of the {@code /api/app/export} datasets.
 *
 * <p><b>Round-trip contract.</b> Every template header reuses the caption
 * already emitted by {@code Utils.ReportExporter} for the matching dataset, so
 * an exported workbook can be re-uploaded as an import sheet without renaming
 * a single column, and {@link #plantillaXlsx(ImportacionMasivaEntidad)} hands
 * back those same headers with one example row.</p>
 *
 * <p><b>Three-phase pipeline.</b> {@link #importar} (1) decodes the workbook,
 * (2) validates the header against the declared schema and then each row
 * against the existing services and their business rules, and (3) — only when
 * {@code simulacion == false} — persists the accepted rows. Nothing is written
 * in dry-run mode.</p>
 *
 * <p><b>No silent drops.</b> Every data row present in the sheet yields exactly
 * one {@link ImportacionMasivaFilaResultado} carrying either the acceptance
 * reason or the rejection reason. The {@code create}/{@code update} service
 * methods swallow {@code PersistenceException} (see {@link GService}), so each
 * write is confirmed by a re-read; a write that does not land is reported as a
 * rejected row instead of vanishing from the counts.</p>
 *
 * <p><b>Per-row atomicity.</b> The entry point is {@link TxType#SUPPORTS}, not
 * REQUIRED: each row commits in its own transaction, so one bad row cannot roll
 * back the rows already imported.</p>
 */
@Named
@ApplicationScoped
public class ImportacionMasivaService {

    private static final Logger LOG = Logger.getLogger(ImportacionMasivaService.class);

    /** 13-digit CABYS catalog key, per the Hacienda CAByS catalog. */
    private static final Pattern RE_CABYS = Pattern.compile("\\d{13}");
    /** 6-digit CIIU4 economic-activity code (ClienteActividad.codigo). */
    private static final Pattern RE_ACTIVIDAD = Pattern.compile("\\d{6}");

    private static final String ESTADO_ACTIVO = "ACTIVO";
    private static final String ESTADO_INACTIVO = "INACTIVO";

    /** Upper bound of the CABYS tax-rate column (percent). */
    private static final BigDecimal CIEN = new BigDecimal("100");

    @Nonnull
    @Inject
    ClientService clientService;

    @Nonnull
    @Inject
    ArticulosService articulosService;

    @Nonnull
    @Inject
    ArticuloPrecioService precioService;

    @Nonnull
    @Inject
    CabysService cabysService;

    @Nonnull
    @Inject
    DepartamentoService departamentoService;

    @Nonnull
    @Inject
    FamiliaService familiaService;

    @Nonnull
    @Inject
    MargenCalculadora margenCalculadora;

    @Nonnull
    @Inject
    SecurityIdentity identity;

    @Nonnull
    @Inject
    LoginService loginService;

    // ── Esquema ───────────────────────────────────────────────────────────────

    /**
     * Declared columns per target, in sheet order. A {@code requerido} column
     * that is missing from the header fails the whole file; a {@code requerido}
     * column that is blank on a row rejects that row only.
     */
    @Nonnull
    public List<ImportacionMasivaColumna> columnas(@Nonnull ImportacionMasivaEntidad entidad) {
        return switch (entidad) {
            case CLIENTES -> List.of(
                    new ImportacionMasivaColumna("Nombre", true,
                            "Nombre o razón social del cliente.", "Cliente de Prueba"),
                    new ImportacionMasivaColumna("Cedula", false,
                            "Cédula física/jurídica. Clave natural: si ya existe, la fila actualiza ese cliente.",
                            "118820456"),
                    new ImportacionMasivaColumna("Codigo", false,
                            "Código interno del cliente (clave primaria). Tiene prioridad sobre 'Cedula'.",
                            "15"),
                    new ImportacionMasivaColumna("Tipo Identificacion", false,
                            "Uno de: Cédula Física, Cédula Jurídica, DIMEX, NITE, "
                                    + "Extranjero No Domiciliado, No Contribuyente.", "Cédula Física"),
                    new ImportacionMasivaColumna("Email", false, "Correo electrónico.", "cliente@correo.cr"),
                    new ImportacionMasivaColumna("Telefono", false, "Teléfono; conserva ceros iniciales.", "88881234"),
                    new ImportacionMasivaColumna("Direccion", false, "Dirección física.", "Av. Central, San José"),
                    new ImportacionMasivaColumna("Provincia", false,
                            "Código de provincia de Hacienda, 1 dígito (01 = San José).", "01"),
                    new ImportacionMasivaColumna("Canton", false,
                            "Código de cantón de Hacienda, 2 dígitos (01 = San José).", "01"),
                    new ImportacionMasivaColumna("Distrito", false,
                            "Código de distrito de Hacienda, 2 dígitos (01 = Carmen).", "01"),
                    new ImportacionMasivaColumna("Fecha Nacimiento", false,
                            "Fecha de nacimiento: yyyy-MM-dd, dd/MM/yyyy o dd-MM-yyyy.", "1990-05-14"),
                    new ImportacionMasivaColumna("Contribuyente", false,
                            "Si/No (también Sí, true, 1).", "Sí"),
                    new ImportacionMasivaColumna("Codigo Zona", false, "Código de zona (entero ≥ 0).", "1"),
                    new ImportacionMasivaColumna("Actividad Economica", false,
                            "Códigos CIIU4 de 6 dígitos, separados por ';' (opcional).", "465100;471101"));
            case ARTICULOS -> List.of(
                    new ImportacionMasivaColumna("Nombre", true, "Nombre del artículo.", "Leche entera 1 L"),
                    new ImportacionMasivaColumna("Codigo de Barras", false,
                            "Código de barras. Clave natural: si ya existe, la fila actualiza ese artículo.",
                            "7441026001015"),
                    new ImportacionMasivaColumna("Descripcion", false, "Descripción del artículo.", "Leche de vaca entera"),
                    new ImportacionMasivaColumna("Unidad de Medida", false, "Unidad de medida.", "Unidad"),
                    new ImportacionMasivaColumna("Unidad de Medida Comercial", false,
                            "Unidad de medida comercial.", "Botella"),
                    new ImportacionMasivaColumna("Departamento", true,
                            "Nombre exacto de un departamento activo.", "Lácteos"),
                    new ImportacionMasivaColumna("Familia", true, "Nombre exacto de una familia.", "Leches"),
                    new ImportacionMasivaColumna("Codigo Cabys", false,
                            "Código CABYS de 13 dígitos ya existente en el catálogo.", "0111010010010"),
                    new ImportacionMasivaColumna("Tipo Refrigeracion", false,
                            "NINGUNA, REFRIGERADO o CONGELADO.", "REFRIGERADO"),
                    new ImportacionMasivaColumna("Exento", false,
                            "Si/No: indica si el artículo es exonerado.", "No"),
                    new ImportacionMasivaColumna("Stock Optimo", false, "Stock óptimo (entero ≥ 0).", "40"),
                    new ImportacionMasivaColumna("Dias Stock Seguridad", false,
                            "Días de stock de seguridad (entero ≥ 0).", "7"));
            case CABYS -> List.of(
                    new ImportacionMasivaColumna("Codigo", true, "Código CABYS de 13 dígitos.", "0111010010010"),
                    new ImportacionMasivaColumna("Descripcion", false,
                            "Descripción según el catálogo de Hacienda.", "Bovinos para reproducción"),
                    new ImportacionMasivaColumna("Categorias", false, "Categorías separadas por '; '.", "Animales; Bovinos"),
                    new ImportacionMasivaColumna("Impuesto", true,
                            "Porcentaje de impuesto, entero entre 0 y 100 (0 = exonerado).", "13"),
                    new ImportacionMasivaColumna("URI", false,
                            "URI oficial del código en el catálogo de Hacienda.",
                            "https://api.hacienda.go.cr/fe/cabys/0111010010010"),
                    new ImportacionMasivaColumna("Estado", false,
                            "ACTIVO o INACTIVO. Si se omite, se asume ACTIVO.", "ACTIVO"));
            case PRECIOS -> List.of(
                    new ImportacionMasivaColumna("Codigo Articulo", false,
                            "Código interno del artículo. Alternativa a 'Codigo de Barras'.", "12"),
                    new ImportacionMasivaColumna("Codigo de Barras", false,
                            "Código de barras del artículo. Alternativa a 'Codigo Articulo'.", "7441026001015"),
                    new ImportacionMasivaColumna("Precio Costo sin IVA", true,
                            "Costo sin IVA en colones. Acepta ₡1.234,56 · 1234.56 · 1,234.56.", "1234.56"),
                    new ImportacionMasivaColumna("Porcentaje Utilidad", false,
                            "Utilidad en %. Si se omite, se usa el margen del sistema según el tipo de "
                                    + "refrigeración del artículo.", "25"),
                    new ImportacionMasivaColumna("Precio Final", false,
                            "Precio de venta con IVA. Si se omite, se calcula con el impuesto del CABYS "
                                    + "del artículo.", "1543"),
                    new ImportacionMasivaColumna("Fecha Compra", false,
                            "Fecha de la fila de precio: yyyy-MM-dd, dd/MM/yyyy o dd-MM-yyyy. "
                                    + "Si ya existe una fila de precio del mismo artículo con esa fecha, "
                                    + "se recalcula en lugar de duplicarse.", "2026-09-01"));
        };
    }

    // ── Punto de entrada ──────────────────────────────────────────────────────

    /**
     * Validates (and, unless {@code simulacion}, applies) an uploaded workbook.
     *
     * @param entidad       target catalog
     * @param nombreArchivo original file name, used for the response echo and
     *                      format detection
     * @param contenido     raw bytes ({@code .xlsx} or {@code .csv})
     * @param simulacion    dry run: validate and report, persist nothing
     * @return the per-row ledger plus the aggregate counts
     * @throws EsquemaInvalidoException when the file cannot be decoded or the
     *         header does not carry the required columns
     */
    @Nonnull
    @Transactional(TxType.SUPPORTS)
    public ImportacionMasivaResultado importar(@Nonnull ImportacionMasivaEntidad entidad,
                                              @Nullable String nombreArchivo,
                                              @Nonnull byte[] contenido,
                                              boolean simulacion)
            throws EsquemaInvalidoException {

        List<ImportacionMasivaColumna> columnas = columnas(entidad);
        List<List<String>> celdas = leerCeldas(nombreArchivo, contenido);
        if (celdas.isEmpty()) {
            throw new EsquemaInvalidoException("El archivo está vacío.");
        }
        List<String> cabecera = celdas.get(0);
        if (filaVacia(cabecera)) {
            throw new EsquemaInvalidoException(
                    "La primera fila del archivo debe contener los encabezados de las columnas.");
        }
        if (celdas.size() < 2) {
            throw new EsquemaInvalidoException("El archivo no contiene filas de datos.");
        }
        Map<String, Integer> mapa = mapearEncabezados(cabecera, columnas);

        ImportacionMasivaResultado resultado = new ImportacionMasivaResultado(
                entidad.getClave(), entidad.getEtiqueta(), nombreArchivo, simulacion, columnas);

        // One client snapshot per run: ClientService exposes no cédula lookup, and
        // listAllOrThrow() (unlike listAll()) never flattens a failed query into an
        // empty result that would silently turn every row into an insert.
        Map<String, Clientes> clientesPorCedula = new LinkedHashMap<>();
        Map<String, Clientes> clientesPorNombre = new LinkedHashMap<>();
        if (entidad == ImportacionMasivaEntidad.CLIENTES) {
            List<Clientes> existentes;
            try {
                existentes = clientService.listAllOrThrow();
            } catch (PersistenceException e) {
                LOG.warn("No se pudo leer el padrón de clientes | source=ImportacionMasivaService.importar() | despues=" + e.getMessage());
                throw new EsquemaInvalidoException(
                        "No se pudo leer el padrón de clientes. Intente de nuevo en unos minutos.");
            }
            for (Clientes cliente : existentes) {
                if (cliente.getIdNumber() != null && !cliente.getIdNumber().isBlank()) {
                    clientesPorCedula.putIfAbsent(cliente.getIdNumber(), cliente);
                }
                if (cliente.getName() != null && !cliente.getName().isBlank()) {
                    clientesPorNombre.putIfAbsent(
                            ImportacionMasivaEntidad.normalizar(cliente.getName()), cliente);
                }
            }
        }

        Set<String> clavesVistas = new HashSet<>();
        for (int i = 1; i < celdas.size(); i++) {
            List<String> fila = celdas.get(i);
            int numeroFila = i + 1; // 1-based; row 1 is the header.
            if (filaVacia(fila)) {
                resultado.getFilas().add(ImportacionMasivaFilaResultado.omitida(
                        numeroFila, null, "Fila vacía: se omite."));
                continue;
            }
            resultado.getFilas().add(procesarFila(entidad, fila, mapa, numeroFila,
                    clavesVistas, simulacion, clientesPorCedula, clientesPorNombre));
        }

        resultado.recalcular();
        return resultado;
    }

    @Nonnull
    private ImportacionMasivaFilaResultado procesarFila(@Nonnull ImportacionMasivaEntidad entidad,
                                                        @Nonnull List<String> fila,
                                                        @Nonnull Map<String, Integer> mapa,
                                                        int numeroFila,
                                                        @Nonnull Set<String> clavesVistas,
                                                        boolean simulacion,
                                                        @Nonnull Map<String, Clientes> clientesPorCedula,
                                                        @Nonnull Map<String, Clientes> clientesPorNombre) {
        return switch (entidad) {
            case CLIENTES -> procesarCliente(fila, mapa, numeroFila, clavesVistas, simulacion,
                    clientesPorCedula, clientesPorNombre);
            case ARTICULOS -> procesarArticulo(fila, mapa, numeroFila, clavesVistas, simulacion);
            case CABYS -> procesarCabys(fila, mapa, numeroFila, clavesVistas, simulacion);
            case PRECIOS -> procesarPrecio(fila, mapa, numeroFila, clavesVistas, simulacion);
        };
    }

    // ── Clientes ──────────────────────────────────────────────────────────────

    @Nonnull
    private ImportacionMasivaFilaResultado procesarCliente(@Nonnull List<String> fila,
                                                           @Nonnull Map<String, Integer> mapa,
                                                           int numeroFila,
                                                           @Nonnull Set<String> clavesVistas,
                                                           boolean simulacion,
                                                           @Nonnull Map<String, Clientes> clientesPorCedula,
                                                           @Nonnull Map<String, Clientes> clientesPorNombre) {
        String nombre = texto(fila, mapa, "Nombre");
        String cedula = texto(fila, mapa, "Cedula");
        if (nombre.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, null,
                    "El nombre del cliente no puede estar vacío.");
        }

        Clientes existente = null;
        String clave;
        String codigoTexto = texto(fila, mapa, "Codigo");
        if (!codigoTexto.isEmpty()) {
            int codigo;
            try {
                codigo = parsearEntero(codigoTexto);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigoTexto,
                        "'Codigo' no es un número entero: '" + codigoTexto + "'.");
            }
            clave = codigoTexto;
            if (!clavesVistas.add("codigo:" + codigo)) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "Ya existe una fila anterior en el archivo con el mismo código: " + clave + ".");
            }
            existente = clientService.find(codigo);
            if (existente == null) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "No se encontró el cliente con el código " + codigo + ".");
            }
        } else {
            clave = cedula.isEmpty() ? nombre : cedula;
            if (!clavesVistas.add(normalizarClave(clave))) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "Ya existe una fila anterior en el archivo con la misma clave: " + clave + ".");
            }
            existente = cedula.isEmpty()
                    ? clientesPorNombre.get(ImportacionMasivaEntidad.normalizar(nombre))
                    : clientesPorCedula.get(cedula);
        }

        String tipoIdentificacion = texto(fila, mapa, "Tipo Identificacion");
        if (!tipoIdentificacion.isEmpty() && !tipoIdentificacionValido(tipoIdentificacion)) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "Tipo de identificación no reconocido: '" + tipoIdentificacion
                            + "'. Use: Cédula Física, Cédula Jurídica, DIMEX, NITE, "
                            + "Extranjero No Domiciliado o No Contribuyente.");
        }

        String provincia = texto(fila, mapa, "Provincia");
        String canton = texto(fila, mapa, "Canton");
        String distrito = texto(fila, mapa, "Distrito");
        String errorLongitud = validarLongitud(provincia, 1, "provincia");
        if (errorLongitud == null) {
            errorLongitud = validarLongitud(canton, 2, "cantón");
        }
        if (errorLongitud == null) {
            errorLongitud = validarLongitud(distrito, 2, "distrito");
        }
        if (errorLongitud != null) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave, errorLongitud);
        }

        Date fechaNacimiento = null;
        String fechaTexto = texto(fila, mapa, "Fecha Nacimiento");
        if (!fechaTexto.isEmpty()) {
            try {
                fechaNacimiento = parsearFecha(fechaTexto);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "La fecha de nacimiento no es válida: '" + fechaTexto
                                + "'. Use yyyy-MM-dd, dd/MM/yyyy o dd-MM-yyyy.");
            }
        }

        Boolean contribuyente = null;
        String contribuyenteTexto = texto(fila, mapa, "Contribuyente");
        if (!contribuyenteTexto.isEmpty()) {
            try {
                contribuyente = parsearBooleano(contribuyenteTexto);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El valor de 'Contribuyente' no es válido: '" + contribuyenteTexto
                                + "'. Use Sí/No, true/false o 1/0.");
            }
        }

        Integer zona = null;
        String zonaTexto = texto(fila, mapa, "Codigo Zona");
        if (!zonaTexto.isEmpty()) {
            try {
                zona = parsearEntero(zonaTexto);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El código de zona no es un número entero: '" + zonaTexto + "'.");
            }
            if (zona < 0) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El código de zona no puede ser negativo.");
            }
        }

        List<String> actividades = new ArrayList<>();
        for (String codigo : texto(fila, mapa, "Actividad Economica").split("[;,]")) {
            String limpio = codigo.trim();
            if (limpio.isEmpty()) {
                continue;
            }
            if (!RE_ACTIVIDAD.matcher(limpio).matches()) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El código de actividad económica debe tener 6 dígitos: '" + limpio + "'.");
            }
            actividades.add(limpio);
        }

        if (existente == null) {
            if (simulacion) {
                return ImportacionMasivaFilaResultado.nuevo(numeroFila, clave,
                        "Se creará el cliente '" + nombre + "'.");
            }
            return crearCliente(fila, mapa, numeroFila, nombre, cedula, tipoIdentificacion,
                    provincia, canton, distrito, fechaNacimiento, contribuyente, zona,
                    actividades, clave);
        }
        if (simulacion) {
            return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                    "Se actualizará el cliente existente '" + existente.getName() + "'.");
        }
        return actualizarCliente(existente, fila, mapa, numeroFila, tipoIdentificacion,
                provincia, canton, distrito, fechaNacimiento, contribuyente, zona, actividades, clave);
    }

    @Nonnull
    private ImportacionMasivaFilaResultado crearCliente(@Nonnull List<String> fila,
                                                         @Nonnull Map<String, Integer> mapa,
                                                         int numeroFila, @Nonnull String nombre,
                                                         @Nonnull String cedula,
                                                         @Nonnull String tipoIdentificacion,
                                                         @Nonnull String provincia,
                                                         @Nonnull String canton,
                                                         @Nonnull String distrito,
                                                         @Nullable Date fechaNacimiento,
                                                         @Nullable Boolean contribuyente,
                                                         @Nullable Integer zona,
                                                         @Nonnull List<String> actividades,
                                                         @Nonnull String clave) {
        Clientes cliente = new Clientes();
        cliente.setName(nombre);
        cliente.setIdNumber(cedula.isEmpty() ? null : cedula);
        cliente.setIdType(tipoIdentificacion.isEmpty() ? null : tipoIdentificacion);
        cliente.setTipoIdentificacion(cliente.getHaciendaIdTypeCode());
        cliente.setAddress(textoOpcional(fila, mapa, "Direccion"));
        cliente.setPhoneNumber(textoOpcional(fila, mapa, "Telefono"));
        cliente.setEmail(textoOpcional(fila, mapa, "Email"));
        cliente.setProvincia(provincia.isEmpty() ? null : provincia);
        cliente.setCanton(canton.isEmpty() ? null : canton);
        cliente.setDistrito(distrito.isEmpty() ? null : distrito);
        cliente.setBirthDate(fechaNacimiento);
        cliente.setTaxpayer(contribuyente != null && contribuyente);
        cliente.setZoneCode(zona != null ? zona : 0);
        cliente.setStatus(true); // ClientsResource.create() parity: new clients are enabled
        cliente.setUsuario(usuarioActual());
        List<ClienteActividad> nuevas = new ArrayList<>();
        for (String codigo : actividades) {
            nuevas.add(new ClienteActividad(codigo, null, cliente));
        }
        cliente.setActividades(nuevas);

        try {
            clientService.create(cliente);
        } catch (RuntimeException e) {
            LOG.warn("Error creando el cliente " + clave + " | source=ImportacionMasivaService.crearCliente() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo guardar el cliente: " + e.getMessage());
        }
        // ClientService.create() swallows PersistenceException: confirm the write.
        if (cliente.getCode() == 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo guardar el cliente: la base de datos no devolvió confirmación.");
        }
        return ImportacionMasivaFilaResultado.nuevo(numeroFila, clave,
                "Se creó el cliente '" + nombre + "'.");
    }

    @Nonnull
    private ImportacionMasivaFilaResultado actualizarCliente(@Nonnull Clientes cliente,
                                                              @Nonnull List<String> fila,
                                                              @Nonnull Map<String, Integer> mapa,
                                                              int numeroFila,
                                                              @Nonnull String tipoIdentificacion,
                                                              @Nonnull String provincia,
                                                              @Nonnull String canton,
                                                              @Nonnull String distrito,
                                                              @Nullable Date fechaNacimiento,
                                                              @Nullable Boolean contribuyente,
                                                              @Nullable Integer zona,
                                                              @Nonnull List<String> actividades,
                                                              @Nonnull String clave) {
        // Sparse-merge parity with ClientsResource.update(): blank cells never
        // wipe stored data.
        String direccion = textoOpcional(fila, mapa, "Direccion");
        if (direccion != null) {
            cliente.setAddress(direccion);
        }
        String telefono = textoOpcional(fila, mapa, "Telefono");
        if (telefono != null) {
            cliente.setPhoneNumber(telefono);
        }
        String email = textoOpcional(fila, mapa, "Email");
        if (email != null) {
            cliente.setEmail(email);
        }
        if (!tipoIdentificacion.isEmpty()) {
            cliente.setIdType(tipoIdentificacion);
            cliente.setTipoIdentificacion(cliente.getHaciendaIdTypeCode());
        }
        if (!provincia.isEmpty()) {
            cliente.setProvincia(provincia);
        }
        if (!canton.isEmpty()) {
            cliente.setCanton(canton);
        }
        if (!distrito.isEmpty()) {
            cliente.setDistrito(distrito);
        }
        if (fechaNacimiento != null) {
            cliente.setBirthDate(fechaNacimiento);
        }
        if (contribuyente != null) {
            cliente.setTaxpayer(contribuyente);
        }
        if (zona != null) {
            cliente.setZoneCode(zona);
        }
        cliente.setUsuario(usuarioActual());
        if (!actividades.isEmpty()) {
            List<ClienteActividad> nuevas = new ArrayList<>();
            for (String codigo : actividades) {
                nuevas.add(new ClienteActividad(codigo, null, cliente));
            }
            cliente.setActividades(nuevas);
        }

        try {
            clientService.update(cliente);
        } catch (RuntimeException e) {
            LOG.warn("Error actualizando el cliente " + clave + " | source=ImportacionMasivaService.actualizarCliente() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo actualizar el cliente: " + e.getMessage());
        }
        return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                "Se actualizó el cliente '" + cliente.getName() + "'.");
    }

    /** Reuses {@code Clientes.getHaciendaIdTypeCode()} as the accept-list oracle. */
    private static boolean tipoIdentificacionValido(@Nonnull String tipo) {
        Clientes sonda = new Clientes();
        sonda.setIdType(tipo);
        return sonda.getHaciendaIdTypeCode() != null;
    }

    // ── Artículos ─────────────────────────────────────────────────────────────

    @Nonnull
    private ImportacionMasivaFilaResultado procesarArticulo(@Nonnull List<String> fila,
                                                            @Nonnull Map<String, Integer> mapa,
                                                            int numeroFila,
                                                            @Nonnull Set<String> clavesVistas,
                                                            boolean simulacion) {
        String nombre = texto(fila, mapa, "Nombre");
        String codigoBarra = texto(fila, mapa, "Codigo de Barras");
        if (nombre.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, null,
                    "El nombre del artículo no puede estar vacío.");
        }
        String clave = codigoBarra.isEmpty() ? nombre : codigoBarra;
        if (!clavesVistas.add(normalizarClave(clave))) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "Ya existe una fila anterior en el archivo con la misma clave: " + clave + ".");
        }

        String nombreDepartamento = texto(fila, mapa, "Departamento");
        String nombreFamilia = texto(fila, mapa, "Familia");
        if (nombreDepartamento.isEmpty() || nombreFamilia.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se encontró selección para Departamentos o Familias: ambos campos son obligatorios.");
        }
        Departamento departamento = departamentoService.findByName(nombreDepartamento);
        if (departamento == null) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No existe un departamento activo con el nombre: " + nombreDepartamento + ".");
        }
        Familia familia = familiaService.findByNombre(nombreFamilia);
        if (familia == null) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No existe una familia con el nombre: " + nombreFamilia + ".");
        }

        Cabys cabys = null;
        String codigoCabys = texto(fila, mapa, "Codigo Cabys");
        if (!codigoCabys.isEmpty()) {
            if (!RE_CABYS.matcher(codigoCabys).matches()) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El código CABYS debe tener 13 dígitos: '" + codigoCabys + "'.");
            }
            cabys = cabysService.find(codigoCabys);
            if (cabys == null) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "No se encontró el código CABYS: " + codigoCabys + ".");
            }
        }

        TipoRefrigeracion refrigeracion = null;
        String textoRefrigeracion = texto(fila, mapa, "Tipo Refrigeracion");
        if (!textoRefrigeracion.isEmpty()) {
            try {
                refrigeracion = TipoRefrigeracion.valueOf(textoRefrigeracion.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "Tipo de refrigeración no válido: '" + textoRefrigeracion
                                + "'. Use NINGUNA, REFRIGERADO o CONGELADO.");
            }
        }

        Boolean exento = null;
        String textoExento = texto(fila, mapa, "Exento");
        if (!textoExento.isEmpty()) {
            try {
                exento = parsearBooleano(textoExento);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El valor de 'Exento' no es válido: '" + textoExento
                                + "'. Use Sí/No, true/false o 1/0.");
            }
        }

        Integer stockOptimo = null;
        String textoStock = texto(fila, mapa, "Stock Optimo");
        if (!textoStock.isEmpty()) {
            try {
                stockOptimo = parsearEntero(textoStock);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "'Stock Optimo' no es un número entero: '" + textoStock + "'.");
            }
            if (stockOptimo < 0) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "'Stock Optimo' no puede ser negativo.");
            }
        }

        Integer diasSeguridad = null;
        String textoDias = texto(fila, mapa, "Dias Stock Seguridad");
        if (!textoDias.isEmpty()) {
            try {
                diasSeguridad = parsearEntero(textoDias);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "'Dias Stock Seguridad' no es un número entero: '" + textoDias + "'.");
            }
            if (diasSeguridad < 0) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "'Dias Stock Seguridad' no puede ser negativo.");
            }
        }

        Articulos existente = codigoBarra.isEmpty()
                ? articulosService.findByName(nombre)
                : articulosService.findByBarCode(codigoBarra);

        if (existente == null) {
            if (simulacion) {
                return ImportacionMasivaFilaResultado.nuevo(numeroFila, clave,
                        "Se creará el artículo '" + nombre + "'.");
            }
            return crearArticulo(fila, mapa, numeroFila, nombre, codigoBarra, departamento,
                    familia, cabys, refrigeracion, exento, stockOptimo, diasSeguridad, clave);
        }
        if (simulacion) {
            return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                    "Se actualizará el artículo existente '" + existente.getNombre() + "'.");
        }
        return actualizarArticulo(existente, fila, mapa, numeroFila, nombre, codigoBarra,
                departamento, familia, cabys, refrigeracion, exento, stockOptimo, diasSeguridad, clave);
    }

    @Nonnull
    private ImportacionMasivaFilaResultado crearArticulo(@Nonnull List<String> fila,
                                                          @Nonnull Map<String, Integer> mapa,
                                                          int numeroFila, @Nonnull String nombre,
                                                          @Nonnull String codigoBarra,
                                                          @Nonnull Departamento departamento,
                                                          @Nonnull Familia familia,
                                                          @Nullable Cabys cabys,
                                                          @Nullable TipoRefrigeracion refrigeracion,
                                                          @Nullable Boolean exento,
                                                          @Nullable Integer stockOptimo,
                                                          @Nullable Integer diasSeguridad,
                                                          @Nonnull String clave) {
        Articulos nuevo = new Articulos();
        nuevo.setNombre(nombre);
        nuevo.setCodigoBarra(codigoBarra.isEmpty() ? null : codigoBarra);
        nuevo.setDescripcion(textoOpcional(fila, mapa, "Descripcion"));
        nuevo.setUnidadMedida(textoOpcional(fila, mapa, "Unidad de Medida"));
        nuevo.setUnidadMedidaComercial(textoOpcional(fila, mapa, "Unidad de Medida Comercial"));
        nuevo.setDepartamento(departamento);
        nuevo.setFamilia(familia);
        nuevo.setCodigoCabys(cabys);
        nuevo.setTipoRefrigeracion(refrigeracion);
        nuevo.setExento(exento != null && exento);
        nuevo.setStockOptimo(stockOptimo);
        nuevo.setDiasStockSeguridad(diasSeguridad);
        nuevo.setEstadoAlertas(Boolean.TRUE);
        nuevo.setProcessed(true); // ArticuloResource.doCreate() parity
        nuevo.setStatus(true);    // ArticuloResource.doCreate() parity
        nuevo.setUsuario(usuarioActual());

        // ArticuloResource.doCreate() seeds one precio row so getLastPrecio()
        // never NPEs for a freshly created article. The price amounts
        // themselves are imported through the PRECIOS target.
        ArticuloPrecio precioInicial = new ArticuloPrecio();
        precioInicial.setArticulo(nuevo);
        List<ArticuloPrecio> precios = new ArrayList<>();
        precios.add(precioInicial);
        nuevo.setPrecios(precios);

        try {
            articulosService.create(nuevo);
        } catch (RuntimeException e) {
            LOG.warn("Error creando el artículo " + clave + " | source=ImportacionMasivaService.crearArticulo() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo guardar el artículo: " + e.getMessage());
        }
        if (nuevo.getCodigo() == null) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo guardar el artículo: la base de datos no devolvió confirmación.");
        }
        return ImportacionMasivaFilaResultado.nuevo(numeroFila, clave,
                        "Se creó el artículo '" + nombre + "'.")
                .conAviso(cabys == null
                        ? "Sin código CABYS: importe los precios para que el precio final incluya el IVA."
                        : null);
    }

    @Nonnull
    private ImportacionMasivaFilaResultado actualizarArticulo(@Nonnull Articulos articulo,
                                                               @Nonnull List<String> fila,
                                                               @Nonnull Map<String, Integer> mapa,
                                                               int numeroFila, @Nonnull String nombre,
                                                               @Nonnull String codigoBarra,
                                                               @Nonnull Departamento departamento,
                                                               @Nonnull Familia familia,
                                                               @Nullable Cabys cabys,
                                                               @Nullable TipoRefrigeracion refrigeracion,
                                                               @Nullable Boolean exento,
                                                               @Nullable Integer stockOptimo,
                                                               @Nullable Integer diasSeguridad,
                                                               @Nonnull String clave) {
        String descripcion = textoOpcional(fila, mapa, "Descripcion");
        if (descripcion != null) {
            articulo.setDescripcion(descripcion);
        }
        String unidadMedida = textoOpcional(fila, mapa, "Unidad de Medida");
        if (unidadMedida != null) {
            articulo.setUnidadMedida(unidadMedida);
        }
        String unidadComercial = textoOpcional(fila, mapa, "Unidad de Medida Comercial");
        if (unidadComercial != null) {
            articulo.setUnidadMedidaComercial(unidadComercial);
        }
        articulo.setNombre(nombre);
        if (!codigoBarra.isEmpty()) {
            articulo.setCodigoBarra(codigoBarra);
        }
        articulo.setDepartamento(departamento);
        articulo.setFamilia(familia);
        if (cabys != null) {
            articulo.setCodigoCabys(cabys);
        }
        if (refrigeracion != null) {
            articulo.setTipoRefrigeracion(refrigeracion);
        }
        if (exento != null) {
            articulo.setExento(exento);
        }
        if (stockOptimo != null) {
            articulo.setStockOptimo(stockOptimo);
        }
        if (diasSeguridad != null) {
            articulo.setDiasStockSeguridad(diasSeguridad);
        }
        articulo.setUsuario(usuarioActual());
        articulo.setProcessed(true); // ArticuloResource.update() parity: edit forces processed=true

        try {
            articulosService.update(articulo);
        } catch (RuntimeException e) {
            LOG.warn("Error actualizando el artículo " + clave + " | source=ImportacionMasivaService.actualizarArticulo() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo actualizar el artículo: " + e.getMessage());
        }
        return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                "Se actualizó el artículo '" + articulo.getNombre() + "'.");
    }

    // ── CABYS ─────────────────────────────────────────────────────────────────

    @Nonnull
    private ImportacionMasivaFilaResultado procesarCabys(@Nonnull List<String> fila,
                                                        @Nonnull Map<String, Integer> mapa,
                                                        int numeroFila,
                                                        @Nonnull Set<String> clavesVistas,
                                                        boolean simulacion) {
        String codigo = texto(fila, mapa, "Codigo");
        if (codigo.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, null,
                    "El código CABYS no puede estar vacío.");
        }
        if (!RE_CABYS.matcher(codigo).matches()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "El código CABYS debe tener 13 dígitos: '" + codigo + "'.");
        }
        if (!clavesVistas.add(codigo)) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "Ya existe una fila anterior en el archivo con el mismo código CABYS: " + codigo + ".");
        }

        String impuestoTexto = texto(fila, mapa, "Impuesto");
        if (impuestoTexto.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "El impuesto del CABYS es obligatorio (0 para exonerado).");
        }
        BigDecimal impuesto;
        try {
            impuesto = parsearMonto(impuestoTexto);
        } catch (NumberFormatException e) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "El impuesto del CABYS no es un número válido: '" + impuestoTexto + "'.");
        }
        if (impuesto.compareTo(BigDecimal.ZERO) < 0 || impuesto.compareTo(CIEN) > 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "El impuesto del CABYS debe estar entre 0 y 100: '" + impuestoTexto + "'.");
        }
        BigDecimal impuestoEntero = impuesto.stripTrailingZeros();
        if (impuestoEntero.scale() > 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "El impuesto del CABYS debe ser un número entero: '" + impuestoTexto + "'.");
        }
        String impuestoNormalizado = impuestoEntero.toPlainString();

        String estado = texto(fila, mapa, "Estado");
        if (estado.isEmpty()) {
            estado = ESTADO_ACTIVO;
        } else if (!ESTADO_ACTIVO.equalsIgnoreCase(estado) && !ESTADO_INACTIVO.equalsIgnoreCase(estado)) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "El estado del CABYS debe ser ACTIVO o INACTIVO: '" + estado + "'.");
        }
        String estadoNormalizado = ESTADO_ACTIVO.equalsIgnoreCase(estado) ? ESTADO_ACTIVO : ESTADO_INACTIVO;

        Cabys existente = cabysService.find(codigo);
        if (existente == null) {
            if (simulacion) {
                return ImportacionMasivaFilaResultado.nuevo(numeroFila, codigo,
                        "Se creará el código CABYS " + codigo + ".");
            }
            return crearCabys(fila, mapa, numeroFila, codigo, impuestoNormalizado, estadoNormalizado);
        }
        if (simulacion) {
            return ImportacionMasivaFilaResultado.actualizado(numeroFila, codigo,
                    "Se actualizará el código CABYS existente " + codigo + ".");
        }
        return actualizarCabys(existente, fila, mapa, numeroFila, codigo, impuestoNormalizado, estadoNormalizado);
    }

    @Nonnull
    private ImportacionMasivaFilaResultado crearCabys(@Nonnull List<String> fila,
                                                       @Nonnull Map<String, Integer> mapa,
                                                       int numeroFila, @Nonnull String codigo,
                                                       @Nonnull String impuesto,
                                                       @Nonnull String estado) {
        String descripcion = textoOpcional(fila, mapa, "Descripcion");
        if (descripcion == null) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "La descripción del CABYS no puede estar vacía.");
        }
        Cabys cabys = new Cabys(codigo, descripcion, textoOpcional(fila, mapa, "Categorias"),
                impuesto, textoOpcional(fila, mapa, "URI"), estado);
        try {
            cabysService.create(cabys);
        } catch (RuntimeException e) {
            LOG.warn("Error creando el CABYS " + codigo + " | source=ImportacionMasivaService.crearCabys() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "No se pudo guardar el CABYS: " + e.getMessage());
        }
        // CabysService.create() swallows PersistenceException: confirm the row landed.
        if (cabysService.find(codigo) == null) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "No se pudo guardar el CABYS: la base de datos no devolvió confirmación.");
        }
        return ImportacionMasivaFilaResultado.nuevo(numeroFila, codigo,
                "Se creó el código CABYS " + codigo + ".");
    }

    @Nonnull
    private ImportacionMasivaFilaResultado actualizarCabys(@Nonnull Cabys cabys,
                                                            @Nonnull List<String> fila,
                                                            @Nonnull Map<String, Integer> mapa,
                                                            int numeroFila, @Nonnull String codigo,
                                                            @Nonnull String impuesto,
                                                            @Nonnull String estado) {
        String descripcion = textoOpcional(fila, mapa, "Descripcion");
        if (descripcion != null) {
            cabys.setDescripcion(descripcion);
        }
        String categorias = textoOpcional(fila, mapa, "Categorias");
        if (categorias != null) {
            cabys.setCategorias(categorias);
        }
        String uri = textoOpcional(fila, mapa, "URI");
        if (uri != null) {
            cabys.setUri(uri);
        }
        cabys.setImpuesto(impuesto);
        cabys.setEstado(estado);
        try {
            cabysService.update(cabys);
        } catch (RuntimeException e) {
            LOG.warn("Error actualizando el CABYS " + codigo + " | source=ImportacionMasivaService.actualizarCabys() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigo,
                    "No se pudo actualizar el CABYS: " + e.getMessage());
        }
        return ImportacionMasivaFilaResultado.actualizado(numeroFila, codigo,
                "Se actualizó el código CABYS " + codigo + ".");
    }

    // ── Precios ───────────────────────────────────────────────────────────────

    @Nonnull
    private ImportacionMasivaFilaResultado procesarPrecio(@Nonnull List<String> fila,
                                                          @Nonnull Map<String, Integer> mapa,
                                                          int numeroFila,
                                                          @Nonnull Set<String> clavesVistas,
                                                          boolean simulacion) {
        String codigoArticulo = texto(fila, mapa, "Codigo Articulo");
        String codigoBarra = texto(fila, mapa, "Codigo de Barras");
        if (codigoArticulo.isEmpty() && codigoBarra.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, null,
                    "Debe indicar 'Codigo Articulo' o 'Codigo de Barras' para identificar el artículo.");
        }

        Articulos articulo;
        if (!codigoArticulo.isEmpty()) {
            int id;
            try {
                id = parsearEntero(codigoArticulo);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigoArticulo,
                        "'Codigo Articulo' no es un número entero: '" + codigoArticulo + "'.");
            }
            articulo = articulosService.findById(id);
            if (articulo == null) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigoArticulo,
                        "No se encontró el artículo con el código: " + codigoArticulo + ".");
            }
        } else {
            articulo = articulosService.findByBarCode(codigoBarra);
            if (articulo == null) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, codigoBarra,
                        "No se encontró el artículo con el código de barras: " + codigoBarra + ".");
            }
        }
        String clave = !codigoArticulo.isEmpty() ? codigoArticulo : codigoBarra;

        Date fechaCompra = null;
        String fechaTexto = texto(fila, mapa, "Fecha Compra");
        if (!fechaTexto.isEmpty()) {
            try {
                fechaCompra = parsearFecha(fechaTexto);
            } catch (IllegalArgumentException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "La fecha de compra no es válida: '" + fechaTexto
                                + "'. Use yyyy-MM-dd, dd/MM/yyyy o dd-MM-yyyy.");
            }
        }

        String costoTexto = texto(fila, mapa, "Precio Costo sin IVA");
        if (costoTexto.isEmpty()) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "El precio de costo sin IVA es obligatorio.");
        }
        BigDecimal costo;
        try {
            costo = parsearMonto(costoTexto);
        } catch (NumberFormatException e) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "El precio de costo sin IVA no es un número válido: '" + costoTexto
                            + "'. Ejemplos válidos: ₡1.234,56 · 1234.56 · 1,234.56.");
        }
        if (costo.compareTo(BigDecimal.ZERO) <= 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "El precio de costo sin IVA debe ser mayor que cero.");
        }

        BigDecimal margen;
        String margenTexto = texto(fila, mapa, "Porcentaje Utilidad");
        if (margenTexto.isEmpty()) {
            margen = margenCalculadora.calcularMargenPorcentaje(articulo);
        } else {
            try {
                margen = parsearMonto(margenTexto);
            } catch (NumberFormatException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El porcentaje de utilidad no es un número válido: '" + margenTexto + "'.");
            }
            if (margen.compareTo(BigDecimal.ZERO) < 0) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El porcentaje de utilidad no puede ser negativo.");
            }
        }

        // Existing business rules: MargenCalculadora (ArticuloResource parity).
        BigDecimal precioConUtilidad = margenCalculadora.calcularPrecioConUtilidad(costo, margen);
        if (precioConUtilidad.compareTo(BigDecimal.ZERO) <= 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "El precio con utilidad calculado es cero o negativo; revise el costo y el "
                            + "porcentaje de utilidad.");
        }

        String finalTexto = texto(fila, mapa, "Precio Final");
        boolean finalCalculado = finalTexto.isEmpty();
        BigDecimal precioFinal;
        if (finalCalculado) {
            precioFinal = margenCalculadora.calcularPrecioFinal(precioConUtilidad, impuestoDe(articulo));
        } else {
            try {
                precioFinal = parsearMonto(finalTexto);
            } catch (NumberFormatException e) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El precio final no es un número válido: '" + finalTexto + "'.");
            }
            if (precioFinal.compareTo(precioConUtilidad) < 0) {
                return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                        "El precio final (" + precioFinal.toPlainString()
                                + ") no puede ser menor que el precio con utilidad ("
                                + precioConUtilidad.toPlainString() + ").");
            }
        }
        if (precioFinal.compareTo(BigDecimal.ZERO) <= 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "El precio final debe ser mayor que cero.");
        }

        // A price row for the same article and purchase date is a "touched" row.
        ArticuloPrecio existente = buscarPrecio(articulo, fechaCompra);
        if (!clavesVistas.add(clave + "@" + (fechaCompra == null ? "hoy" : fechaCompra.getTime()))) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "Ya existe una fila anterior en el archivo para el mismo artículo y la misma fecha de compra.");
        }

        String detalle = "Costo " + costo.toPlainString()
                + " · precio con utilidad " + precioConUtilidad.toPlainString()
                + " · precio final " + precioFinal.toPlainString();
        String avisoFinal = finalCalculado
                ? "El precio final se calculó con el impuesto del CABYS del artículo."
                : null;

        if (existente == null) {
            if (simulacion) {
                return ImportacionMasivaFilaResultado
                        .nuevo(numeroFila, clave, "Se creará una fila de precio para '" + articulo.getNombre() + "'.")
                        .conAviso(fechaCompra == null
                                ? "Sin fecha de compra: el sistema asignará la fecha actual."
                                : null)
                        .conAviso(avisoFinal);
            }
            return crearPrecio(articulo, costo, margen, precioConUtilidad, precioFinal,
                    fechaCompra, numeroFila, clave, detalle, avisoFinal);
        }
        if (simulacion) {
            return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                    "Se recalculará la fila de precio existente de '" + articulo.getNombre() + "'.")
                    .conAviso(avisoFinal);
        }
        return actualizarPrecio(existente, costo, margen, precioConUtilidad, precioFinal,
                numeroFila, clave, detalle, avisoFinal);
    }

    @Nullable
    private BigDecimal impuestoDe(@Nonnull Articulos articulo) {
        Cabys cabys = articulo.getCodigoCabys();
        if (cabys == null || cabys.getImpuesto() == null || cabys.getImpuesto().isBlank()) {
            return null;
        }
        try {
            return parsearMonto(cabys.getImpuesto());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private ArticuloPrecio buscarPrecio(@Nonnull Articulos articulo, @Nullable Date fechaCompra) {
        if (fechaCompra == null) {
            return null;
        }
        List<ArticuloPrecio> precios = precioService.findAllByArticulo(articulo);
        if (precios == null) {
            return null;
        }
        LocalDate objetivo = aFechaLocal(fechaCompra);
        for (ArticuloPrecio precio : precios) {
            if (precio.getFechaCompra() != null
                    && objetivo.equals(aFechaLocal(precio.getFechaCompra()))) {
                return precio;
            }
        }
        return null;
    }

    @Nonnull
    private ImportacionMasivaFilaResultado crearPrecio(@Nonnull Articulos articulo,
                                                        @Nonnull BigDecimal costo,
                                                        @Nonnull BigDecimal margen,
                                                        @Nonnull BigDecimal precioConUtilidad,
                                                        @Nonnull BigDecimal precioFinal,
                                                        @Nullable Date fechaCompra,
                                                        int numeroFila, @Nonnull String clave,
                                                        @Nonnull String detalle,
                                                        @Nullable String avisoFinal) {
        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(costo);
        precio.setPorcentajeUtilidad(margen);
        precio.setPrecioConUtilidad(precioConUtilidad);
        precio.setPrecioFinal(precioFinal);
        precio.setUsuario(usuarioActual());

        try {
            precioService.create(precio);
        } catch (RuntimeException e) {
            LOG.warn("Error creando el precio del artículo " + clave + " | source=ImportacionMasivaService.crearPrecio() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo guardar el precio: " + e.getMessage());
        }
        if (precio.getId() == 0) {
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo guardar el precio: la base de datos no devolvió confirmación.");
        }
        // ArticuloPrecio.onCreate() stamps fechaCompra with "now", discarding the
        // sheet value. merge() does not re-run @PrePersist, so the file date is
        // restored in a second step when the operator supplied one.
        if (fechaCompra != null && !aFechaLocal(precio.getFechaCompra()).equals(aFechaLocal(fechaCompra))) {
            precio.setFechaCompra(fechaCompra);
            try {
                precioService.update(precio);
            } catch (RuntimeException e) {
                LOG.warn("No se pudo aplicar la fecha de compra del artículo " + clave
                        + " | source=ImportacionMasivaService.crearPrecio() | despues=" + e.getMessage());
                return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                                "Se guardó el precio. " + detalle)
                        .conAviso("La fecha de compra del archivo (" + aFechaLocal(fechaCompra)
                                + ") no se pudo aplicar: el sistema registró la fecha actual.")
                        .conAviso(avisoFinal);
            }
        }
        return ImportacionMasivaFilaResultado.nuevo(numeroFila, clave, "Se guardó el precio. " + detalle)
                .conAviso(avisoFinal);
    }

    @Nonnull
    private ImportacionMasivaFilaResultado actualizarPrecio(@Nonnull ArticuloPrecio precio,
                                                             @Nonnull BigDecimal costo,
                                                             @Nonnull BigDecimal margen,
                                                             @Nonnull BigDecimal precioConUtilidad,
                                                             @Nonnull BigDecimal precioFinal,
                                                             int numeroFila, @Nonnull String clave,
                                                             @Nonnull String detalle,
                                                             @Nullable String avisoFinal) {
        precio.setPrecioCostoSinIVA(costo);
        precio.setPorcentajeUtilidad(margen);
        precio.setPrecioConUtilidad(precioConUtilidad);
        precio.setPrecioFinal(precioFinal);
        precio.setUsuario(usuarioActual());
        try {
            precioService.update(precio);
        } catch (RuntimeException e) {
            LOG.warn("Error actualizando el precio del artículo " + clave + " | source=ImportacionMasivaService.actualizarPrecio() | despues=" + e.getMessage());
            return ImportacionMasivaFilaResultado.rechazada(numeroFila, clave,
                    "No se pudo actualizar el precio: " + e.getMessage());
        }
        return ImportacionMasivaFilaResultado.actualizado(numeroFila, clave,
                        "Se actualizó el precio. " + detalle)
                .conAviso(avisoFinal);
    }

    // ── Lectura del archivo ───────────────────────────────────────────────────

    /**
     * Decodes an {@code .xlsx} (POI) or {@code .csv} (RFC 4180 subset) file into
     * a grid of text cells. Numeric cells are rendered in a locale-independent
     * plain form so money columns are never re-parsed through a display
     * formatter.
     *
     * @throws EsquemaInvalidoException unsupported extension or corrupt workbook
     */
    @Nonnull
    public static List<List<String>> leerCeldas(@Nullable String nombreArchivo, @Nonnull byte[] contenido)
            throws EsquemaInvalidoException {
        String nombre = nombreArchivo == null ? "" : nombreArchivo.toLowerCase(Locale.ROOT);
        if (nombre.endsWith(".xlsx") || nombre.endsWith(".xlsm")) {
            return leerXlsx(contenido);
        }
        if (nombre.endsWith(".csv") || nombre.endsWith(".txt")) {
            return leerCsv(contenido);
        }
        throw new EsquemaInvalidoException(
                "Formato de archivo no soportado: '" + nombreArchivo + "'. Se admiten .xlsx y .csv.");
    }

    @Nonnull
    private static List<List<String>> leerXlsx(@Nonnull byte[] contenido) throws EsquemaInvalidoException {
        List<List<String>> filas = new ArrayList<>();
        try (Workbook libro = new XSSFWorkbook(new ByteArrayInputStream(contenido))) {
            if (libro.getNumberOfSheets() == 0) {
                return filas;
            }
            Sheet hoja = libro.getSheetAt(0);
            for (Row fila : hoja) {
                List<String> celdas = new ArrayList<>();
                int ultimo = fila.getLastCellNum();
                for (int i = 0; i < ultimo; i++) {
                    celdas.add(textoCelda(fila.getCell(i)));
                }
                filas.add(celdas);
            }
        } catch (IOException | RuntimeException e) {
            throw new EsquemaInvalidoException("No se pudo leer el archivo Excel: " + e.getMessage());
        }
        return filas;
    }

    @Nonnull
    private static String textoCelda(@Nullable Cell celda) {
        if (celda == null) {
            return "";
        }
        return switch (celda.getCellType()) {
            case STRING -> celda.getStringCellValue().trim();
            case NUMERIC -> {
                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(celda)) {
                    yield celda.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                yield BigDecimal.valueOf(celda.getNumericCellValue()).stripTrailingZeros().toPlainString();
            }
            case BOOLEAN -> Boolean.toString(celda.getBooleanCellValue());
            case FORMULA -> celda.getCellFormula();
            default -> "";
        };
    }

    /**
     * Minimal RFC 4180 reader: quoted fields, doubled quotes, embedded
     * separators/newlines, UTF-8 BOM, and a delimiter sniffed from the header
     * ({@code ;} wins ties, which is the Costa Rican Excel default).
     */
    @Nonnull
    static List<List<String>> leerCsv(@Nonnull byte[] contenido) {
        String texto = new String(contenido, StandardCharsets.UTF_8);
        if (!texto.isEmpty() && texto.charAt(0) == '\uFEFF') {
            texto = texto.substring(1);
        }
        char delimitador = detectarDelimitador(texto);
        List<List<String>> filas = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        StringBuilder campo = new StringBuilder();
        boolean enComillas = false;

        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (enComillas) {
                if (c == '"') {
                    if (i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                        campo.append('"');
                        i++;
                    } else {
                        enComillas = false;
                    }
                } else {
                    campo.append(c);
                }
                continue;
            }
            if (c == '"') {
                enComillas = true;
            } else if (c == delimitador) {
                actual.add(campo.toString().trim());
                campo.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                actual.add(campo.toString().trim());
                campo.setLength(0);
                filas.add(actual);
                actual = new ArrayList<>();
            } else {
                campo.append(c);
            }
        }
        if (campo.length() > 0 || !actual.isEmpty()) {
            actual.add(campo.toString().trim());
            filas.add(actual);
        }
        return filas;
    }

    private static char detectarDelimitador(@Nonnull String texto) {
        int primera = texto.indexOf('\n');
        String linea = primera >= 0 ? texto.substring(0, primera) : texto;
        int puntoYComa = 0;
        int coma = 0;
        boolean enComillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (c == '"') {
                enComillas = !enComillas;
            } else if (!enComillas && c == ';') {
                puntoYComa++;
            } else if (!enComillas && c == ',') {
                coma++;
            }
        }
        return puntoYComa >= coma ? ';' : ',';
    }

    // ── Encabezados ───────────────────────────────────────────────────────────

    /**
     * Maps normalized header captions to column indexes and enforces the
     * required-column contract. Matching is accent- and punctuation-insensitive,
     * so {@code "Código de Barras"}, {@code "Codigo de Barras"} and
     * {@code "codigo_barras"} all bind to the same field.
     *
     * @throws EsquemaInvalidoException when a required column is absent
     */
    @Nonnull
    static Map<String, Integer> mapearEncabezados(@Nonnull List<String> cabecera,
                                                   @Nonnull List<ImportacionMasivaColumna> columnas)
            throws EsquemaInvalidoException {
        Map<String, Integer> mapa = new HashMap<>();
        for (int i = 0; i < cabecera.size(); i++) {
            String clave = normalizarEncabezado(cabecera.get(i));
            if (!clave.isEmpty()) {
                mapa.putIfAbsent(clave, i);
            }
        }
        List<String> faltantes = new ArrayList<>();
        for (ImportacionMasivaColumna columna : columnas) {
            if (columna.isRequerido() && !mapa.containsKey(normalizarEncabezado(columna.getEncabezado()))) {
                faltantes.add(columna.getEncabezado());
            }
        }
        if (!faltantes.isEmpty()) {
            throw new EsquemaInvalidoException("El archivo no cumple el esquema requerido. "
                    + "Faltan las columnas obligatorias: " + String.join(", ", faltantes) + ".");
        }
        return mapa;
    }

    /**
     * Lowercase, unaccented, alphanumeric-only form of a header caption, with the
     * standalone word {@code de} dropped.
     *
     * <p>The stopword is load-bearing, not cosmetic. Supplier files spell the
     * column {@code Codigo de Barras} while the canonical column is
     * {@code Codigo de Barras} itself — and integrators also write
     * {@code codigo_barras}. Without dropping {@code de}, the first folds to
     * {@code codigodebarras} and the second to {@code codigobarras}, so the same
     * column does not match itself across the two spellings and the file is
     * rejected for a missing required column.</p>
     *
     * <p>Collision-checked: no two canonical {@code ImportacionMasivaColumna}
     * headers fold to the same key with the stopword dropped
     * ({@code Codigo/Codigo Articulo/Codigo Cabys/Codigo de Barras/Codigo Zona}
     * stay distinct, as do {@code Unidad de Medida/Unidad de Medida Comercial}).
     * Only the bare word {@code de} is dropped — {@code del}, {@code la},
     * {@code el} and any {@code de} inside a longer token are kept, so this
     * cannot merge two genuinely different headers.</p>
     */
    @Nonnull
    static String normalizarEncabezado(@Nullable String texto) {
        if (texto == null) {
            return "";
        }
        String plegado = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        plegado = plegado.replaceAll("\\bde\\b", "");
        return plegado.replaceAll("[^a-z0-9]", "");
    }

    /** Same folding as {@link #normalizarEncabezado}, used for duplicate keys. */
    @Nonnull
    static String normalizarClave(@Nullable String texto) {
        return normalizarEncabezado(texto);
    }

    /** Trimmed cell value, or {@code ""} when the column is absent from the sheet. */
    @Nonnull
    private static String texto(@Nonnull List<String> fila, @Nonnull Map<String, Integer> mapa,
                                @Nonnull String columna) {
        Integer indice = mapa.get(normalizarEncabezado(columna));
        if (indice == null || indice < 0 || indice >= fila.size()) {
            return "";
        }
        String valor = fila.get(indice);
        return valor == null ? "" : valor.trim();
    }

    /** {@link #texto} with blanks mapped to {@code null} (sparse-merge semantics). */
    @Nullable
    private static String textoOpcional(@Nonnull List<String> fila, @Nonnull Map<String, Integer> mapa,
                                        @Nonnull String columna) {
        String valor = texto(fila, mapa, columna);
        return valor.isEmpty() ? null : valor;
    }

    private static boolean filaVacia(@Nonnull List<String> fila) {
        for (String celda : fila) {
            if (celda != null && !celda.isBlank()) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String validarLongitud(@Nonnull String valor, int longitud, @Nonnull String etiqueta) {
        if (valor.isEmpty()) {
            return null;
        }
        if (valor.length() != longitud) {
            return "El código de " + etiqueta + " de Hacienda debe tener " + longitud
                    + (longitud == 1 ? " dígito" : " dígitos") + ": '" + valor + "'.";
        }
        return null;
    }

    // ── Parsing numérico / fechas / booleanos ─────────────────────────────────

    /**
     * Locale-tolerant money/number parser for Costa Rican data. Accepts the colón
     * sign ({@code ₡}), thousands separators in either convention ({@code 1.234,56}
     * and {@code 1,234.56} alike), a bare {@code 1234.56}, and grouping-only forms
     * ({@code 1,234} → 1234; {@code 1.234.567} → 1234567).
     *
     * <p>Anything else — a stray letter or symbol, a repeated decimal separator,
     * or a single separator followed by 4+ digits (genuinely ambiguous) — raises
     * {@link NumberFormatException} instead of being silently coerced to zero.</p>
     */
    @Nonnull
    public static BigDecimal parsearMonto(@Nullable String texto) {
        if (texto == null) {
            throw new NumberFormatException("valor nulo");
        }
        String original = texto.trim();
        if (original.isEmpty()) {
            throw new NumberFormatException("valor vacío");
        }
        StringBuilder limpio = new StringBuilder();
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.isDigit(c) || c == '.' || c == ',' || c == '-' || c == '+') {
                limpio.append(c);
            } else if (c == ' ' || c == '\t' || c == '\u00A0' || c == '\u202F' || c == '\u2007'
                    || c == '₡' || c == '¢' || c == '$') {
                continue;
            } else {
                throw new NumberFormatException("carácter no permitido: '" + c + "'");
            }
        }
        String s = limpio.toString();
        boolean negativo = false;
        if (s.startsWith("-")) {
            negativo = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (!s.matches("[0-9.,]+")) {
            throw new NumberFormatException("no es un número: '" + original + "'");
        }

        int ultimoPunto = s.lastIndexOf('.');
        int ultimaComa = s.lastIndexOf(',');
        String normalizado;
        if (ultimoPunto >= 0 && ultimaComa >= 0) {
            int posDecimal = Math.max(ultimoPunto, ultimaComa);
            char decimal = s.charAt(posDecimal);
            if (s.indexOf(decimal) != posDecimal) {
                throw new NumberFormatException("más de un separador decimal: '" + original + "'");
            }
            String agrupador = decimal == '.' ? "," : ".";
            String cabeza = s.substring(0, posDecimal).replace(agrupador, "");
            if (cabeza.isEmpty()) {
                cabeza = "0";
            }
            normalizado = cabeza + "." + s.substring(posDecimal + 1);
        } else if (ultimoPunto >= 0 || ultimaComa >= 0) {
            char separador = ultimoPunto >= 0 ? '.' : ',';
            int pos = Math.max(ultimoPunto, ultimaComa);
            int ocurrencias = s.length() - s.replace(String.valueOf(separador), "").length();
            int decimales = s.length() - pos - 1;
            if (ocurrencias > 1) {
                // Varios separadores iguales solo pueden ser de agrupacion, y la
                // agrupacion exige grupos de 3 digitos: "1.234.567" es un millon,
                // pero "1.2.3.4" esta mal formado y antes se convertia en silencio
                // en 1234. En importes, convertir un texto mal formado en otra
                // cantidad es peor que rechazarlo: el error se importa como dato.
                if (!esAgrupacionValida(s, separador)) {
                    throw new NumberFormatException(
                            "agrupacion de miles invalida (los grupos deben ser de 3 digitos): '"
                                    + original + "'");
                }
                normalizado = s.replace(String.valueOf(separador), "");
            } else if (decimales == 1 || decimales == 2) {
                normalizado = (pos == 0 ? "0" : s.substring(0, pos)) + "." + s.substring(pos + 1);
            } else if (decimales == 3) {
                normalizado = s.replace(String.valueOf(separador), "");
            } else {
                throw new NumberFormatException("separador decimal ambiguo: '" + original + "'");
            }
        } else {
            normalizado = s;
        }

        BigDecimal valor;
        try {
            valor = new BigDecimal(normalizado);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("no es un número: '" + original + "'");
        }
        return negativo ? valor.negate() : valor;
    }

    /**
     * Whether repeated separators form valid thousand-grouping: a first group of
     * 1-3 digits followed by groups of exactly 3, e.g. {@code 1.234.567}.
     *
     * <p>{@code 1.2.3.4} fails (single-digit groups) and {@code 12.34.567} fails
     * ("34" is not a full group of 3). Anything that is not grouping-shaped is
     * rejected rather than silently reinterpreted as a different amount.</p>
     */
    private static boolean esAgrupacionValida(@Nonnull String s, char separador) {
        String[] grupos = s.split("\\" + separador, -1);
        if (grupos.length < 2) {
            return false;
        }
        if (!grupos[0].matches("[0-9]{1,3}")) {
            return false;
        }
        for (int g = 1; g < grupos.length; g++) {
            if (!grupos[g].matches("[0-9]{3}")) {
                return false;
            }
        }
        return true;
    }

    /** Parses an integer, rejecting decimals and any non-digit content. */
    public static int parsearEntero(@Nullable String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("valor nulo");
        }
        String limpio = texto.trim();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException("valor vacío");
        }
        try {
            return Integer.parseInt(limpio);
        } catch (NumberFormatException e) {
            // Tolerate "12.0" from a spreadsheet, nothing else.
            try {
                return parsearMonto(limpio).intValueExact();
            } catch (RuntimeException inner) {
                throw new IllegalArgumentException("no es un entero: '" + texto + "'");
            }
        }
    }

    private static final DateTimeFormatter FECHA_ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FECHA_DIA_MES_ANIO =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FECHA_DIA_MES_ANIO_GUION =
            DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FECHA_ANIO_MES_DIA =
            DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FECHA_DIA_MES_ANIO_CORTO =
            new DateTimeFormatterBuilder()
                    .appendPattern("dd/MM/")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Parses {@code yyyy-MM-dd}, {@code dd/MM/yyyy}, {@code dd-MM-yyyy},
     * {@code yyyy/MM/dd} or {@code dd/MM/yy} strictly — an out-of-range date such
     * as {@code 2026-13-45} is rejected, never rolled over.
     */
    @Nonnull
    public static Date parsearFecha(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new IllegalArgumentException("fecha vacía");
        }
        String limpio = texto.trim();
        LocalDate fecha = null;
        for (DateTimeFormatter formato : new DateTimeFormatter[]{
                FECHA_ISO, FECHA_DIA_MES_ANIO, FECHA_DIA_MES_ANIO_GUION,
                FECHA_ANIO_MES_DIA, FECHA_DIA_MES_ANIO_CORTO}) {
            try {
                fecha = LocalDate.parse(limpio, formato);
                break;
            } catch (DateTimeParseException e) {
                // try the next supported layout
            }
        }
        if (fecha == null) {
            throw new IllegalArgumentException("no es una fecha válida: '" + texto + "'");
        }
        return Date.from(fecha.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** Spanish-first boolean parser: {@code Sí/No}, {@code true/false}, {@code 1/0}. */
    @Nonnull
    public static Boolean parsearBooleano(@Nullable String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("valor nulo");
        }
        return switch (ImportacionMasivaEntidad.normalizar(texto)) {
            case "si", "yes", "true", "1", "x" -> Boolean.TRUE;
            case "no", "false", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("no es un booleano: '" + texto + "'");
        };
    }

    // ── Plantilla (espejo de la exportación) ─────────────────────────────────

    /**
     * Builds the import template as an {@code .xlsx} workbook: sheet
     * {@code <Entidad>} with the declared headers plus one example row, and an
     * {@code Instrucciones} sheet describing each column. Required headers carry a
     * trailing {@code " *"}; the importer folds that away when matching.
     */
    @Nonnull
    public byte[] plantillaXlsx(@Nonnull ImportacionMasivaEntidad entidad) throws IOException {
        List<ImportacionMasivaColumna> columnas = columnas(entidad);
        try (Workbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Font fuenteNegrita = libro.createFont();
            fuenteNegrita.setBold(true);
            org.apache.poi.ss.usermodel.CellStyle estiloEncabezado = libro.createCellStyle();
            estiloEncabezado.setFont(fuenteNegrita);
            estiloEncabezado.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            estiloEncabezado.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            estiloEncabezado.setAlignment(HorizontalAlignment.CENTER);

            Sheet hoja = libro.createSheet(entidad.getEtiqueta());
            Row filaEncabezado = hoja.createRow(0);
            Row filaEjemplo = hoja.createRow(1);
            for (int i = 0; i < columnas.size(); i++) {
                ImportacionMasivaColumna columna = columnas.get(i);
                Cell celda = filaEncabezado.createCell(i);
                celda.setCellValue(columna.getEncabezado() + (columna.isRequerido() ? " *" : ""));
                celda.setCellStyle(estiloEncabezado);
                filaEjemplo.createCell(i)
                        .setCellValue(columna.getEjemplo() == null ? "" : columna.getEjemplo());
                hoja.setColumnWidth(i, 22 * 256);
            }

            Sheet instrucciones = libro.createSheet("Instrucciones");
            instrucciones.createRow(0).createCell(0).setCellValue("Columna");
            instrucciones.getRow(0).createCell(1).setCellValue("Obligatoria");
            instrucciones.getRow(0).createCell(2).setCellValue("Descripción");
            for (int i = 0; i < columnas.size(); i++) {
                ImportacionMasivaColumna columna = columnas.get(i);
                Row fila = instrucciones.createRow(i + 1);
                fila.createCell(0).setCellValue(columna.getEncabezado());
                fila.createCell(1).setCellValue(columna.isRequerido() ? "Sí" : "No");
                fila.createCell(2).setCellValue(columna.getDescripcion() == null ? "" : columna.getDescripcion());
            }
            instrucciones.setColumnWidth(0, 26 * 256);
            instrucciones.setColumnWidth(2, 70 * 256);

            libro.write(salida);
            return salida.toByteArray();
        }
    }

    /** The same template as delimited text, using {@code ;} and a quoted header. */
    @Nonnull
    public String plantillaCsv(@Nonnull ImportacionMasivaEntidad entidad) {
        List<ImportacionMasivaColumna> columnas = columnas(entidad);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columnas.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            ImportacionMasivaColumna columna = columnas.get(i);
            sb.append('"').append(columna.getEncabezado())
                    .append(columna.isRequerido() ? " *" : "").append('"');
        }
        sb.append(System.lineSeparator());
        for (int i = 0; i < columnas.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            String ejemplo = columnas.get(i).getEjemplo() == null ? "" : columnas.get(i).getEjemplo();
            if (ejemplo.indexOf(';') >= 0 || ejemplo.indexOf('"') >= 0) {
                sb.append('"').append(ejemplo.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(ejemplo);
            }
        }
        return sb.toString();
    }

    // ── Utilidades ────────────────────────────────────────────────────────────

    /** Authenticated principal attribution, or {@code null} in the REST world. */
    @Nullable
    private Usuarios usuarioActual() {
        try {
            if (identity.isAnonymous() || identity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.debug("No se pudo resolver el usuario actual", e);
            return null;
        }
    }

    @Nonnull
    private static LocalDate aFechaLocal(@Nonnull Date fecha) {
        return fecha.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** Header/schema violations and undecodable uploads. */
    public static class EsquemaInvalidoException extends Exception {
        public EsquemaInvalidoException(@Nonnull String message) {
            super(message);
        }
    }
}
