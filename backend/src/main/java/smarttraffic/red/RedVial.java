package smarttraffic.red;

import java.util.ArrayList;
import java.util.List;
import smarttraffic.estructuras.Grafo;
import smarttraffic.estructuras.ListaEnlazada;
import smarttraffic.estructuras.TablaHash;

/**
 * Red vial: un grafo dirigido cuyos nodos son intersecciones y cuyas aristas son vías, con las
 * operaciones que necesita el administrador para crear y editar el mapa.
 */
public class RedVial {

    private final Grafo<Interseccion, Via> grafo = new Grafo<>();
    private String nombre;

    public RedVial(String nombre) {
        this.nombre = nombre;
    }

    public String nombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public Grafo<Interseccion, Via> grafo() {
        return grafo;
    }

    // ----------------------------------------------------------------- intersecciones

    public Interseccion agregarInterseccion(Interseccion i) {
        grafo.agregarVertice(i.id(), i);
        return i;
    }

    public Interseccion interseccion(String id) {
        return grafo.contieneVertice(id) ? grafo.vertice(id) : null;
    }

    /** Mueve una intersección y recalcula las longitudes de las vías cuya distancia no se fijó a mano. */
    public void moverInterseccion(String id, double x, double y) {
        Interseccion i = grafo.vertice(id);
        i.mover(x, y);
        for (Grafo.Arista<Via> a : grafo.salientes(id)) { ajustarExtremo(a.dato(), true, x, y); recalcular(a.dato()); }
        for (Grafo.Arista<Via> a : grafo.entrantes(id)) { ajustarExtremo(a.dato(), false, x, y); recalcular(a.dato()); }
    }

    /** Al mover una intersección, el extremo de la poligonal de sus vías la sigue. */
    private void ajustarExtremo(Via v, boolean inicio, double x, double y) {
        double[] p = v.puntos().clone();
        int k = inicio ? 0 : p.length - 2;
        p[k] = x;
        p[k + 1] = y;
        v.setPuntos(p);
    }

    private void recalcular(Via v) {
        if (v.longitudManual()) return;
        double d = v.longitudGeometria();
        if (d > 0) v.setLongitud(d, false);
    }

    /** Elimina la intersección y todas las vías que llegan o salen de ella. */
    public boolean eliminarInterseccion(String id) {
        return grafo.eliminarVertice(id);
    }

    // ----------------------------------------------------------------- vías

    /**
     * Agrega una vía de un solo sentido.
     *
     * @param longitud metros; si es null se calcula con la distancia entre las dos intersecciones
     */
    public Via agregarVia(String id, String desde, String hacia, String nombre, Double longitud, double velocidadKmh) {
        if (!grafo.contieneVertice(desde)) throw new IllegalArgumentException("No existe la intersección " + desde);
        if (!grafo.contieneVertice(hacia)) throw new IllegalArgumentException("No existe la intersección " + hacia);
        Interseccion a = grafo.vertice(desde), b = grafo.vertice(hacia);
        return agregarVia(id, desde, hacia, nombre, longitud, velocidadKmh, new double[] {a.x(), a.y(), b.x(), b.y()});
    }

    /** Agrega una vía con su geometría (poligonal en metros, del inicio al final). */
    public Via agregarVia(String id, String desde, String hacia, String nombre, Double longitud, double velocidadKmh, double[] puntos) {
        if (!grafo.contieneVertice(desde)) throw new IllegalArgumentException("No existe la intersección " + desde);
        if (!grafo.contieneVertice(hacia)) throw new IllegalArgumentException("No existe la intersección " + hacia);
        double largo = longitud != null ? longitud : geometriaLarga(puntos);
        Via via = new Via(id, desde, hacia, nombre, largo, velocidadKmh, longitud != null, puntos);
        grafo.agregarArista(id, desde, hacia, via);
        return via;
    }

    private static double geometriaLarga(double[] p) {
        double total = 0;
        for (int i = 2; i + 1 < p.length; i += 2) total += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
        return total;
    }

    /** Agrega una calle; si es de doble sentido crea dos vías (id + ":AB" e id + ":BA"). */
    public List<Via> agregarCalle(String idBase, String a, String b, boolean dobleSentido, String nombre, double velocidadKmh) {
        List<Via> creadas = new ArrayList<>();
        creadas.add(agregarVia(idBase + ":AB", a, b, nombre, null, velocidadKmh));
        if (dobleSentido) creadas.add(agregarVia(idBase + ":BA", b, a, nombre, null, velocidadKmh));
        return creadas;
    }

    public boolean eliminarVia(String id) {
        return grafo.eliminarArista(id);
    }

    public Via via(String id) {
        Grafo.Arista<Via> a = grafo.arista(id);
        return a == null ? null : a.dato();
    }

    // ----------------------------------------------------------------- consultas

    public List<Interseccion> intersecciones() {
        List<Interseccion> lista = new ArrayList<>();
        for (String id : grafo.idsVertices()) lista.add(grafo.vertice(id));
        return lista;
    }

    public List<Via> vias() {
        List<Via> lista = new ArrayList<>();
        for (String id : grafo.idsAristas()) lista.add(grafo.arista(id).dato());
        return lista;
    }

    public List<Interseccion> entradasSalidas() {
        return porTipo(TipoNodo.ENTRADA_SALIDA);
    }

    /** Intersecciones desde las que salen vehículos de emergencia (tienen algún servicio). */
    public List<Interseccion> bases() {
        List<Interseccion> lista = new ArrayList<>();
        for (Interseccion i : intersecciones()) if (!i.servicios().isEmpty()) lista.add(i);
        return lista;
    }

    private List<Interseccion> porTipo(TipoNodo tipo) {
        List<Interseccion> lista = new ArrayList<>();
        for (Interseccion i : intersecciones()) if (i.tipo() == tipo) lista.add(i);
        return lista;
    }

    /** Mayor velocidad permitida en la red (m/s); sirve para la heurística de A*. */
    public double velocidadMaximaMs() {
        double max = 1;
        for (Via v : vias()) max = Math.max(max, v.velocidadMaxMs());
        return max;
    }

    /**
     * Factor que garantiza que la distancia en línea recta, multiplicada por él, nunca supere la
     * longitud real de una vía. Mantiene admisible la heurística de A* aunque el administrador haya
     * fijado a mano longitudes más cortas que la distancia geométrica.
     */
    public double factorHeuristica() {
        double factor = 1;
        for (Via v : vias()) {
            double recta = grafo.vertice(v.desde()).distanciaA(grafo.vertice(v.hacia()));
            if (recta > 0) factor = Math.min(factor, v.longitud() / recta);
        }
        return factor;
    }

    /** ¿Se puede llegar de cualquier intersección a cualquier otra respetando los sentidos? */
    public boolean fuertementeConexa() {
        if (grafo.numeroVertices() <= 1) return true;
        String inicio = grafo.idsVertices().primero();
        return alcanzables(inicio, true) == grafo.numeroVertices() && alcanzables(inicio, false) == grafo.numeroVertices();
    }

    private int alcanzables(String inicio, boolean haciaAdelante) {
        TablaHash<String, Boolean> visto = new TablaHash<>();
        ListaEnlazada<String> pila = new ListaEnlazada<>();
        pila.agregarFinal(inicio);
        visto.poner(inicio, true);
        while (!pila.estaVacia()) {
            String actual = pila.quitarFinal();
            ListaEnlazada<Grafo.Arista<Via>> vecinos = haciaAdelante ? grafo.salientes(actual) : grafo.entrantes(actual);
            for (Grafo.Arista<Via> a : vecinos) {
                String otro = haciaAdelante ? a.hacia() : a.desde();
                if (!visto.contiene(otro)) {
                    visto.poner(otro, true);
                    pila.agregarFinal(otro);
                }
            }
        }
        return visto.tamano();
    }

    /** Problemas que impiden simular con esta red (lista vacía si está lista). */
    public List<String> validarParaSimulacion() {
        List<String> problemas = new ArrayList<>();
        if (grafo.numeroVertices() < 2) problemas.add("La red necesita al menos dos intersecciones.");
        if (entradasSalidas().size() < 2) problemas.add("La red necesita al menos dos puntos de entrada y salida.");
        for (Interseccion i : entradasSalidas()) {
            if (grafo.salientes(i.id()).estaVacia()) problemas.add("Desde la entrada " + i.nombre() + " no sale ninguna vía.");
            if (grafo.entrantes(i.id()).estaVacia()) problemas.add("A la salida " + i.nombre() + " no llega ninguna vía.");
        }
        if (grafo.numeroVertices() > 1 && !fuertementeConexa()) problemas.add("Hay intersecciones a las que no se puede llegar (o desde las que no se puede salir) respetando los sentidos de circulación.");
        return problemas;
    }
}
