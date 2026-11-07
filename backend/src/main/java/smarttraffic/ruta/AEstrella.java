package smarttraffic.ruta;

import java.util.Comparator;
import smarttraffic.estructuras.ColaPrioridad;
import smarttraffic.estructuras.Grafo;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;
import smarttraffic.red.Via;

/**
 * Algoritmo A*: igual que Dijkstra pero guiado por una heurística que estima el tiempo restante
 * (distancia en línea recta entre la intersección y el destino, a la velocidad máxima de la red).
 *
 * <p>Con una heurística admisible encuentra la misma ruta óptima que Dijkstra expandiendo, por lo
 * general, muchas menos intersecciones. La función de costo debe ser siempre mayor o igual que el
 * tiempo libre de la vía.
 */
public final class AEstrella {
    private AEstrella() {}

    private record Item(String nodo, double g, double f, int orden) {}

    public static Ruta buscar(RedVial red, String origen, String destino, FuncionCosto funcion) {
        Grafo<Interseccion, Via> g = red.grafo();
        if (!g.contieneVertice(origen)) throw new IllegalArgumentException("No existe la intersección de origen " + origen);
        if (!g.contieneVertice(destino)) throw new IllegalArgumentException("No existe la intersección de destino " + destino);
        if (origen.equals(destino)) return new Ruta(origen, destino, new Via[0], 0, 0);

        Interseccion meta = g.vertice(destino);
        double escala = red.factorHeuristica() / red.velocidadMaximaMs();
        TablaHash<String, Double> mejor = new TablaHash<>();
        TablaHash<String, Via> previa = new TablaHash<>();
        TablaHash<String, Boolean> cerrado = new TablaHash<>();
        ColaPrioridad<Item> abiertos = new ColaPrioridad<>(Comparator.comparingDouble(Item::f).thenComparingInt(Item::orden));
        int orden = 0, expandidos = 0;
        mejor.poner(origen, 0.0);
        abiertos.insertar(new Item(origen, 0, g.vertice(origen).distanciaA(meta) * escala, orden++));

        while (!abiertos.estaVacia()) {
            Item actual = abiertos.extraerMinimo();
            if (cerrado.contiene(actual.nodo())) continue;
            cerrado.poner(actual.nodo(), true);
            expandidos++;
            if (actual.nodo().equals(destino)) return Dijkstra.reconstruir(origen, destino, previa, actual.g(), expandidos);
            for (Grafo.Arista<Via> a : g.salientes(actual.nodo())) {
                double c = funcion.costo(a.dato());
                if (c < 0) throw new IllegalArgumentException("A* no admite costos negativos (vía " + a.id() + ")");
                if (Double.isInfinite(c) || cerrado.contiene(a.hacia())) continue;
                double nuevo = actual.g() + c;
                Double conocido = mejor.obtener(a.hacia());
                if (conocido == null || nuevo < conocido) {
                    mejor.poner(a.hacia(), nuevo);
                    previa.poner(a.hacia(), a.dato());
                    abiertos.insertar(new Item(a.hacia(), nuevo, nuevo + g.vertice(a.hacia()).distanciaA(meta) * escala, orden++));
                }
            }
        }
        return null;
    }
}
