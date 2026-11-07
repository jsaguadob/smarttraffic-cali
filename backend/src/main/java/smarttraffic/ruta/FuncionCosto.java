package smarttraffic.ruta;

import smarttraffic.red.Via;

/**
 * Costo (en segundos) de recorrer una vía. Devuelve {@link Double#POSITIVE_INFINITY} si la vía no
 * se puede usar (cerrada por un accidente, por ejemplo).
 */
@FunctionalInterface
public interface FuncionCosto {
    double costo(Via via);

    /** Costo sin tráfico: longitud dividida entre la velocidad máxima. */
    FuncionCosto TIEMPO_LIBRE = Via::tiempoLibre;
}
