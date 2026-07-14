package Services.Strategies;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.AppSettings;
import Models.Clients;
import Models.Encabezado.*;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.CorreoElectronicoReceptor;
import Services.Facturas.EmisorService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared builder for Encabezado fields common across all document strategy types.
 * Eliminates ~70 LOC of duplicated emisor + preamble code per strategy.
 */
public final class EncabezadoBuilder {

    private EncabezadoBuilder() {
        // utility class
    }

    /**
     * Sets the common preamble fields on a new Encabezado.
     * Does NOT touch CondicionVenta or its validation — those are strategy-specific.
     */
    public static void initEncabezado(AppSettings appSettings, Encabezado encabezado, String codigoDocumento) {
        encabezado.setCodigoActividadEmisor(appSettings.getCodigoActividad());
        encabezado.setProveedorSistemas(appSettings.getProvedor());
        encabezado.setNumeroConsecutivo("");
        encabezado.setFechaEmision(LocalDateTime.now().withNano(0));
        encabezado.setCodigoDocumento(codigoDocumento);
    }

    /**
     * Builds a complete Emisor from AppSettings with up to 4 emails, persists it,
     * and returns it. Used by all strategies (FE, TE, NC, ND, FCE, FEE, REP).
     */
    public static Emisor buildEmisor(AppSettings appSettings, EmisorService emisorService) {
        Emisor emisor = new Emisor();
        emisor.setNombre(appSettings.getNombre());

        IdentificacionEmisor emisorId = new IdentificacionEmisor();
        emisorId.setNumero(appSettings.getIdentificacion());
        emisorId.setTipo(appSettings.getTipoIdentificacion());
        emisor.setIdentificacion(emisorId);

        emisor.setNombreComercial(appSettings.getNombreNegocio());

        Ubicacion emisorUbicacion = new Ubicacion();
        emisorUbicacion.setProvincia(appSettings.getProvincia());
        emisorUbicacion.setCanton(appSettings.getCanton());
        emisorUbicacion.setDistrito(appSettings.getDistrito());
        emisorUbicacion.setBarrio(appSettings.getBarrio());
        emisorUbicacion.setOtrasSenas(appSettings.getDireccionCompleta());
        emisor.setUbicacion(emisorUbicacion);

        Telefono emisorTelefono = new Telefono();
        emisorTelefono.setCodigoPais(appSettings.getCodigoPais());
        emisorTelefono.setNumeroTelefono(appSettings.getTelefono());
        emisor.setTelefono(emisorTelefono);

        List<CorreoElectronicoEmisor> correos = new ArrayList<>();
        addEmailIfPresent(correos, emisor, appSettings.getCorreoElectronicoTributacion());
        addEmailIfPresent(correos, emisor, appSettings.getCorreoElectronicoTributacion2());
        addEmailIfPresent(correos, emisor, appSettings.getCorreoElectronicoTributacion3());
        addEmailIfPresent(correos, emisor, appSettings.getCorreoElectronicoTributacion4());
        emisor.setCorreosElectronicos(correos);

        emisorService.create(emisor);
        return emisor;
    }

    /**
     * Builds a Receptor from the given client.
     * Uses getHaciendaIdTypeCode() to map UI idType display names to Hacienda codes.
     * Domestic clients (Cédula Física, Cédula Jurídica) and known foreign types
     * (DIMEX, NITE) go through IdentificacionReceptor with the proper Tipo;
     * unknown idTypes fall back to IdentificacionExtranjero.
     */
    public static Receptor buildReceptor(Clients selectedClient) {
        Receptor receptor = new Receptor();
        receptor.setNombre(selectedClient.getName());
        receptor.setNombreComercial(selectedClient.getName());
        String haciendaCode = selectedClient.getHaciendaIdTypeCode();
        if (haciendaCode != null) {
            // Domestic or DIMEX/NITE with known Hacienda code — use IdentificacionReceptor
            IdentificacionReceptor id = new IdentificacionReceptor();
            id.setNumero(selectedClient.getIdNumber() != null ? selectedClient.getIdNumber() : "");
            id.setTipo(haciendaCode);
            receptor.setIdentificacion(id);
        } else {
            // Unknown idType — fall back to IdentificacionExtranjero
            receptor.setIdentificacionExtranjero(selectedClient.getIdNumber() != null ? selectedClient.getIdNumber() : "");
        }
        if (selectedClient.getEmail() != null && !selectedClient.getEmail().trim().isEmpty()) {
            List<CorreoElectronicoReceptor> correos = new ArrayList<>();
            CorreoElectronicoReceptor correo = new CorreoElectronicoReceptor();
            correo.setCorreo(selectedClient.getEmail().trim());
            correo.setReceptor(receptor);
            correos.add(correo);
            receptor.setCorreosElectronicos(correos);
        }

        return receptor;
    }

    private static void addEmailIfPresent(List<CorreoElectronicoEmisor> list, Emisor emisor, String email) {
        if (email != null && !email.trim().isEmpty()) {
            CorreoElectronicoEmisor correo = new CorreoElectronicoEmisor();
            correo.setCorreo(email);
            correo.setEmisor(emisor);
            list.add(correo);
        }
    }
}
