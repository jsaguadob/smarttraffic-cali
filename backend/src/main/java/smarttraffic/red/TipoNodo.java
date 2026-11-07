package smarttraffic.red;

/** Papel de una intersección dentro de la red. */
public enum TipoNodo {
    /** Intersección interior de la ciudad. */
    INTERIOR,
    /** Punto donde los vehículos entran y salen de la zona simulada. */
    ENTRADA_SALIDA,
    /** Hospital o estación desde donde salen los vehículos de emergencia. */
    BASE_EMERGENCIAS
}
