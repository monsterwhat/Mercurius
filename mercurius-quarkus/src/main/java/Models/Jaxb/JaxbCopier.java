package Models.Jaxb;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.xml.bind.annotation.XmlTransient;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reflection-based field copier for JAXB wrapper subclasses.
 *
 * Copies simple (non-entity, non-collection) fields from a shared-entity instance
 * to a per-document wrapper instance. Entity-typed fields and collection fields
 * are skipped so the wrapper constructor can handle them manually (creating
 * nested wrapper instances).
 *
 * Usage from a wrapper copy-constructor:
 * <pre>{@code
 * public Encabezado(Models.Encabezado.Encabezado src) {
 *     JaxbCopier.copySimpleFields(src, this);
 *     // handle entity fields manually:
 *     if (src.getEmisor() != null) this.emisor = new FE.Emisor(src.getEmisor());
 * }
 * }</pre>
 */
public final class JaxbCopier {

    private JaxbCopier() {}

    /**
     * Copies every non-static, non-{@code @XmlTransient}, non-entity,
     * non-collection field from {@code source} to {@code target}.
     *
     * @param source the shared-entity instance (e.g. {@code Models.Encabezado.Encabezado})
     * @param target the wrapper instance (e.g. {@code Models.Jaxb.FE.Encabezado})
     */
    public static void copySimpleFields(Object source, Object target) {
        for (Field field : getWrappableFields(source.getClass())) {
            try {
                field.setAccessible(true);
                Object value = field.get(source);
                field.set(target, value);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(
                    "Cannot copy field " + field.getName() + " from " + source.getClass().getSimpleName(),
                    e);
            }
        }
    }

    /**
     * Returns all declared fields from {@code clazz} and its superclasses
     * (up to {@code Object}) that should be auto-copied:
     * <ul>
     *   <li>not static</li>
     *   <li>not {@code @XmlTransient}</li>
     *   <li>not a {@code Collection} subtype</li>
     *   <li>not a type annotated {@code @Entity} or {@code @Embeddable}</li>
     * </ul>
     */
    private static List<Field> getWrappableFields(Class<?> clazz) {
        List<Field> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getAnnotation(XmlTransient.class) != null) continue;
                Class<?> type = field.getType();
                if (Collection.class.isAssignableFrom(type)) continue;
                if (type.getAnnotation(Entity.class) != null) continue;
                if (type.getAnnotation(Embeddable.class) != null) continue;
                result.add(field);
            }
            current = current.getSuperclass();
        }
        return result;
    }
}
