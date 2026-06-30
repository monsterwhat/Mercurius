package Services.Strategies;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves a Hacienda document type code (e.g. "01", "04") to its
 * corresponding {@link DocumentoStrategy}.
 *
 * New strategies are registered here when added.
 */
@ApplicationScoped
public class DocumentoStrategyFactory {

    private final Map<String, DocumentoStrategy> strategies = new HashMap<>();

    /** Default strategy when code is null/unknown. */
    static final String DEFAULT_CODE = "04";

    @Inject
    public DocumentoStrategyFactory(TiqueteElectronicoStrategy te, FacturaElectronicaStrategy fe,
                                    NotaCreditoElectronicaStrategy nc, NotaDebitoElectronicaStrategy nd,
                                    FacturaCompraElectronicaStrategy fec, ReciboElectronicoPagoStrategy rep,
                                    FacturaExportacionElectronicaStrategy fee) {
        strategies.put(te.getCodigoDocumento(), te);
        strategies.put(fe.getCodigoDocumento(), fe);
        strategies.put(nc.getCodigoDocumento(), nc);
        strategies.put(nd.getCodigoDocumento(), nd);
        strategies.put(fec.getCodigoDocumento(), fec);
        strategies.put(rep.getCodigoDocumento(), rep);
        strategies.put(fee.getCodigoDocumento(), fee);
    }

    /**
     * Returns the strategy for the given document code.
     * Falls back to TE (code "04") for null or unknown codes.
     */
    public DocumentoStrategy forCode(String codigoDocumento) {
        if (codigoDocumento == null || codigoDocumento.isEmpty()) {
            return strategies.get(DEFAULT_CODE);
        }
        DocumentoStrategy strategy = strategies.get(codigoDocumento);
        return strategy != null ? strategy : strategies.get(DEFAULT_CODE);
    }
}
