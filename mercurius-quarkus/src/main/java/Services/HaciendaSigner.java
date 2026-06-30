package Services;

import Models.Users;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import Models.ComprobantesEmitidos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import javax.security.auth.x500.X500Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Fallback;

@Named
@ApplicationScoped
public class HaciendaSigner {

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
                    String certPassword = certificateService.getActiveSettings().getCertificadoPassword();
                    privateKey = (PrivateKey) keyStore.getKey(alias,
                        (certPassword != null ? certPassword : "").toCharArray());
                    certificate = (X509Certificate) keyStore.getCertificate(alias);
                }
            }

            if (privateKey == null || certificate == null) {
                return SignResult.error("No private key or certificate found in keystore");
            }

            // ── XAdES-EPES: Build QualifyingProperties ──────────────────────
            String xadesNs = "http://uri.etsi.org/01903/v1.3.2#";
            String dsNs    = "http://www.w3.org/2000/09/xmldsig#";

            Element qualifyingProps = doc.createElementNS(xadesNs, "xades:QualifyingProperties");
            qualifyingProps.setAttributeNS(null, "Target", "#signatureId");

            Element signedProps = doc.createElementNS(xadesNs, "xades:SignedProperties");
            signedProps.setAttributeNS(null, "Id", "signedPropsId");

            Element signedSigProps = doc.createElementNS(xadesNs, "xades:SignedSignatureProperties");

            // SigningTime
            Element signingTime = doc.createElementNS(xadesNs, "xades:SigningTime");
            signingTime.setTextContent(ZonedDateTime.now().format(DateTimeFormatter.ISO_INSTANT));
            signedSigProps.appendChild(signingTime);

            // SigningCertificate → Cert → CertDigest + IssuerSerial
            Element signingCert = doc.createElementNS(xadesNs, "xades:SigningCertificate");
            Element cert = doc.createElementNS(xadesNs, "xades:Cert");

            // CertDigest (SHA-256 of the certificate)
            Element certDigest = doc.createElementNS(xadesNs, "xades:CertDigest");
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] certDigestVal = md.digest(certificate.getEncoded());

            Element digestMethod = doc.createElementNS(dsNs, "ds:DigestMethod");
            digestMethod.setAttributeNS(null, "Algorithm", DigestMethod.SHA256);
            Element digestValue = doc.createElementNS(dsNs, "ds:DigestValue");
            digestValue.setTextContent(java.util.Base64.getEncoder().encodeToString(certDigestVal));

            certDigest.appendChild(digestMethod);
            certDigest.appendChild(digestValue);
            cert.appendChild(certDigest);

            // IssuerSerial
            Element issuerSerial = doc.createElementNS(xadesNs, "xades:IssuerSerial");
            X500Principal issuerPrincipal = certificate.getIssuerX500Principal();

            Element x509IssuerName = doc.createElementNS(dsNs, "ds:X509IssuerName");
            x509IssuerName.setTextContent(issuerPrincipal.getName());

            Element x509SerialNumber = doc.createElementNS(dsNs, "ds:X509SerialNumber");
            x509SerialNumber.setTextContent(certificate.getSerialNumber().toString());

            issuerSerial.appendChild(x509IssuerName);
            issuerSerial.appendChild(x509SerialNumber);
            cert.appendChild(issuerSerial);
            signingCert.appendChild(cert);
            signedSigProps.appendChild(signingCert);

            // SignaturePolicyIdentifier → SignaturePolicyImplied (EPES)
            Element sigPolicyId = doc.createElementNS(xadesNs, "xades:SignaturePolicyIdentifier");
            Element sigPolicyImplied = doc.createElementNS(xadesNs, "xades:SignaturePolicyImplied");
            sigPolicyId.appendChild(sigPolicyImplied);
            signedSigProps.appendChild(sigPolicyId);

            signedProps.appendChild(signedSigProps);
            qualifyingProps.appendChild(signedProps);

            // Insert QualifyingProperties into the document BEFORE signing
            doc.getDocumentElement().appendChild(qualifyingProps);

            // ── Create the XML Signature with TWO references ─────────────────
            XMLSignatureFactory sigFactory = XMLSignatureFactory.getInstance("DOM");

            // Reference 1: the document (enveloped → removes itself from digest)
            Reference contentRef = sigFactory.newReference("",
                sigFactory.newDigestMethod(DigestMethod.SHA256, null),
                Collections.singletonList(
                    sigFactory.newTransform(Transform.ENVELOPED, (javax.xml.crypto.dsig.spec.TransformParameterSpec) null)),
                null, null);

            // Reference 2: XAdES SignedProperties (type per ETSI TS 101 903)
            Reference signedPropsRef = sigFactory.newReference("#signedPropsId",
                sigFactory.newDigestMethod(DigestMethod.SHA256, null),
                Collections.emptyList(),
                "http://uri.etsi.org/01903#SignedProperties", null);

            SignedInfo signedInfo = sigFactory.newSignedInfo(
                sigFactory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                sigFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                Arrays.asList(contentRef, signedPropsRef));

            // KeyInfo with X509Data
            KeyInfoFactory kiFactory = sigFactory.getKeyInfoFactory();
            List<X509Certificate> x509Content = new ArrayList<>();
            x509Content.add(certificate);
            X509Data x509Data = kiFactory.newX509Data(x509Content);
            KeyInfo keyInfo = kiFactory.newKeyInfo(Collections.singletonList(x509Data));

            // Sign — XMLSignature(id="signatureId") so QualifyingProperties.Target resolves
            DOMSignContext signContext = new DOMSignContext(privateKey, doc.getDocumentElement());
            XMLSignature signature = sigFactory.newXMLSignature(signedInfo, keyInfo, null, "signatureId", null);
            signature.sign(signContext);

            // Serialize
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(doc), new StreamResult(outputStream));

            return SignResult.ok(outputStream.toString("UTF-8"));

        } catch (Exception e) {
            alertasService.registrarAlerta("Error Firmando XML", "Error al firmar XML: " + e.getMessage(), null, 0, "HaciendaSigner.signXml()", null, e.getMessage());
            return SignResult.error("Error signing XML: " + e.getMessage());
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
            java.util.logging.Logger.getLogger(HaciendaSigner.class.getName())
                .warning("padLeftZeros: non-digit characters stripped from '" + input + "'");
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

    public static String marshalComprobante(ComprobantesEmitidos comprobante) throws JAXBException {
        JAXBContext context = JAXBContext.newInstance(ComprobantesEmitidos.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter sw = new StringWriter();
        marshaller.marshal(comprobante, sw);
        return sw.toString();
    }
}
