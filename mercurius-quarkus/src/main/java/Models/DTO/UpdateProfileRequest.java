package Models.DTO;

import jakarta.annotation.Nullable;

public class UpdateProfileRequest {
    @Nullable private String name;
    @Nullable private String email;
    @Nullable private String phoneNumber;
    @Nullable private String address;
    @Nullable private String idType;
    @Nullable private String idNumber;

    public UpdateProfileRequest() {}

    @Nullable
    public String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; }

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
}
