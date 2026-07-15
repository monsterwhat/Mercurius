package Models.DTO;

import java.util.List;

import jakarta.annotation.Nonnull;

/**
 * Standard paginated response envelope for list endpoints.
 * Format: { data: [...], total: N, page: N, size: N }
 *
 * @param <T> the element type
 */
public class PagedResponse<T> {

    @Nonnull
    private List<T> data;

    private long total;

    private int page;

    private int size;

    public PagedResponse() {
    }

    public PagedResponse(@Nonnull List<T> data, long total, int page, int size) {
        this.data = data;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    @Nonnull
    public List<T> getData() {
        return data;
    }

    public void setData(@Nonnull List<T> data) {
        this.data = data;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }
}
