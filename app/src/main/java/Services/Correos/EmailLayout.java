package Services.Correos;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Envoltorio HTML de los correos que envía Mercurius.
 *
 * <p>Usa tablas y estilos en línea (los clientes de correo ignoran hojas de
 * estilo externas), ancho máximo 600px y una cabecera con el nombre del
 * negocio. El cuerpo en texto plano se escapa y se convierte en párrafos, así
 * que nunca se inyecta HTML del usuario sin querer.</p>
 */
public final class EmailLayout {

    private EmailLayout() {
    }

    /** Azul de marca usado en la cabecera y los títulos. */
    private static final String BRAND = "#0b3b8c";

    /**
     * Envuelve un cuerpo de texto plano en el layout de marca.
     *
     * @param titulo  asunto/título visible del correo (se escapa)
     * @param cuerpo  cuerpo en texto plano (se escapa y se parte por líneas)
     * @param negocio nombre del negocio para la cabecera (null → "Mercurius")
     */
    @Nonnull
    public static String wrap(@Nonnull String titulo, @Nonnull String cuerpo, @Nullable String negocio) {
        String parrafos = Arrays.stream(cuerpo.split("\\R", -1))
                .map(String::trim)
                .map(linea -> linea.isEmpty()
                        ? "<div style=\"height:10px;line-height:10px\">&nbsp;</div>"
                        : "<p style=\"margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55\">"
                                + escape(linea) + "</p>")
                .collect(Collectors.joining());
        return envolver(titulo, parrafos, negocio);
    }

    /**
     * Envuelve un cuerpo que YA es HTML (las plantillas de correo). No se
     * escapa: las plantillas las escribe un administrador en la app y están
     * pensadas para llevar tablas y estilos propios. Para contenido de origen
     * externo use {@link #wrap(String, String, String)}, que sí escapa.
     *
     * <p>La vista previa de plantillas lo muestra dentro de un {@code iframe}
     * con {@code sandbox} para que un HTML malicioso no ejecute scripts en la
     * aplicación.</p>
     */
    @Nonnull
    public static String wrapHtml(@Nonnull String titulo, @Nonnull String cuerpoHtml, @Nullable String negocio) {
        return envolver(titulo, cuerpoHtml, negocio);
    }

    @Nonnull
    private static String envolver(@Nonnull String titulo, @Nonnull String cuerpoRenderizado,
                                   @Nullable String negocio) {
        String nombre = negocio == null || negocio.isBlank() ? "Mercurius" : negocio.trim();

        return "<!doctype html>"
                + "<html lang=\"es\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escape(titulo) + "</title></head>"
                + "<body style=\"margin:0;padding:24px 12px;background:#f4f6fb;"
                + "font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">"
                + "<tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"width:100%;max-width:600px;background:#ffffff;border:1px solid #e5e9f2;"
                + "border-radius:12px;overflow:hidden\">"
                + "<tr><td style=\"background:" + BRAND + ";padding:20px 28px;color:#ffffff;"
                + "font-size:18px;font-weight:700;letter-spacing:.2px\">" + escape(nombre) + "</td></tr>"
                + "<tr><td style=\"padding:28px 28px 8px\">"
                + "<h1 style=\"margin:0 0 18px;color:" + BRAND + ";font-size:20px;line-height:1.3\">"
                + escape(titulo) + "</h1>"
                + cuerpoRenderizado
                + "</td></tr>"
                + "<tr><td style=\"padding:16px 28px;background:#f8fafc;border-top:1px solid #eef2f7;"
                + "color:#6b7280;font-size:12px;line-height:1.5\">"
                + "Enviado por Mercurius · " + escape(nombre)
                + "<br>Este es un correo automático; no responda a esta dirección.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** Escapa los cinco caracteres peligrosos de HTML. */
    @Nonnull
    public static String escape(@Nullable String valor) {
        if (valor == null) {
            return "";
        }
        return valor.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
