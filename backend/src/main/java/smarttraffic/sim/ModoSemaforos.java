package smarttraffic.sim;

/** Estrategia de control de los semáforos. */
public enum ModoSemaforos {
    /** Ciclo de tiempos fijos, igual para todos los semáforos. */
    FIJO,
    /** Tiempos de verde ajustados según los vehículos que esperan y prioridad a emergencias. */
    INTELIGENTE
}
