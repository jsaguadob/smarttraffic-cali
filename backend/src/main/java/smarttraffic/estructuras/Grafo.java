package smarttraffic.estructuras;

import java.util.NoSuchElementException;

/**
 * Grafo dirigido representado con listas de adyacencia.
 *
 * <p>Uso en SmartTraffic: cada nodo es una intersección y cada arista dirigida es una vía. El
 * «peso» de la arista no se guarda aquí: lo calcula una función de costo en el momento de buscar
 * la ruta, porque cambia con la congestión y los incidentes.
 *
 * <p>Complejidad: agregar vértice/arista O(1) promedio; recorrer los vecinos de un vértice O(grado);
 * eliminar un vértice O(grado + n) por mantener el orden de inserción.
 *
 * @param <V> dato asociado a cada vértice (la intersección)
 * @param <A> dato asociado a cada arista (la vía)
 */
public class Grafo<V, A> {

    public static final class Arista<A> {
        private final String id;
        private final String desde;
        private final String hacia;
        private final A dato;

        Arista(String id, String desde, String hacia, A dato) {
            this.id = id;
            this.desde = desde;
            this.hacia = hacia;
            this.dato = dato;
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

        public A dato() {
            return dato;
        }
    }

    private final TablaHash<String, V> vertices = new TablaHash<>();
    private final TablaHash<String, Arista<A>> aristas = new TablaHash<>();
    private final TablaHash<String, ListaEnlazada<Arista<A>>> salientes = new TablaHash<>();
    private final TablaHash<String, ListaEnlazada<Arista<A>>> entrantes = new TablaHash<>();
    private final ListaEnlazada<String> ordenVertices = new ListaEnlazada<>();
    private final ListaEnlazada<String> ordenAristas = new ListaEnlazada<>();

    public int numeroVertices() {
        return vertices.tamano();
    }

    public int numeroAristas() {
        return aristas.tamano();
    }

    public void agregarVertice(String id, V dato) {
        if (vertices.contiene(id)) throw new IllegalArgumentException("Ya existe el vértice " + id);
        vertices.poner(id, dato);
        salientes.poner(id, new ListaEnlazada<>());
        entrantes.poner(id, new ListaEnlazada<>());
        ordenVertices.agregarFinal(id);
    }

    public boolean contieneVertice(String id) {
        return vertices.contiene(id);
    }

    public V vertice(String id) {
        if (!vertices.contiene(id)) throw new NoSuchElementException("No existe el vértice " + id);
        return vertices.obtener(id);
    }

    /** Agrega la arista dirigida desde → hacia. Se permiten aristas paralelas con distinto id. */
    public Arista<A> agregarArista(String id, String desde, String hacia, A dato) {
        if (aristas.contiene(id)) throw new IllegalArgumentException("Ya existe la arista " + id);
        if (!vertices.contiene(desde)) throw new NoSuchElementException("No existe el vértice " + desde);
        if (!vertices.contiene(hacia)) throw new NoSuchElementException("No existe el vértice " + hacia);
        Arista<A> arista = new Arista<>(id, desde, hacia, dato);
        aristas.poner(id, arista);
        salientes.obtener(desde).agregarFinal(arista);
        entrantes.obtener(hacia).agregarFinal(arista);
        ordenAristas.agregarFinal(id);
        return arista;
    }

    public Arista<A> arista(String id) {
        return aristas.obtener(id);
    }

    /** Aristas que salen del vértice (las vías que se pueden tomar desde esa intersección). */
    public ListaEnlazada<Arista<A>> salientes(String vertice) {
        ListaEnlazada<Arista<A>> l = salientes.obtener(vertice);
        if (l == null) throw new NoSuchElementException("No existe el vértice " + vertice);
        return l;
    }

    /** Aristas que llegan al vértice (las vías que desembocan en esa intersección). */
    public ListaEnlazada<Arista<A>> entrantes(String vertice) {
        ListaEnlazada<Arista<A>> l = entrantes.obtener(vertice);
        if (l == null) throw new NoSuchElementException("No existe el vértice " + vertice);
        return l;
    }

    public boolean eliminarArista(String id) {
        Arista<A> a = aristas.eliminar(id);
        if (a == null) return false;
        salientes.obtener(a.desde).eliminar(a);
        entrantes.obtener(a.hacia).eliminar(a);
        ordenAristas.eliminar(id);
        return true;
    }

    /** Elimina el vértice y todas las aristas que entran o salen de él. */
    public boolean eliminarVertice(String id) {
        if (!vertices.contiene(id)) return false;
        for (Arista<A> a : salientes.obtener(id).aLista()) eliminarArista(a.id);
        for (Arista<A> a : entrantes.obtener(id).aLista()) eliminarArista(a.id);
        vertices.eliminar(id);
        salientes.eliminar(id);
        entrantes.eliminar(id);
        ordenVertices.eliminar(id);
        return true;
    }

    /** Identificadores de vértices en el orden en que se agregaron. */
    public ListaEnlazada<String> idsVertices() {
        return ordenVertices;
    }

    /** Identificadores de aristas en el orden en que se agregaron. */
    public ListaEnlazada<String> idsAristas() {
        return ordenAristas;
    }
}
