package TestSupport;

import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;

import org.apache.xalan.xsltc.trax.TransformerFactoryImpl;

public class TestTransformerFactory extends TransformerFactoryImpl {

    @Override
    public void setFeature(String name, boolean value)
            throws TransformerConfigurationException {
        try {
            super.setFeature(name, value);
        } catch (TransformerConfigurationException e) {
            // Silently accept features unsupported by the implementation
        }
    }

    @Override
    public boolean getFeature(String name) {
        try {
            return super.getFeature(name);
        } catch (Exception e) {
            return false;
        }
    }
}
