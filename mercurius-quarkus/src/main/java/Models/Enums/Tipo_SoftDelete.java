package Models.Enums;

/**
 * Resultado de una operacion de borrado logico (soft delete).
 * El servicio reporta el efecto aplicado y la capa de controlador
 * lo traduce al mensaje de UI correspondiente.
 */
public enum Tipo_SoftDelete {
    DEACTIVATED,
    ACTIVATED
}
