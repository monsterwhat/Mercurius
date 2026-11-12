package Models.Jaxb;

import jakarta.xml.bind.annotation.adapters.XmlAdapter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LocalDateTimeAdapter extends XmlAdapter<String, LocalDateTime> {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    public LocalDateTime unmarshal(String v) {
        if (v == null) {
            return null;
        }
        // Hacienda emite FechaEmision con offset (-06:00); ISO_LOCAL_DATE_TIME
        // no lo acepta. ISO_DATE_TIME sí, y devuelve el componente local.
        return LocalDateTime.parse(v, DateTimeFormatter.ISO_DATE_TIME);
    }

    @Override
    public String marshal(LocalDateTime v) {
        return v != null ? v.format(FMT) : null;
    }
}
