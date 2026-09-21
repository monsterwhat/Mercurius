package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Registration request payload for Mercatus client self-registration.
 */
public class RegisterRequest {

    @Nonnull
    private String name;

    @Nonnull
    private String email;

    @Nonnull
    private String password;

    @Nullable
    private String idType; // Tipo de identificacion (e.g., "Cédula Física", "Cédula Jurídica")

    @Nullable
    private String idNumber; // Número de identificación

    @Nullable
    private String phoneNumber;

    @Nullable
    private String address;

    public RegisterRequest() {
    }

    @Nonnull
    public String getName() {
        return name;
    }

    public void setName(@Nonnull String name) {
        this.name = name;
    }

    @Nonnull
    public String getEmail() {
        return email;
    }

    public void setEmail(@Nonnull String email) {
        this.email = email;
    }

    @Nonnull
    public String getPassword() {
        return password;
    }

    public void setPassword(@Nonnull String password) {
        this.password = password;
    }

    @Nullable
    public String getIdType() {
        return idType;
    }

    public void setIdType(@Nullable String idType) {
        this.idType = idType;
    }

    @Nullable
    public String getIdNumber() {
        return idNumber;
    }

    public void setIdNumber(@Nullable String idNumber) {
        this.idNumber = idNumber;
    }

    @Nullable
    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(@Nullable String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    @Nullable
    public String getAddress() {
        return address;
    }

    public void setAddress(@Nullable String address) {
        this.address = address;
    }
}
