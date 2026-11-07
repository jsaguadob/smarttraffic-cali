package smarttraffic.red;

/**
 * Arista dirigida del grafo: un tramo de calle que va de una intersección a otra en un solo
 * sentido. Una calle de doble sentido se representa con dos vías.
 */
public class Via {
    public static final double VELOCIDAD_MIN_KMH = 10;
    public static final double VELOCIDAD_MAX_KMH = 100;

    private final String id;
    private final String desde;
    private final String hacia;
    private String nombre;
    private double longitud; // metros
    private double velocidadMaxKmh;
    private boolean longitudManual;

    public Via(String id, String desde, String hacia, String nombre, double longitud, double velocidadMaxKmh, boolean longitudManual) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("La vía necesita un identificador");
        if (desde == null || hacia == null || desde.equals(hacia)) throw new IllegalArgumentException("La vía debe unir dos intersecciones distintas");
        if (!(longitud > 0)) throw new IllegalArgumentException("La longitud debe ser mayor que cero");
        if (velocidadMaxKmh < VELOCIDAD_MIN_KMH || velocidadMaxKmh > VELOCIDAD_MAX_KMH)
            throw new IllegalArgumentException("La velocidad máxima debe estar entre " + VELOCIDAD_MIN_KMH + " y " + VELOCIDAD_MAX_KMH + " km/h");
        this.id = id;
        this.desde = desde;
        this.hacia = hacia;
        this.nombre = nombre == null || nombre.isBlank() ? id : nombre;
        this.longitud = longitud;
        this.velocidadMaxKmh = velocidadMaxKmh;
        this.longitudManual = longitudManual;
    }

    public String id() {
        return id;
    }

    public String desde() {
        return desde;
    }

    public String hacia() {
        return hacia;
    }

    public String nombre() {
        return nombre;
    }

    public double longitud() {
        return longitud;
    }

    public double velocidadMaxKmh() {
        return velocidadMaxKmh;
    }

    /** Velocidad máxima en metros por segundo. */
    public double velocidadMaxMs() {
        return velocidadMaxKmh / 3.6;
    }

    /** Tiempo en segundos para recorrer la vía a la velocidad máxima y sin tráfico. */
    public double tiempoLibre() {
        return longitud / velocidadMaxMs();
    }

    public boolean longitudManual() {
        return longitudManual;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public void setVelocidadMaxKmh(double v) {
        if (v < VELOCIDAD_MIN_KMH || v > VELOCIDAD_MAX_KMH)
            throw new IllegalArgumentException("La velocidad máxima debe estar entre " + VELOCIDAD_MIN_KMH + " y " + VELOCIDAD_MAX_KMH + " km/h");
        this.velocidadMaxKmh = v;
    }

    public void setLongitud(double metros, boolean manual) {
        if (!(metros > 0)) throw new IllegalArgumentException("La longitud debe ser mayor que cero");
        this.longitud = metros;
        this.longitudManual = manual;
    }
}
