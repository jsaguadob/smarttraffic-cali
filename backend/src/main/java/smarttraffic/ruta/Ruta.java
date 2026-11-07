package smarttraffic.ruta;

import java.util.ArrayList;
import java.util.List;
import smarttraffic.red.Via;

/** Resultado de una búsqueda de ruta: la secuencia de vías, su costo y cuánto trabajó el algoritmo. */
public final class Ruta {
    private final String origen;
    private final String destino;
    private final Via[] vias;
    private final double costo;
    private final int nodosExpandidos;

    public Ruta(String origen, String destino, Via[] vias, double costo, int nodosExpandidos) {
        this.origen = origen;
        this.destino = destino;
        this.vias = vias;
        this.costo = costo;
        this.nodosExpandidos = nodosExpandidos;
    }

    public String origen() {
        return origen;
    }

    public String destino() {
        return destino;
    }

    public Via[] vias() {
        return vias;
    }

    public double costo() {
        return costo;
    }

    /** Intersecciones expandidas por el algoritmo (sirve para comparar Dijkstra con A*). */
    public int nodosExpandidos() {
        return nodosExpandidos;
    }

    public List<String> nodos() {
        List<String> ids = new ArrayList<>();
        ids.add(origen);
        for (Via v : vias) ids.add(v.hacia());
        return ids;
    }

    public double longitudTotal() {
        double total = 0;
        for (Via v : vias) total += v.longitud();
        return total;
    }

    public boolean usa(String idVia) {
        for (Via v : vias) if (v.id().equals(idVia)) return true;
        return false;
    }
}
