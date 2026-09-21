package Models.DTO;

import jakarta.annotation.Nullable;
import java.util.Date;
import java.util.List;

/**
 * Data transfer object mirroring the {@code Models.Correos.ReporteProgramado} entity
 * for the Correos/Reportes administration pages.
 *
 * Carries the scheduling fields ({@code frecuencia}, {@code lastRun},
 * {@code nextRunTime}), the enabled flag ({@code status}) and the target
 * recipients flattened from the entity's {@code List<String>} to a plain
 * {@code String[]} for simple transport.
 */
public class ReporteProgramadoDTO {

    private Long id;
    private String perfil;
    @Nullable
    private List<String> frecuencia; // Diario, Semanal, Quincenal, Mensual
    @Nullable
    private List<String> reportes; // Tipos de Reportes...
    @Nullable
    private String[] correos; // Lista de Recipientes (flattened)
    @Nullable
    private Date lastRun;
    private boolean status; // Habilitado / Deshabilitado
    @Nullable
    private Date nextRunTime;

    public ReporteProgramadoDTO() {
    }

    public ReporteProgramadoDTO(Long id, String perfil,
                                @Nullable List<String> frecuencia, @Nullable List<String> reportes,
                                @Nullable String[] correos, @Nullable Date lastRun,
                                boolean status, @Nullable Date nextRunTime) {
        this.id = id;
        this.perfil = perfil;
        this.frecuencia = frecuencia;
        this.reportes = reportes;
        this.correos = correos;
        this.lastRun = lastRun;
        this.status = status;
        this.nextRunTime = nextRunTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPerfil() {
        return perfil;
    }

    public void setPerfil(String perfil) {
        this.perfil = perfil;
    }

    @Nullable
    public List<String> getFrecuencia() {
        return frecuencia;
    }

    public void setFrecuencia(@Nullable List<String> frecuencia) {
        this.frecuencia = frecuencia;
    }

    @Nullable
    public List<String> getReportes() {
        return reportes;
    }

    public void setReportes(@Nullable List<String> reportes) {
        this.reportes = reportes;
    }

    @Nullable
    public String[] getCorreos() {
        return correos;
    }

    public void setCorreos(@Nullable String[] correos) {
        this.correos = correos;
    }

    @Nullable
    public Date getLastRun() {
        return lastRun;
    }

    public void setLastRun(@Nullable Date lastRun) {
        this.lastRun = lastRun;
    }

    public boolean isStatus() {
        return status;
    }

    public void setStatus(boolean status) {
        this.status = status;
    }

    @Nullable
    public Date getNextRunTime() {
        return nextRunTime;
    }

    public void setNextRunTime(@Nullable Date nextRunTime) {
        this.nextRunTime = nextRunTime;
    }
}
