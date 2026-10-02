package Models.Correos;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * The four endpoints {@code Services.EmailService} needs to open a mailbox or
 * hand a message to a relay, already resolved from the provider setting.
 *
 * <p>Immutable value, so the senders and the mailbox sweep can be unit-tested
 * against it without a settings row, a database or a mail server.</p>
 *
 * <p>The resolution rules, in order:</p>
 * <ol>
 *   <li>Preset providers supply their own host/port.</li>
 *   <li>A custom host/port that was saved explicitly wins over the preset — an
 *       operator who typed {@code imap.exchange.midominio.com} means it, even
 *       if they left the preset at Gmail.</li>
 *   <li>Blank or out-of-range custom values fall back to the preset, and a
 *       preset with no values of its own ({@link ProveedorCorreo#PERSONALIZADO})
 *       falls back to Gmail. The result is never null and never an invalid
 *       port, so a half-filled form cannot produce a mailbox that silently
 *       stops being read.</li>
 * </ol>
 *
 * @author Al
 */
public final class ConfiguracionConexion {

    private ConfiguracionConexion() {
    }

    /**
     * Resolves the endpoints for a provider plus optional operator overrides.
     *
     * @param proveedor  preset to start from; {@link ProveedorCorreo#desdeClave}
     *                   is the caller's job (it is lenient by design)
     * @param imapHost   custom IMAP host, or null/blank to use the preset
     * @param imapPuerto custom IMAP port, or null/blank/out-of-range to use the
     *                   preset
     * @param smtpHost   custom SMTP host, or null/blank to use the preset
     * @param smtpPuerto custom SMTP port, or null/blank/out-of-range to use the
     *                   preset
     */
    @Nonnull
    public static Resuelta resolver(@Nonnull ProveedorCorreo proveedor,
                                   @Nullable String imapHost,
                                   @Nullable String imapPuerto,
                                   @Nullable String smtpHost,
                                   @Nullable String smtpPuerto) {
        return new Resuelta(
                proveedor,
                textoOValido(imapHost, proveedor.getImapHost(), ProveedorCorreo.GMAIL.getImapHost()),
                puertoOValido(imapPuerto, proveedor.getImapPuerto(), ProveedorCorreo.GMAIL.getImapPuerto()),
                textoOValido(smtpHost, proveedor.getSmtpHost(), ProveedorCorreo.GMAIL.getSmtpHost()),
                puertoOValido(smtpPuerto, proveedor.getSmtpPuerto(), ProveedorCorreo.GMAIL.getSmtpPuerto()));
    }

    /**
     * Resolves from a stored provider key, tolerating null/blank/unknown.
     *
     * @param clave stored provider value; see {@link ProveedorCorreo#desdeClave}
     */
    @Nonnull
    public static Resuelta desdeClave(@Nullable String clave,
                                      @Nullable String imapHost,
                                      @Nullable String imapPuerto,
                                      @Nullable String smtpHost,
                                      @Nullable String smtpPuerto) {
        return resolver(ProveedorCorreo.desdeClave(clave), imapHost, imapPuerto, smtpHost, smtpPuerto);
    }

    /** First non-blank of the operator value, the preset value and the fallback. */
    @Nonnull
    private static String textoOValido(@Nullable String elegido,
                                       @Nullable String delProveedor,
                                       @Nullable String porDefecto) {
        if (elegido != null && !elegido.isBlank()) {
            return elegido.trim();
        }
        if (delProveedor != null && !delProveedor.isBlank()) {
            return delProveedor;
        }
        // Solo PERSONALIZADO llega aqui sin valores propios. Devolver el host
        // de Gmail mantiene el contrato "nunca null": un formulario a medias
        // falla al conectar con un error visible, no con un NPE en el cliente
        // de correo a mitad del barrido de la bandeja.
        return porDefecto != null ? porDefecto : "";
    }

    /** Parses the operator port, rejecting anything outside 1..65535. */
    @Nonnull
    private static int puertoOValido(@Nullable String elegido,
                                     @Nullable Integer delProveedor,
                                     @Nullable Integer porDefecto) {
        if (elegido != null && !elegido.isBlank()) {
            try {
                int puerto = Integer.parseInt(elegido.trim());
                if (puerto >= 1 && puerto <= 65535) {
                    return puerto;
                }
            } catch (NumberFormatException e) {
                // No es un entero: cae al preset.
            }
        }
        if (delProveedor != null) {
            return delProveedor;
        }
        return porDefecto != null ? porDefecto : 587;
    }

    /**
     * Endpoints resolved. Ports are primitives: an invalid port cannot survive
     * construction, which is the whole point of resolving them here instead of
     * at connect time.
     */
    public record Resuelta(@Nonnull ProveedorCorreo proveedor,
                           @Nonnull String imapHost,
                           int imapPuerto,
                           @Nonnull String smtpHost,
                           int smtpPuerto) {

        /** Convenience for the SMTP props block every sender builds. */
        @Nonnull
        public java.util.Properties propiedadesSmtp() {
            java.util.Properties props = new java.util.Properties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.host", smtpHost);
            props.put("mail.smtp.port", String.valueOf(smtpPuerto));
            return props;
        }
    }
}