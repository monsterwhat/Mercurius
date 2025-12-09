package Models;

import Models.ComprobantesV44.ComprobantesRecibidos;
import Models.ComprobantesV45.ComprobantesRecibidosV45;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Factory for creating Comprobante entities based on XML version detection.
 */
public class ComprobanteFactory {

    // More flexible pattern: matches v4.3, v4.4, v4.5 anywhere in the header
    private static final Pattern VERSION_PATTERN = Pattern.compile("v(\\d+\\.\\d+)");

    /**
     * Detects the XML version from the input stream.
     * @param xmlStream InputStream containing XML data
     * @return Version string (e.g., "4.3", "4.4", "4.5")
     * @throws IOException if unable to read from stream
     */
    public static String detectVersion(InputStream xmlStream) throws IOException {

        // Guarantee mark/reset support
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
                return matcher.group(1);  // return the detected version
            }

            throw new IOException("Unable to detect XML schema version from the document header.");
        }
        finally {
            xmlStream.reset();
        }
    }

    /**
     * Creates the appropriate ComprobantesRecibidos entity based on XML version.
     * @param xmlStream InputStream containing XML data
     * @return Instance of ComprobantesRecibidos (4.3/4.4) or ComprobantesRecibidosV45 (4.5)
     * @throws IOException if unable to read from stream or detect version
     * @throws UnsupportedOperationException if version is unsupported
     */
    public static Object createComprobanteRecibido(InputStream xmlStream) throws IOException {
        String version = detectVersion(xmlStream);

        return switch (version) {
            case "4.3", "4.4" -> new ComprobantesRecibidos();
            case "4.5" -> new ComprobantesRecibidosV45();
            default -> throw new UnsupportedOperationException("Unsupported XML version: " + version);
        };
    }

    /**
     * Gets the supported versions.
     * @return Array of supported version strings.
     */
    public static String[] getSupportedVersions() {
        return new String[]{"4.3", "4.4", "4.5"};
    }

    /**
     * Checks if a version is supported.
     * @param version Version string to check
     * @return true if supported, false otherwise
     */
    public static boolean isVersionSupported(String version) {
        for (String supported : getSupportedVersions()) {
            if (supported.equals(version)) {
                return true;
            }
        }
        return false;
    }
}
