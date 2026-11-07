package smarttraffic.sim;

/** Entrada del historial de eventos de la simulación. */
public record Evento(double t, String tipo, String mensaje) {}
