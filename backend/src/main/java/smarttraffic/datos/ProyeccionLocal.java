package smarttraffic.datos;

/**
 * Proyección equirrectangular local: convierte latitud y longitud (WGS84) a metros alrededor de un
 * punto de origen. En una zona de pocos kilómetros el error es de centímetros, suficiente para
 * dibujar y simular. El eje y crece hacia el sur (como en la pantalla).
 */
public final class ProyeccionLocal {
    private static final double RADIO_TIERRA = 6371008.8;
    private final double lat0;
    private final double lon0;
    private final double cosLat0;

    public ProyeccionLocal(double lat0, double lon0) {
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.cosLat0 = Math.cos(Math.toRadians(lat0));
    }

    public double lat0() {
        return lat0;
    }

    public double lon0() {
        return lon0;
    }

    public double x(double lon) {
        return Math.toRadians(lon - lon0) * cosLat0 * RADIO_TIERRA;
    }

    public double y(double lat) {
        return -Math.toRadians(lat - lat0) * RADIO_TIERRA;
    }

    public double lon(double x) {
        return lon0 + Math.toDegrees(x / (RADIO_TIERRA * cosLat0));
    }

    public double lat(double y) {
        return lat0 - Math.toDegrees(y / RADIO_TIERRA);
    }
}
