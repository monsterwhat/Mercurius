package Services.Strategies;

import Models.AppSettings;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import jakarta.xml.bind.JAXBException;
import java.util.Set;

/**
 * Strategy for building Hacienda electronic document types (FE, TE, ND, NC, etc.).
 * Each implementation handles the type-specific XML root element, JAXB marshalling,
 * encabezado assembly, and validation rules.
 */
public interface DocumentoStrategy {

    /** Hacienda document code: "01" = Factura Electrónica, "04" = Tiquete Electrónico, etc. */
    String getCodigoDocumento();

    /** XML root element name: "FacturaElectronica", "TiqueteElectronico", etc. */
    String getRootElementName();

    /** XML target namespace for this document type. */
    String getNamespace();

    /** Whether this document type requires a receptor with valid identification. */
    boolean requiresReceptor();

    /**
     * Returns the set of CondicionVenta codes permitted for this document type.
     * Per Anexos V4.4, each document type has specific restrictions:
     * - Code 12 is FE-only
     * - Codes 09, 11 are REP-only
     */
    Set<String> getCondicionVentaPermitidas();

    /**
     * Validates that the given CondicionVenta code is allowed for this document type.
     * Throws IllegalArgumentException if the code is not in getCondicionVentaPermitidas().
     */
    default void validarCondicionVenta(String condicionVenta) {
        Set<String> permitidas = getCondicionVentaPermitidas();
        if (condicionVenta != null && !permitidas.contains(condicionVenta)) {
            throw new IllegalArgumentException(
                "CondicionVenta código " + condicionVenta + " no permitido para tipo documento "
                + getCodigoDocumento() + ". Códigos permitidos: " + permitidas
            );
        }
    }

    /**
     * Validates PlazoCredito is set when CondicionVenta is 02 (Crédito) or 10 (Venta a crédito IVA hasta 90 días).
     * Per Anexos V4.4, mandatory for those conditions. PlazoCredito is stored as a numeric string in the entity.
     */
    default void validarPlazoCredito(String condicionVenta, String plazoCredito) {
        if ("02".equals(condicionVenta) || "10".equals(condicionVenta)) {
            if (plazoCredito == null || plazoCredito.trim().isEmpty()) {
                throw new IllegalArgumentException(
                    "PlazoCredito es obligatorio cuando CondicionVenta es " + condicionVenta
                    + " (Crédito o Venta a crédito IVA hasta 90 días). Debe ser un valor mayor a cero."
                );
            }
            try {
                int plazo = Integer.parseInt(plazoCredito.trim());
                if (plazo <= 0) {
                    throw new IllegalArgumentException(
                        "PlazoCredito debe ser mayor a cero cuando CondicionVenta es " + condicionVenta
                    );
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "PlazoCredito debe ser un valor numérico válido, recibido: " + plazoCredito
                );
            }
        }
    }

    /**
     * Produces the unsigned XML string for the given comprobante, using
     * the type-specific root element and namespace.
     */
    String buildXml(ComprobantesEmitidos comprobante) throws JAXBException;

    /**
     * Builds an Encabezado tailored to this document type.
     * For TE the receptor is optional; for FE/ND/NC it is required.
     */
    Encabezado buildEncabezado(AppSettings appSettings, Clients selectedClient);
}
