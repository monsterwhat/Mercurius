package Services;

import Models.Articulos.Articulos;
import Models.Articulos.Carrito.CartOperationResult;
import Models.Articulos.Carrito.CartSessionContext;
import Models.Clientes;
import Models.ComprobantesEmitidos;
import Models.ConfiguracionAplicacion;
import Models.EntradaPago;
import Models.Enums.Tipo_MedioPago;
import Models.Usuarios;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import Utils.PDFGenerator;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Facturación por API: recibe el mínimo (cajero, líneas por código de barras y
 * pagos), resuelve precios/cliente/caja en el servidor y ejecuta el mismo
 * pipeline que el POS (crear comprobante, enviar, PDF, inventario).
 *
 * <p>Reglas de negocio fijadas con el cliente:</p>
 * <ul>
 *   <li>Cliente opcional en tiquete; obligatorio en factura electrónica.</li>
 *   <li>Pagos obligatorios y suficientes; se calcula el vuelto.</li>
 *   <li>El precio SIEMPRE sale del catálogo; el API no puede imponerlo.</li>
 *   <li>Sin stock no es error: se emite y se devuelve una advertencia.</li>
 *   <li>La caja es la sesión abierta asignada al cajero; si no tiene, 409.</li>
 *   <li>Idempotencia por (api client, clave).</li>
 * </ul>
 */
@ApplicationScoped
public class InvoicingService {

    private static final Logger LOG = Logger.getLogger(InvoicingService.class);

    @Inject
    AppSettingsService appSettingsService;

    @Inject
    LoginService loginService;

    @Inject
    ClientService clientService;

    @Inject
    ArticulosService articulosService;

    @Inject
    InventarioService inventarioService;

    @Inject
    CarritoService carritoService;

    @Inject
    CierreCajaService cierreCajaService;

    @Inject
    ComprobanteService comprobanteService;

    @Inject
    ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject
    DocumentoStrategyFactory strategyFactory;

    @Inject
    PDFGenerator pdfGenerator;

    @Inject
    EntityManager em;

    // ─────────────────────────── contrato ───────────────────────────

    public record Linea(@Nonnull String codigoBarra, @Nonnull BigDecimal cantidad) {
    }

    public record Pago(@Nonnull String metodo, @Nonnull BigDecimal monto) {
    }

    public record Solicitud(@Nonnull String idempotencyKey, @Nonnull Long cajeroId,
                            @Nullable String tipoDocumento,
                            @Nullable Long clienteId, @Nullable String clienteCedula,
                            @Nonnull List<Linea> lineas, @Nonnull List<Pago> pagos) {
    }

    public record Resultado(int status, @Nullable String codigo, @Nullable String mensaje,
                            @Nullable Long comprobanteId, @Nullable String consecutivo,
                            @Nullable String clave, @Nullable String estado,
                            @Nullable BigDecimal total, @Nullable BigDecimal vuelto,
                            @Nonnull List<String> advertencias,
                            @Nullable String pdfUrl, @Nullable String tiqueteUrl) {

        static Resultado error(int status, @Nonnull String codigo, @Nonnull String mensaje) {
            return new Resultado(status, codigo, mensaje, null, null, null, null,
                    null, null, List.of(), null, null);
        }
    }

    private record Reserva(boolean nueva, @Nullable Long comprobanteId) {
    }

    // ─────────────────────────── entrada ───────────────────────────

    public @Nonnull Resultado crear(@Nonnull String apiClientId, @Nonnull Solicitud s) {
        // 1. Validaciones de forma.
        if (s.idempotencyKey() == null || s.idempotencyKey().isBlank()) {
            return Resultado.error(400, "IDEMPOTENCY_KEY_REQUERIDA",
                    "idempotencyKey es obligatorio para no duplicar la venta.");
        }
        if (s.cajeroId() == null) {
            return Resultado.error(400, "CAJERO_REQUERIDO", "cajeroId es obligatorio.");
        }
        if (s.lineas() == null || s.lineas().isEmpty()) {
            return Resultado.error(400, "SIN_LINEAS", "Debe enviar al menos una línea.");
        }
        if (s.pagos() == null || s.pagos().isEmpty()) {
            return Resultado.error(400, "SIN_PAGOS", "Debe enviar al menos un pago.");
        }

        String tipo = s.tipoDocumento() == null || s.tipoDocumento().isBlank()
                ? "04" : s.tipoDocumento().trim();
        if (!"01".equals(tipo) && !"04".equals(tipo)) {
            return Resultado.error(400, "TIPO_DOCUMENTO_INVALIDO",
                    "tipoDocumento debe ser 01 (factura) o 04 (tiquete).");
        }

        // 2. Reserva de idempotencia (antes de cualquier escritura).
        Reserva reserva = reservar(apiClientId, s.idempotencyKey());
        if (!reserva.nueva()) {
            if (reserva.comprobanteId() != null) {
                ComprobantesEmitidos previo = comprobantesEmitidosService.find(reserva.comprobanteId());
                if (previo != null) {
                    LOG.info("Venta API replay idempotente: clave=" + s.idempotencyKey()
                            + " comprobante=" + previo.getId()
                            + " | source=InvoicingService.crear()");
                    return respuesta(previo, BigDecimal.ZERO, List.of());
                }
                liberar(apiClientId, s.idempotencyKey());
            } else {
                return Resultado.error(409, "VENTA_EN_PROCESO",
                        "Ya hay una venta con esa idempotencyKey en proceso; espere el resultado.");
            }
        }

        boolean creada = false;
        try {
            // 3. Cajero activo.
            Usuarios cajero = loginService.find(s.cajeroId());
            if (cajero == null || !Boolean.TRUE.equals(cajero.getStatus())) {
                return fallo(apiClientId, s, 400, "CAJERO_INVALIDO",
                        "El cajero no existe o está inactivo.");
            }

            // 4. Caja abierta DEL cajero (no se elige otra).
            if (cierreCajaService.findSesionAbierta(cajero) == null) {
                return fallo(apiClientId, s, 409, "CAJA_CERRADA",
                        "El cajero no tiene una caja abierta. Ábrala antes de facturar.");
            }

            // 5. Cliente: opcional en tiquete, obligatorio en factura.
            DocumentoStrategy strategy = strategyFactory.forCode(tipo);
            Clientes cliente = resolverCliente(s);
            if (strategy.requiresReceptor() && cliente == null) {
                return fallo(apiClientId, s, 400, "CLIENTE_REQUERIDO",
                        "Una factura electrónica requiere clienteCedula (o clienteId).");
            }
            if (strategy.requiresReceptor() && cliente != null) {
                String id = cliente.getIdNumber() == null ? ""
                        : cliente.getIdNumber().replaceAll("[^0-9A-Za-z]", "");
                if (id.isEmpty() || cliente.getHaciendaIdTypeCode() == null) {
                    return fallo(apiClientId, s, 400, "CLIENTE_SIN_IDENTIFICACION",
                            "El cliente no tiene cédula y tipo de identificación válidos.");
                }
            }

            // 6. Carrito resuelto en el servidor (precio del catálogo).
            CartSessionContext ctx = new CartSessionContext();
            ctx.setSelectedClient(cliente);
            List<String> advertencias = new ArrayList<>();
            for (Linea linea : s.lineas()) {
                if (linea == null || linea.codigoBarra() == null || linea.codigoBarra().isBlank()
                        || linea.cantidad() == null || linea.cantidad().signum() <= 0) {
                    return fallo(apiClientId, s, 400, "LINEA_INVALIDA",
                            "Cada línea necesita codigoBarra y cantidad mayor que cero.");
                }
                Articulos articulo = articulosService.findByBarCode(linea.codigoBarra().trim());
                if (articulo == null) {
                    return fallo(apiClientId, s, 400, "ARTICULO_NO_ENCONTRADO",
                            "No existe un artículo con el código de barras "
                                    + linea.codigoBarra().trim() + ".");
                }
                if (!esFacturable(articulo)) {
                    return fallo(apiClientId, s, 400, "ARTICULO_NO_FACTURABLE",
                            "El artículo «" + articulo.getNombre() + "» no está procesado "
                                    + "o no tiene código CAByS.");
                }
                carritoService.addArticulo(ctx, articulo, linea.cantidad());
                double stock = inventarioService.getStock(articulo.getCodigoBarra());
                if (!articulo.isEsServicio() && stock < linea.cantidad().doubleValue()) {
                    String aviso = "Sin stock: " + articulo.getNombre() + " (disponible "
                            + stock + ", vendido " + linea.cantidad() + ")";
                    advertencias.add(aviso);
                    LOG.warn("Venta API sin stock: " + aviso
                            + " | source=InvoicingService.crear() | despues=se emite igual");
                }
            }

            CartOperationResult revision = carritoService.revisarCarrito(ctx);
            if (revision != null && revision.status == CartOperationResult.Status.CARRITO_VACIO) {
                return fallo(apiClientId, s, 409, "CARRITO_VACIO",
                        revision.detail != null ? revision.detail : "No se agregó ninguna línea.");
            }

            // 7. Pagos.
            List<EntradaPago> pagos = new ArrayList<>();
            BigDecimal totalPagado = BigDecimal.ZERO;
            for (Pago pago : s.pagos()) {
                if (pago == null || pago.metodo() == null || pago.monto() == null
                        || pago.monto().signum() <= 0) {
                    return fallo(apiClientId, s, 400, "PAGO_INVALIDO",
                            "Cada pago necesita metodo y un monto mayor que cero.");
                }
                try {
                    Tipo_MedioPago.fromCodigo(pago.metodo());
                } catch (IllegalArgumentException e) {
                    return fallo(apiClientId, s, 400, "METODO_PAGO_INVALIDO",
                            "Método de pago no válido: " + pago.metodo() + ".");
                }
                EntradaPago entrada = new EntradaPago();
                entrada.setMetodoPago(pago.metodo());
                entrada.setMonto(pago.monto());
                pagos.add(entrada);
                totalPagado = totalPagado.add(pago.monto());
            }
            ctx.setColones(totalPagado);
            ctx.setDolares(BigDecimal.ZERO);
            ctx.setTotalPagado(totalPagado);
            ctx.setPago(totalPagado);
            carritoService.calcularVuelto(ctx, BigDecimal.ZERO);
            if (ctx.getVuelto() == null || ctx.getVuelto().signum() < 0) {
                BigDecimal total = carritoService.calculateTotalCarrito(ctx);
                BigDecimal faltante = total.subtract(totalPagado).max(BigDecimal.ZERO);
                return fallo(apiClientId, s, 400, "PAGO_INSUFICIENTE",
                        "El pago no cubre el total; faltan " + faltante.setScale(2, RoundingMode.HALF_UP) + ".");
            }
            BigDecimal vuelto = ctx.getVuelto();

            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            if (settings == null) {
                return fallo(apiClientId, s, 503, "SIN_CONFIGURACION",
                        "No hay configuración de emisión.");
            }

            // 8. Mismo pipeline que el POS.
            ComprobanteService.CrearComprobanteResult result;
            try {
                result = comprobanteService.crearComprobante(
                        settings, ctx.getCarrito(), cliente, cliente, cajero, strategy,
                        pagos, BigDecimal.ZERO, null);
            } catch (ComprobanteService.ComprobanteNoCreadoException e) {
                LOG.warn("Venta API no creó el comprobante: " + e.getMessage()
                        + " | source=InvoicingService.crear()");
                return fallo(apiClientId, s, 400, "COMPROBANTE_INVALIDO", e.getMessage());
            }
            if (result == null || result.comprobante == null) {
                return fallo(apiClientId, s, 500, "COMPROBANTE_ERROR",
                        "No se pudo crear el comprobante.");
            }
            creada = true;
            ComprobantesEmitidos comprobante = result.comprobante;
            marcarComprobante(apiClientId, s.idempotencyKey(), comprobante.getId());

            comprobanteService.enviarComprobanteCreado(result);

            try {
                pdfGenerator.generarPDFComprobante(comprobante, settings);
            } catch (RuntimeException e) {
                LOG.warn("No se pudo generar el PDF de la venta API " + comprobante.getId()
                        + ": " + e.getMessage() + " | source=InvoicingService.crear()", e);
            }

            LOG.info("Venta API creada: comprobante=" + comprobante.getId()
                    + " cajero=" + cajero.getUsername()
                    + " total=" + ctx.getTotalCarrito()
                    + " advertencias=" + advertencias.size()
                    + " | client=" + apiClientId
                    + " | source=InvoicingService.crear()");
            return respuesta(comprobante, vuelto, advertencias);
        } catch (RuntimeException e) {
            if (!creada) {
                liberar(apiClientId, s.idempotencyKey());
            }
            LOG.error("Error creando la venta API: " + e.getMessage()
                    + " | client=" + apiClientId, e);
            return Resultado.error(500, "ERROR_INTERNO", "No se pudo procesar la venta.");
        }
    }

    // ─────────────────────────── helpers ───────────────────────────

    private @Nonnull Resultado fallo(String apiClientId, Solicitud s, int status,
                                     String codigo, String mensaje) {
        liberar(apiClientId, s.idempotencyKey());
        return Resultado.error(status, codigo, mensaje);
    }

    private @Nullable Clientes resolverCliente(Solicitud s) {
        if (s.clienteId() != null) {
            return clientService.find(s.clienteId().intValue());
        }
        if (s.clienteCedula() == null || s.clienteCedula().isBlank()) {
            return null;
        }
        String cedula = s.clienteCedula().replaceAll("[^0-9A-Za-z]", "");
        if (cedula.isEmpty()) {
            return null;
        }
        List<Clientes> found = em.createQuery(
                        "SELECT c FROM Clientes c WHERE "
                                + "function('replace', function('replace', function('replace', "
                                + "c.idNumber, '-', ''), ' ', ''), '.', '') = :cedula",
                        Clientes.class)
                .setParameter("cedula", cedula)
                .setMaxResults(1)
                .getResultList();
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean esFacturable(@Nonnull Articulos articulo) {
        return articulo.isStatus() && articulo.isProcessed()
                && articulo.getCodigoCabys() != null;
    }

    private @Nonnull Resultado respuesta(@Nonnull ComprobantesEmitidos c,
                                          @Nonnull BigDecimal vuelto,
                                          @Nonnull List<String> advertencias) {
        String consecutivo = c.getEncabezado() != null ? c.getEncabezado().getNumeroConsecutivo() : null;
        String clave = c.getHaciendaClave() != null ? c.getHaciendaClave()
                : (c.getEncabezado() != null ? c.getEncabezado().getClave() : null);
        BigDecimal total = c.getResumen() != null ? c.getResumen().getTotalComprobante() : null;
        return new Resultado(200, null, null, c.getId(), consecutivo, clave,
                c.getHaciendaEstado(), total, vuelto, advertencias,
                "/api/app/recibos/" + c.getId() + "/pdf",
                "/api/app/recibos/" + c.getId() + "/tiquete");
    }

    // ─────────────────────── idempotencia ───────────────────────

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    Reserva reservar(@Nonnull String apiClientId, @Nonnull String clave) {
        int insertadas = em.createNativeQuery(
                        "INSERT INTO api_venta_idempotencia (api_client_id, clave, creado_en) "
                                + "VALUES (?, ?, now()) ON CONFLICT (api_client_id, clave) DO NOTHING")
                .setParameter(1, apiClientId)
                .setParameter(2, clave)
                .executeUpdate();
        Object[] fila = (Object[]) em.createNativeQuery(
                        "SELECT id, comprobante_id FROM api_venta_idempotencia "
                                + "WHERE api_client_id = ? AND clave = ?")
                .setParameter(1, apiClientId)
                .setParameter(2, clave)
                .getSingleResult();
        Long comprobanteId = fila[1] == null ? null : ((Number) fila[1]).longValue();
        return new Reserva(insertadas > 0, comprobanteId);
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void marcarComprobante(@Nonnull String apiClientId, @Nonnull String clave, long comprobanteId) {
        em.createNativeQuery(
                        "UPDATE api_venta_idempotencia SET comprobante_id = ?, actualizado_en = now() "
                                + "WHERE api_client_id = ? AND clave = ?")
                .setParameter(1, comprobanteId)
                .setParameter(2, apiClientId)
                .setParameter(3, clave)
                .executeUpdate();
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    void liberar(@Nonnull String apiClientId, @Nonnull String clave) {
        em.createNativeQuery(
                        "DELETE FROM api_venta_idempotencia "
                                + "WHERE api_client_id = ? AND clave = ? AND comprobante_id IS NULL")
                .setParameter(1, apiClientId)
                .setParameter(2, clave)
                .executeUpdate();
    }
}
