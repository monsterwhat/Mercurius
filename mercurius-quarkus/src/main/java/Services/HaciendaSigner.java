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
import javax.xml.crypto.dsig.spec.ExcC14NParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
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
            KeyStore keyStore = certificateService.loadKeyStore();
            PrivateKey privateKey = null;
            X509Certificate certificate = null;

            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    privateKey = (PrivateKey) keyStore.getKey(alias, 
                        certificateService.getActiveSettings().getCertificadoPassword().toCharArray());
                    certificate = (X509Certificate) keyStore.getCertificate(alias);
                    String certPassword = certificateService.getActiveSettings().getCertificadoPassword();
                    privateKey = (PrivateKey) keyStore.getKey(alias, 
                        (certPassword != null ? certPassword : "").toCharArray());
                }
            }

            if (privateKey == null || certificate == null) {
                return SignResult.error("No private key or certificate found in keystore");
            }

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputStream is = new ByteArrayInputStream(xmlContent.getBytes("UTF-8"));
            Document document = builder.parse(is);

            XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");

            Reference reference = signatureFactory.newReference("#", 
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
                Collections.singletonList(
                    signatureFactory.newTransform(Transform.ENVELOPED, (javax.xml.crypto.dsig.spec.TransformParameterSpec) null)),
                null, null);

            SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (ExcC14NParameterSpec) null),
                signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                Collections.singletonList(reference));

            KeyInfoFactory keyInfoFactory = signatureFactory.getKeyInfoFactory();
            List<X509Certificate> x509Content = new ArrayList<>();
            x509Content.add(certificate);
            X509Data x509Data = keyInfoFactory.newX509Data(x509Content);
            KeyInfo keyInfo = keyInfoFactory.newKeyInfo(Collections.singletonList(x509Data));

            DOMSignContext signContext = new DOMSignContext(privateKey, document.getDocumentElement());

            XMLSignature signature = signatureFactory.newXMLSignature(signedInfo, keyInfo);
            signature.sign(signContext);

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(outputStream));

            return SignResult.ok(outputStream.toString("UTF-8"));

        } catch (Exception e) {
            System.out.println("Error signing XML: " + e.getMessage());
            e.printStackTrace();
            alertasService.registrarAlerta("Error Firmando XML", "Error al firmar XML: " + e.getMessage(), null, 0, "HaciendaSigner.signXml()", null, e.getMessage());
            return SignResult.error("Error signing XML: " + e.getMessage());
        }
    }

    public String generateInvoiceKey(String identificationNumber, String documentType, 
                                     String branchCode, String terminalCode, 
                                     String consecutiveNumber, String securityCode) {
        StringBuilder key = new StringBuilder();
        
        key.append(String.format("%4s", getCurrentCountryCode()));
        key.append(String.format("%2s", getCurrentDay()));
        key.append(String.format("%2s", getCurrentMonth()));
        key.append(String.format("%2s", getCurrentYear2Digits()));
        key.append(String.format("%12s", identificationNumber));
        key.append(String.format("%2s", documentType));
        key.append(String.format("%3s", branchCode));
        key.append(String.format("%5s", terminalCode));
        key.append(String.format("%8s", consecutiveNumber));
        key.append(String.format("%8s", securityCode));

        return key.toString();
    }

    private String getCurrentCountryCode() {
        return "506";
    }

    private String getCurrentDay() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return String.format("%02d", today.getDayOfMonth());
    }

    private String getCurrentMonth() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return String.format("%02d", today.getMonthValue());
    }

    private String getCurrentYear2Digits() {
        java.time.LocalDate today = java.time.LocalDate.now();
        return String.format("%02d", today.getYear() % 100);
    }
    
    private SignResult signXmlFallback(String xmlContent) {
        System.err.println("FALLBACK: XML signing failed after retries");
        alertasService.registrarAlerta("Error Firmando XML", "Fallo en firma XML despues de reintentos", null, 0, "HaciendaSigner.signXmlFallback()", null, null);
        return SignResult.error("XML signing failed: Service temporarily unavailable. Please try again later.");
    }
}
