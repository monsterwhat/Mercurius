package Models.Articulos.Carrito;

import jakarta.annotation.Nullable;

/**
 * Resultado de una operación de carrito que antes se comunicaba directamente
 * con {@code FacesContext.addMessage} / {@code PrimeFaces.executeScript}
 * dentro de {@code Services.CarritoService} (T5, plan
 * mercurius-jsf-to-api-migration).
 * <p>
 * La capa de controlador (p.ej. CrearTiqueteController) traduce este resultado
 * a FacesMessage/executeScript exactamente como se hacía antes:
 * <ul>
 * <li>{@code severity != null} → addMessage(null, new FacesMessage(severity,
 * summary, detail))</li>
 * <li>{@code jsCommand != null} → PrimeFaces.current().executeScript(jsCommand)</li>
 * </ul>
 * Convención de estilo: clase simple con campos públicos, igual que
 * {@code ComprobanteService.CrearComprobanteResult}.
 */
public class CartOperationResult {

    /** Espejo fiel de las severidades de jakarta.faces.application.FacesMessage. */
    public enum Severity {
        INFO, WARN, ERROR
    }

    /**
     * Estado lógico de la operación, para asserts de paridad conductual
     * (suite T9) sin depender de los textos en español.
     */
    public enum Status {
        /** revisarCarrito: carrito con artículos; diálogo de pago listo para abrir. */
        PAGO_DIALOG_LISTO,
        /** revisarCarrito: carrito vacío. */
        CARRITO_VACIO,
        /** processCodigoBarra: artículo agregado al carrito. */
        ARTICULO_AGREGADO,
        /** processCodigoBarra: cantidad inválida (<= 0). */
        CANTIDAD_INVALIDA,
        /** processCodigoBarra: código de barra sin artículo válido. */
        ARTICULO_NO_ENCONTRADO,
        /** processCodigoBarra: código de barra nulo o en blanco. */
        CODIGO_VACIO,
        /** cancel: carrito reiniciado y ventana lista para cerrarse. */
        CANCELADO,
        /** Excepción capturada: alerta registrada; sin mensaje ni script (igual que antes). */
        FALLA_INTERNA
    }

    public final Status status;
    /** Null cuando la operación no muestra mensaje. */
    public final @Nullable Severity severity;
    /** Resumen del FacesMessage original; null si no hay mensaje. */
    public final @Nullable String summary;
    /** Detalle del FacesMessage original; null si no hay mensaje. */
    public final @Nullable String detail;
    /** Comando para PrimeFaces.executeScript; null cuando no hay script. */
    public final @Nullable String jsCommand;

    private CartOperationResult(Status status, @Nullable Severity severity,
            @Nullable String summary, @Nullable String detail, @Nullable String jsCommand) {
        this.status = status;
        this.severity = severity;
        this.summary = summary;
        this.detail = detail;
        this.jsCommand = jsCommand;
    }

    /** Resultado que solo transporta un FacesMessage (sin script). */
    public static CartOperationResult message(Status status, Severity severity, String summary, String detail) {
        return new CartOperationResult(status, severity, summary, detail, null);
    }

    /** Resultado que solo ejecuta un script de PrimeFaces (sin mensaje). */
    public static CartOperationResult script(Status status, String jsCommand) {
        return new CartOperationResult(status, null, null, null, jsCommand);
    }

    /** Resultado sin efecto de UI (p.ej. excepción ya registrada en bitácora). */
    public static CartOperationResult silent(Status status) {
        return new CartOperationResult(status, null, null, null, null);
    }
}
