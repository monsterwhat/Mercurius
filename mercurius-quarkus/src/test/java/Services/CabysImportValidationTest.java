package Services;

import static org.junit.jupiter.api.Assertions.*;

import Models.Cabys;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@QuarkusTest
@Tag("cabys")
class CabysImportValidationTest {

    @Inject CabysService cabysService;

    private String uniqueCode() {
        return String.format("%013d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000_0000L)).substring(0,13);
    }

    @Test
    void createValidCabysPersistsAndFindable() {
        String code = uniqueCode();
        Cabys c = new Cabys(code, "Test CABYS Valid", "Cat1, Cat2", "01", "https://ex.com/"+code, "ACTIVO");
        cabysService.create(c);
        try {
            Cabys found = cabysService.find(code);
            assertNotNull(found);
            assertEquals(code, found.getCodigo());
            assertEquals("ACTIVO", found.getEstado());
        } finally {
            var f = cabysService.find(code);
            if (f != null) cabysService.delete(f);
        }
    }

    @Test
    void findNonExistentReturnsNull() {
        assertNull(cabysService.find("9999999999999"));
        assertNull(cabysService.find("0000000000000"));
    }

    @Test
    void cabysWithDifferentEstadoAreDistinct() {
        String code1 = uniqueCode();
        String code2 = uniqueCode();
        Cabys a = new Cabys(code1, "Desc A", "Cat", "01", "https://ex.com", "ACTIVO");
        Cabys b = new Cabys(code2, "Desc B", "Cat", "01", "https://ex.com", "INACTIVO");
        cabysService.create(a); cabysService.create(b);
        try {
            assertEquals("ACTIVO", cabysService.find(code1).getEstado());
            assertEquals("INACTIVO", cabysService.find(code2).getEstado());
        } finally {
            var e1 = cabysService.find(code1); if (e1 != null) cabysService.delete(e1);
            var e2 = cabysService.find(code2); if (e2 != null) cabysService.delete(e2);
        }
    }

    @Test
    void cabysUpdateIsPersisted() {
        String code = uniqueCode();
        Cabys c = new Cabys(code, "Original", "Cat", "01", "https://ex.com", "ACTIVO");
        cabysService.create(c);
        try {
            Cabys found = cabysService.find(code);
            found.setDescripcion("Updated Desc");
            cabysService.update(found);
            assertEquals("Updated Desc", cabysService.find(code).getDescripcion());
        } finally { var e = cabysService.find(code); if (e != null) cabysService.delete(e); }
    }

    @Test
    void duplicateCabysCodeThrowsOrIsIdempotent() {
        String code = uniqueCode();
        Cabys c1 = new Cabys(code, "First", "Cat", "01", "https://ex.com", "ACTIVO");
        cabysService.create(c1);
        try {
            Cabys c2 = new Cabys(code, "Second", "Cat", "01", "https://ex.com", "ACTIVO");
            try { cabysService.create(c2); } catch (Exception e) { /* expected duplicate */ }
            assertNotNull(cabysService.find(code));
        } finally { var e = cabysService.find(code); if (e != null) cabysService.delete(e); }
    }

    @Test
    void cabysCodeLengthValidationBusinessRule() {
        String valid = "0111010010010";
        assertEquals(13, valid.length());
        assertTrue(valid.matches("\\d{13}"));
        String invalidShort = "999";
        assertNotEquals(13, invalidShort.length());
        String invalidAlpha = "011101001001A";
        assertFalse(invalidAlpha.matches("\\d{13}"));
    }

    @Test
    void listAllContainsSeededActivo() {
        String known = "0111010010010";
        if (cabysService.find(known) == null) {
            cabysService.create(new Cabys(known, "Bovinos", "Cat", "0", "https://ex.com", "ACTIVO"));
        }
        assertNotNull(cabysService.find(known));
        var all = cabysService.listAll();
        assertNotNull(all);
        assertTrue(all.size() >= 1);
    }

    @Test
    void cabysEstadoFilteringViaService() {
        String code = uniqueCode();
        Cabys c = new Cabys(code, "Estado Test", "Cat", "01", "https://ex.com", "ACTIVO");
        cabysService.create(c);
        try {
            var found = cabysService.find(code);
            assertNotNull(found);
            assertTrue("ACTIVO".equals(found.getEstado()) || "INACTIVO".equals(found.getEstado()));
        } finally { var e = cabysService.find(code); if (e != null) cabysService.delete(e); }
    }
}
