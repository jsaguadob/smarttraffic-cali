package smarttraffic.red;

/** Nodo del grafo: una intersección con su posición en el plano (en metros). */
public class Interseccion {
    private final String id;
    private String nombre;
    private double x;
    private double y;
    private TipoNodo tipo;

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

    public void mover(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public double distanciaA(Interseccion otra) {
        return Math.hypot(x - otra.x, y - otra.y);
    }
}
