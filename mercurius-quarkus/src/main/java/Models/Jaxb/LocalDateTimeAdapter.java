package Models.Jaxb;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LocalDateTimeAdapter extends XmlAdapter<String, LocalDateTime> {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public LocalDateTime unmarshal(String v) {
        return v != null ? LocalDateTime.parse(v, FMT) : null;
    }

    @Override
    public String marshal(LocalDateTime v) {
        return v != null ? v.format(FMT) : null;
    }
}
