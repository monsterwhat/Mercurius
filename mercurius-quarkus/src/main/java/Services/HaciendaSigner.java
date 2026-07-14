package Services;

import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import xades4j.production.XadesEpesSigningProfile;
import xades4j.production.XadesSigner;
import xades4j.production.Enveloped;
import xades4j.production.SigningCertificateMode;
import xades4j.production.BasicSignatureOptions;
import xades4j.properties.SignaturePolicyIdentifierProperty;
import xades4j.properties.ObjectIdentifier;
import xades4j.properties.IdentifierType;
import xades4j.providers.KeyingDataProvider;
import xades4j.providers.SignaturePolicyInfoProvider;
import xades4j.providers.impl.DirectKeyingDataProvider;
import xades4j.XAdES4jException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Enumeration;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Fallback;

@Named
@ApplicationScoped
public class HaciendaSigner {

    private static final java.util.logging.Logger LOG =
        java.util.logging.Logger.getLogger(HaciendaSigner.class.getName());

    private final HaciendaCertificateService certificateService;
    
    @Inject AlertasService alertasService;

    @Inject HaciendaXsdValidator xsdValidator;

    @Inject
    public HaciendaSigner(HaciendaCertificateService certificateService) {
        this.certificateService = certificateService;
    }

    public static class SignResult {
        public boolean success;
        public String signedXml;
        public String errorMessage;

        public static SignResult ok(String xml) {
            SignResult result = new SignResult();
            result.success = true;
            result.signedXml = xml;
            return result;
        }

        public static SignResult error(String message) {
            SignResult result = new SignResult();
            result.success = false;
            result.errorMessage = message;
            return result;
        }
    }

    @Retry(maxRetries = 2, delay = 500)
    @Fallback(fallbackMethod = "signXmlFallback")
    public SignResult signXml(String xmlContent) {
        try {
            // ── 1. Parse and XSD-validate before any cryptographic work ───────
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // XXE prevention (OWASP)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputStream is = new ByteArrayInputStream(xmlContent.getBytes("UTF-8"));
            Document doc = builder.parse(is);

            String rootNs = doc.getDocumentElement().getNamespaceURI();
            if (rootNs == null || rootNs.isEmpty()) {
                return SignResult.error("XML document has no namespace — cannot validate");
            }

            HaciendaXsdValidator.ValidationResult vr = xsdValidator.validate(xmlContent, rootNs);
            if (!vr.valid) {
                alertasService.registrarAlerta("Error Validacion XSD",
                    "El XML no pasó la validación contra el esquema XSD: " + vr.errorMessage,
                    null, 0, "HaciendaSigner.signXml()", null, xmlContent);
                return SignResult.error("XSD validation failed: " + vr.errorMessage);
            }

            // ── 2. Load keystore & certificate ───────────────────────────────
            KeyStore keyStore = certificateService.loadKeyStore();
            PrivateKey privateKey = null;
            X509Certificate certificate = null;

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    String certPassword = certificateService.getDecryptedCertificadoPassword();
                    privateKey = (PrivateKey) keyStore.getKey(alias,
                        (certPassword != null ? certPassword : "").toCharArray());
                    certificate = (X509Certificate) keyStore.getCertificate(alias);
                }
            }

            if (privateKey == null || certificate == null) {
                return SignResult.error("No private key or certificate found in keystore");
            }

            // ── XAdES-EPES: Build signature using xades4j ────────────────
            KeyingDataProvider kdp = new DirectKeyingDataProvider(certificate, privateKey);

            SignaturePolicyInfoProvider policyInfoProvider = () ->
                new SignaturePolicyIdentifierProperty(
                    new ObjectIdentifier(
                        "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.2/facturaElectronica",
                        IdentifierType.URI,
                        "Política de firma para factura electrónica de Costa Rica"),
                    new ByteArrayInputStream("Politica de Factura Digital".getBytes()));

            XadesSigner signer = new XadesEpesSigningProfile(kdp, policyInfoProvider)
                .withBasicSignatureOptions(new BasicSignatureOptions()
                    .includeSigningCertificate(SigningCertificateMode.SIGNING_CERTIFICATE)
                    .includeSubjectName(true)
                    .includeIssuerSerial(true))
                .newSigner();

            Element elemToSign = doc.getDocumentElement();
            new Enveloped(signer).sign(elemToSign);

            // Serialize
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            transformerFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            transformerFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Transformer transformer = transformerFactory.newTransformer();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(outputStream));

            return SignResult.ok(outputStream.toString("UTF-8"));

        } catch (ParserConfigurationException | SAXException | IOException | GeneralSecurityException | TransformerException | XAdES4jException e) {
            alertasService.registrarAlerta("Error Firmando XML", "Error al firmar XML: " + e.getMessage(), null, 0, "HaciendaSigner.signXml()", null, e.getMessage());
            throw new RuntimeException("Error signing XML: " + e.getMessage(), e);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error Firmando XML", "Error al firmar XML: " + e.getMessage(), null, 0, "HaciendaSigner.signXml()", null, e.getMessage());
            throw new RuntimeException("Error signing XML: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a 50-digit Hacienda document key (clave) with:
     * <ul>
     *   <li>Fecha emisión from the invoice date (not system clock)</li>
     *   <li>7-digit random security code + 1 check digit (módulo 10, per Hacienda spec)</li>
     * </ul>
     *
     * @param identificationNumber Emisor's ID (cedula jurídica/física)
     * @param consecutiveNumber   20-digit consecutive (sucursal + terminal + tipo doc + secuencial)
     * @param situation           "1" for normal, "2" for corrige, etc.
     * @param fechaEmision        The invoice's emission date (becomes DDMMYY embedded in clave)
     * @return 50-digit clave
     */
    public String generateInvoiceKey(String identificationNumber,
                                     String consecutiveNumber,
                                     String situation,
                                     LocalDate fechaEmision) {
        StringBuilder key = new StringBuilder(50);

        key.append(getCurrentCountryCode());
        key.append(getDay(fechaEmision));
        key.append(getMonth(fechaEmision));
        key.append(getYear2Digits(fechaEmision));
        key.append(padLeftZeros(identificationNumber, 12));
        key.append(padLeftZeros(consecutiveNumber, 20));
        key.append(situation != null ? situation : "1");

        // Generate 7 random digits for the security code (positions 43-49)
        int random7 = ThreadLocalRandom.current().nextInt(10_000_000);
        key.append(String.format("%07d", random7));

        // Calculate check digit (position 50) using módulo 10 over the first 49 digits
        int checkDigit = calcularDigitoVerificador(key.toString());
        key.append(checkDigit);

        return key.toString();
    }

    /**
     * Computes the Hacienda check digit (módulo 10 / Luhn variant) for the first 49
     * digits of a document key.  Process right-to-left with alternating weights 2,1.
     * Sum of digits of each product; check digit = (10 - sum%10) % 10.
     */
    public static int calcularDigitoVerificador(String prefix49) {
        if (prefix49 == null || prefix49.length() != 49) {
            throw new IllegalArgumentException("Prefix must be exactly 49 digits, got " +
                (prefix49 == null ? "null" : prefix49.length()));
        }
        int sum = 0;
        boolean weightTwo = true;  // rightmost starts with weight 2
        for (int i = prefix49.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(prefix49.charAt(i));
            if (digit < 0 || digit > 9) {
                throw new IllegalArgumentException("Non-digit character at position " + i);
            }
            int product = digit * (weightTwo ? 2 : 1);
            // Sum of digits of the product (e.g. 16 → 1+6 = 7)
            sum += product > 9 ? product - 9 : product;
            weightTwo = !weightTwo;
        }
        return (10 - (sum % 10)) % 10;
    }

    private static String padLeftZeros(String input, int length) {
        if (input == null || input.isEmpty()) {
            char[] zeros = new char[length];
            java.util.Arrays.fill(zeros, '0');
            return new String(zeros);
        }
        String stripped = input.replaceAll("[^0-9]", "");
        if (stripped.length() != input.length()) {
            LOG.warning("Clave numeric: non-digit chars stripped from '" + maskInput(input)
                + "' — clave must be 50-digit numeric per Hacienda spec");
        }
        if (stripped.isEmpty()) {
            char[] zeros = new char[length];
            java.util.Arrays.fill(zeros, '0');
            return new String(zeros);
        }
        if (stripped.length() >= length) {
            return stripped;
        }
        char[] zeros = new char[length - stripped.length()];
        java.util.Arrays.fill(zeros, '0');
        return new String(zeros) + stripped;
    }

    /** Masks all but last 4 chars for safe logging (e.g. cédula numbers). */
    private static String maskInput(String input) {
        if (input == null) return "null";
        int len = input.length();
        if (len <= 4) return "****";
        return input.substring(0, len - 4).replaceAll(".", "*") + input.substring(len - 4);
    }

    private String getCurrentCountryCode() {
        return "506";
    }

    /** Day from the invoice date (DD), so the clave matches the XML FechaEmision. */
    private static String getDay(LocalDate date) {
        return String.format("%02d", date.getDayOfMonth());
    }

    /** Month from the invoice date (MM). */
    private static String getMonth(LocalDate date) {
        return String.format("%02d", date.getMonthValue());
    }

    /** Two-digit year from the invoice date (YY). */
    private static String getYear2Digits(LocalDate date) {
        return String.format("%02d", date.getYear() % 100);
    }
    
    private SignResult signXmlFallback(String xmlContent) {
        alertasService.registrarAlerta("Error", "FALLBACK: XML signing failed after retries", null, 0, "HaciendaSigner.signXmlFallback()", null, null);
        alertasService.registrarAlerta("Error Firmando XML", "Fallo en firma XML despues de reintentos", null, 0, "HaciendaSigner.signXmlFallback()", null, null);
        return SignResult.error("XML signing failed: Service temporarily unavailable. Please try again later.");
    }

}
