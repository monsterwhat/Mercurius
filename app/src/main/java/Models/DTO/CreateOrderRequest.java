package Models.DTO;

import jakarta.annotation.Nullable;

public class CreateOrderRequest {
    @Nullable private String notes;

    public CreateOrderRequest() {}

    @Nullable
    public String getNotes() { return notes; }
    public void setNotes(@Nullable String notes) { this.notes = notes; }
}
