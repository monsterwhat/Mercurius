package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

public class ProfileDTO {
    private int code;
    private String name;
    @Nullable private String email;
    @Nullable private String phoneNumber;
    @Nullable private String address;
    @Nullable private String idType;
    @Nullable private String idNumber;
    @Nullable private Date birthDate;
    @Nullable private BigDecimal puntosAcumulados;
    @Nullable private String statusPuntos;

    public ProfileDTO() {}

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    @Nullable
    public String getEmail() { return email; }
    public void setEmail(@Nullable String email) { this.email = email; }

    @Nullable
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(@Nullable String phoneNumber) { this.phoneNumber = phoneNumber; }

    @Nullable
    public String getAddress() { return address; }
    public void setAddress(@Nullable String address) { this.address = address; }

    @Nullable
    public String getIdType() { return idType; }
    public void setIdType(@Nullable String idType) { this.idType = idType; }

    @Nullable
    public String getIdNumber() { return idNumber; }
    public void setIdNumber(@Nullable String idNumber) { this.idNumber = idNumber; }

    @Nullable
    public Date getBirthDate() { return birthDate; }
    public void setBirthDate(@Nullable Date birthDate) { this.birthDate = birthDate; }

    @Nullable
    public BigDecimal getPuntosAcumulados() { return puntosAcumulados; }
    public void setPuntosAcumulados(@Nullable BigDecimal puntosAcumulados) { this.puntosAcumulados = puntosAcumulados; }

    @Nullable
    public String getStatusPuntos() { return statusPuntos; }
    public void setStatusPuntos(@Nullable String statusPuntos) { this.statusPuntos = statusPuntos; }
}
