package smarttraffic.red;

import java.util.LinkedHashSet;
import java.util.Set;

/** Nodo del grafo: una intersección con su posición en el plano (en metros). */
public class Interseccion {
    private final String id;
    private String nombre;
    private double x;
    private double y;
    private TipoNodo tipo;
    private double latitud = Double.NaN;
    private double longitud = Double.NaN;
    private boolean semaforizada = true;
    private final Set<String> servicios = new LinkedHashSet<>();

    public Interseccion(String id, String nombre, double x, double y, TipoNodo tipo) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("La intersección necesita un identificador");
        this.id = id;
        this.nombre = nombre == null || nombre.isBlank() ? id : nombre;
        this.x = x;
        this.y = y;
        this.tipo = tipo == null ? TipoNodo.INTERIOR : tipo;
    }

    public String id() {
        return id;
    }

    public String nombre() {
        return nombre;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public TipoNodo tipo() {
        return tipo;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public void setTipo(TipoNodo tipo) {
        this.tipo = tipo;
    }

    /** Coordenadas geográficas (WGS84) de la intersección, si se importó de un mapa real. */
    public double latitud() {
        return latitud;
    }

    public double longitud() {
        return longitud;
    }

    public void setGeo(double latitud, double longitud) {
        this.latitud = latitud;
        this.longitud = longitud;
    }

    /** ¿Tiene semáforo? En el mapa real solo las intersecciones registradas como semaforizadas. */
    public boolean semaforizada() {
        return semaforizada;
    }

    public void setSemaforizada(boolean semaforizada) {
        this.semaforizada = semaforizada;
    }

    /** Servicios de emergencia que salen de aquí: «hospital», «bomberos», «policia». */
    public Set<String> servicios() {
        return servicios;
    }

    public void mover(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double distanciaA(Interseccion otra) {
        return Math.hypot(x - otra.x, y - otra.y);
    }
}
