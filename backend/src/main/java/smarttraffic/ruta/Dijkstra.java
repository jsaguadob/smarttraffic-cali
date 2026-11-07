package smarttraffic.ruta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import smarttraffic.estructuras.ColaPrioridad;
import smarttraffic.estructuras.Grafo;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.RedVial;
import smarttraffic.red.Via;

/**
 * Algoritmo de Dijkstra: ruta de menor costo entre dos intersecciones.
 *
 * <p>Usa una cola de prioridad (montículo) con eliminación perezosa y tablas hash para las
 * distancias y las vías previas. Complejidad O((V + E) log V). Requiere costos no negativos.
 */
public final class Dijkstra {
    private Dijkstra() {}

    private record Item(String nodo, double costo, int orden) {}

    /** Devuelve la ruta de menor costo o null si el destino no es alcanzable. */
    public static Ruta buscar(RedVial red, String origen, String destino, FuncionCosto funcion) {
        Grafo<?, Via> g = red.grafo();
        if (!g.contieneVertice(origen)) throw new IllegalArgumentException("No existe la intersección de origen " + origen);
        if (!g.contieneVertice(destino)) throw new IllegalArgumentException("No existe la intersección de destino " + destino);
        if (origen.equals(destino)) return new Ruta(origen, destino, new Via[0], 0, 0);

        TablaHash<String, Double> distancia = new TablaHash<>();
        TablaHash<String, Via> previa = new TablaHash<>();
        TablaHash<String, Boolean> cerrado = new TablaHash<>();
        ColaPrioridad<Item> abiertos = new ColaPrioridad<>(Comparator.comparingDouble(Item::costo).thenComparingInt(Item::orden));
        int orden = 0, expandidos = 0;
        distancia.poner(origen, 0.0);
        abiertos.insertar(new Item(origen, 0, orden++));

        while (!abiertos.estaVacia()) {
            Item actual = abiertos.extraerMinimo();
            if (cerrado.contiene(actual.nodo())) continue; // entrada vieja de la cola
            cerrado.poner(actual.nodo(), true);
            expandidos++;
            if (actual.nodo().equals(destino)) return reconstruir(origen, destino, previa, actual.costo(), expandidos);
            for (Grafo.Arista<Via> a : g.salientes(actual.nodo())) {
                double c = funcion.costo(a.dato());
                if (c < 0) throw new IllegalArgumentException("Dijkstra no admite costos negativos (vía " + a.id() + ")");
                if (Double.isInfinite(c) || cerrado.contiene(a.hacia())) continue;
                double nuevo = actual.costo() + c;
                Double conocido = distancia.obtener(a.hacia());
                if (conocido == null || nuevo < conocido) {
                    distancia.poner(a.hacia(), nuevo);
                    previa.poner(a.hacia(), a.dato());
                    abiertos.insertar(new Item(a.hacia(), nuevo, orden++));
                }
            }
        }
        return null;
    }

    static Ruta reconstruir(String origen, String destino, TablaHash<String, Via> previa, double costo, int expandidos) {
        List<Via> camino = new ArrayList<>();
        String nodo = destino;
        while (!nodo.equals(origen)) {
            Via v = previa.obtener(nodo);
            camino.add(v);
            nodo = v.desde();
        }
        Collections.reverse(camino);
        return new Ruta(origen, destino, camino.toArray(new Via[0]), costo, expandidos);
    }
}
