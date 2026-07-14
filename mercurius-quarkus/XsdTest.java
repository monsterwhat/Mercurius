import javax.xml.validation.SchemaFactory;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;

public class XsdTest {
    public static void main(String[] args) throws Exception {
        try {
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            System.out.println("SchemaFactory created: " + factory.getClass().getName());
            
            InputStream is = XsdTest.class.getResourceAsStream("/xsd/v4.4/FacturaElectronica_V4.4.xsd");
            if (is == null) {
                System.out.println("FAIL: XSD not found on classpath");
                return;
            }
            System.out.println("XSD found on classpath");
            
            StreamSource source = new StreamSource(is);
            source.setSystemId("/xsd/v4.4/FacturaElectronica_V4.4.xsd");
            
            factory.newSchema(source);
            System.out.println("SUCCESS: Schema compiled!");
        } catch (Exception e) {
            System.out.println("FAIL: " + e.getClass().getName() + " - " + e.getMessage());
            e.printStackTrace();
        }
    }
}
