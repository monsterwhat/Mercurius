package Utils;

import jakarta.annotation.Nonnull;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for creating Comprobante entities based on XML version detection.
 */
public class ComprobanteFactory {

    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+\\.\\d+)");

    @Nonnull
    public static String detectVersion(@Nonnull InputStream xmlStream) throws IOException {

        if (!xmlStream.markSupported()) {
            xmlStream = new BufferedInputStream(xmlStream);
        }

        xmlStream.mark(4096);

        try {
            byte[] buffer = new byte[4096];
            int bytesRead = xmlStream.read(buffer);

            if (bytesRead <= 0) {
                throw new IOException("Unable to read from XML stream");
            }

            String xmlHeader = new String(buffer, 0, bytesRead);

            Matcher matcher = VERSION_PATTERN.matcher(xmlHeader);
            if (matcher.find()) {
                return matcher.group(1);
            }

            throw new IOException("Unable to detect XML schema version from the document header.");
        }
        finally {
            xmlStream.reset();
        }
    }

    @Nonnull
    public static ComprobantesRecibidos createComprobanteRecibido(@Nonnull InputStream xmlStream) throws IOException {
        String version = detectVersion(xmlStream);
        ComprobantesRecibidos entity = new ComprobantesRecibidos();
        entity.setSchemaVersion(version);
        return entity;
    }

    @Nonnull
    public static ComprobantesEmitidos createComprobanteEmitido(@Nonnull InputStream xmlStream) throws IOException {
        String version = detectVersion(xmlStream);
        ComprobantesEmitidos entity = new ComprobantesEmitidos();
        entity.setSchemaVersion(version);
        return entity;
    }

    @Nonnull
    public static String[] getSupportedVersions() {
        return new String[]{"4.3", "4.4", "4.5"};
    }

    public static boolean isVersionSupported(@Nonnull String version) {
        for (String supported : getSupportedVersions()) {
            if (supported.equals(version)) {
                return true;
            }
        }
        return false;
    }
}
