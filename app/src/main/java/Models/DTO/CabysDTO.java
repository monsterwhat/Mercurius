package Models.DTO;

import jakarta.annotation.Nullable;

public class CabysDTO {
    private String codigo;
    @Nullable private String descripcion;
    @Nullable private String categorias;
    private String impuesto;
    private String uri;
    private String estado;

    public CabysDTO() {}

    public CabysDTO(String codigo, String descripcion, String categorias, String impuesto, String uri, String estado) {
        this.codigo = codigo;
        this.descripcion = descripcion;
        this.categorias = categorias;
        this.impuesto = impuesto;
        this.uri = uri;
        this.estado = estado;
    }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    @Nullable
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(@Nullable String descripcion) { this.descripcion = descripcion; }

    @Nullable
    public String getCategorias() { return categorias; }
    public void setCategorias(@Nullable String categorias) { this.categorias = categorias; }

    public String getImpuesto() { return impuesto; }
    public void setImpuesto(String impuesto) { this.impuesto = impuesto; }

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }
}
