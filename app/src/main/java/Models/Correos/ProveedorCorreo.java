package Models.Correos;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Mail providers Mercurius knows how to talk to, with the IMAP/SMTP endpoints
 * each one uses.
 *
 * <p>Exists because {@code Services.EmailService} hard-coded
 * {@code smtp.gmail.com}/{@code imap.gmail.com} in six places: the mailbox
 * sweep could not read an Outlook or Yahoo mailbox, and neither could the
 * senders. The presets here are the values those six call sites now resolve
 * through, so the provider is a setting instead of a constant.</p>
 *
 * <p><b>Gmail is the default and nothing changes for an installation that never
 * touched the setting</b>: {@link #desdeClave(String)} maps null/blank/unknown
 * to {@link #GMAIL}, which is exactly what the constants were. A row saved
 * before this setting existed therefore keeps working untouched.</p>
 *
 * <p>{@link #PERSONALIZADO} carries no preset endpoint on purpose: a
 * self-hosted relay has no opinion from us, so the resolver falls back to
 * Gmail's values only when the operator left the custom fields blank, rather
 * than handing {@code null} to the mail client (which fails at connect time
 * with an opaque error).</p>
 *
 * @author Al
 */
public enum ProveedorCorreo {

    GMAIL("Gmail", "imap.gmail.com", 993, "smtp.gmail.com", 587),

    OUTLOOK("Outlook / Hotmail", "outlook.office365.com", 993, "smtp.office365.com", 587),

    YAHOO("Yahoo", "imap.mail.yahoo.com", 993, "smtp.mail.yahoo.com", 587),

    PERSONALIZADO("Personalizado (host propio)", null, null, null, null);

    private final String etiqueta;
    private final String imapHost;
    private final Integer imapPuerto;
    private final String smtpHost;
    private final Integer smtpPuerto;

    ProveedorCorreo(@Nonnull String etiqueta,
                    @Nullable String imapHost,
                    @Nullable Integer imapPuerto,
                    @Nullable String smtpHost,
                    @Nullable Integer smtpPuerto) {
        this.etiqueta = etiqueta;
        this.imapHost = imapHost;
        this.imapPuerto = imapPuerto;
        this.smtpHost = smtpHost;
        this.smtpPuerto = smtpPuerto;
    }

    /** Human-readable label rendered by the settings select. */
    @Nonnull
    public String getEtiqueta() {
        return etiqueta;
    }

    @Nullable
    public String getImapHost() {
        return imapHost;
    }

    @Nullable
    public Integer getImapPuerto() {
        return imapPuerto;
    }

    @Nullable
    public String getSmtpHost() {
        return smtpHost;
    }

    @Nullable
    public Integer getSmtpPuerto() {
        return smtpPuerto;
    }

    /** True when this provider has no built-in endpoints of its own. */
    public boolean esPersonalizado() {
        return imapHost == null;
    }

    /**
     * Maps a stored setting to a provider.
     *
     * <p>Lenient on purpose, and the leniency is deliberate: this value comes
     * from a row written by an older build (column absent), by a hand-edited
     * database, or by a UI that sends whatever the select had. Returning
     * {@link #GMAIL} for anything unrecognised keeps the pre-existing
     * behaviour — the constants these presets replace — instead of turning a
     * cosmetic setting problem into a mailbox that stopped being read.</p>
     *
     * @param clave stored value; null, blank, mixed case and unknown names all
     *              resolve to {@link #GMAIL}
     */
    @Nonnull
    public static ProveedorCorreo desdeClave(@Nullable String clave) {
        if (clave == null || clave.isBlank()) {
            return GMAIL;
        }
        String normalizada = clave.trim().toUpperCase(java.util.Locale.ROOT);
        for (ProveedorCorreo proveedor : values()) {
            if (proveedor.name().equals(normalizada)) {
                return proveedor;
            }
        }
        return GMAIL;
    }
}