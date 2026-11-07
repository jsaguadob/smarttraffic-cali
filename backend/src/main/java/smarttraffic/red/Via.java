package smarttraffic.red;

/**
 * Arista dirigida del grafo: un tramo de calle que va de una intersección a otra en un solo
 * sentido. Una calle de doble sentido se representa con dos vías.
 */
public final class Via {
    public static final double VELOCIDAD_MIN_KMH = 10;
    public static final double VELOCIDAD_MAX_KMH = 100;

    private final String id;
    private final String desde;
    private final String hacia;
    private String nombre;
    private double longitud; // metros
    private double velocidadMaxKmh;
    private boolean longitudManual;
    /** Poligonal de la vía en metros: x0, y0, x1, y1, ... (al menos dos puntos). */
    private double[] puntos;
    private String clase = "";

    public Via(String id, String desde, String hacia, String nombre, double longitud, double velocidadMaxKmh, boolean longitudManual, double[] puntos) {
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
        setPuntos(puntos);
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

    /** Tipo de calle (p. ej. «primary», «residential»); informativo. */
    public String clase() {
        return clase;
    }

    public void setClase(String clase) {
        this.clase = clase == null ? "" : clase;
    }

    public double[] puntos() {
        return puntos;
    }

    public void setPuntos(double[] puntos) {
        if (puntos == null || puntos.length < 4 || puntos.length % 2 != 0) throw new IllegalArgumentException("La geometría necesita al menos dos puntos (x, y)");
        this.puntos = puntos.clone();
    }

    /** Longitud de la poligonal en metros. */
    public double longitudGeometria() {
        double total = 0;
        for (int i = 2; i < puntos.length; i += 2) total += Math.hypot(puntos[i] - puntos[i - 2], puntos[i + 1] - puntos[i - 1]);
        return total;
    }

    /** Dirección de llegada a la intersección final (vector del último tramo). */
    public double[] direccionFinal() {
        int n = puntos.length;
        return new double[] {puntos[n - 2] - puntos[n - 4], puntos[n - 1] - puntos[n - 3]};
    }

    /** Punto de la vía a {@code distancia} metros de su inicio: {x, y, rumbo en radianes}. */
    public double[] puntoEn(double distancia) {
        double d = Math.max(0, distancia);
        for (int i = 2; i < puntos.length; i += 2) {
            double dx = puntos[i] - puntos[i - 2], dy = puntos[i + 1] - puntos[i - 1], seg = Math.hypot(dx, dy);
            if (d <= seg || i == puntos.length - 2) {
                double f = seg == 0 ? 0 : Math.min(1, d / seg);
                return new double[] {puntos[i - 2] + dx * f, puntos[i - 1] + dy * f, Math.atan2(dy, dx)};
            }
            d -= seg;
        }
        return new double[] {puntos[0], puntos[1], 0};
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
