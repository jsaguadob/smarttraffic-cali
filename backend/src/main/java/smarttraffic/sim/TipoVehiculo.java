package smarttraffic.sim;

/** Tipos de vehículo con sus características de movimiento (largo en m, aceleración y frenado en m/s²). */
public enum TipoVehiculo {
    AUTO("Auto", 4.5, 1.00, 2.6, 4.5, 0),
    MOTO("Motocicleta", 2.2, 1.10, 3.4, 5.5, 0),
    BUS("Bus", 11.0, 0.85, 1.4, 3.5, 0),
    AMBULANCIA("Ambulancia", 5.5, 1.30, 3.0, 6.0, 1),
    BOMBEROS("Bomberos", 9.0, 1.15, 2.0, 5.0, 2),
    POLICIA("Policía", 4.8, 1.25, 3.0, 6.0, 3);

    private final String etiqueta;
    private final double largo;
    private final double factorVelocidad;
    private final double aceleracion;
    private final double frenado;
    private final int prioridad;

    TipoVehiculo(String etiqueta, double largo, double factorVelocidad, double aceleracion, double frenado, int prioridad) {
        this.etiqueta = etiqueta;
        this.largo = largo;
        this.factorVelocidad = factorVelocidad;
        this.aceleracion = aceleracion;
        this.frenado = frenado;
        this.prioridad = prioridad;
    }

    public String etiqueta() {
        return etiqueta;
    }

    public double largo() {
        return largo;
    }

    public double factorVelocidad() {
        return factorVelocidad;
    }

    public double aceleracion() {
        return aceleracion;
    }

    public double frenado() {
        return frenado;
    }

    /** 0 para vehículos particulares; 1 (ambulancia), 2 (bomberos), 3 (policía) para emergencias. */
    public int prioridad() {
        return prioridad;
    }

    public boolean esEmergencia() {
        return prioridad > 0;
    }
}
