package smarttraffic.datos;

import smarttraffic.estructuras.ListaEnlazada;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.Interseccion;

/**
 * Índice espacial de rejilla: divide el plano en celdas cuadradas y guarda en una tabla hash las
 * intersecciones de cada celda. Buscar la más cercana a un punto solo revisa las celdas vecinas,
 * en lugar de recorrer todas (O(1) promedio en vez de O(n)).
 */
public final class IndiceEspacial {
    private final double celda;
    private final TablaHash<Long, ListaEnlazada<Interseccion>> celdas = new TablaHash<>();

    public IndiceEspacial(double tamanoCelda) {
        this.celda = tamanoCelda;
    }

    private static long clave(long cx, long cy) {
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    public void agregar(Interseccion i) {
        long k = clave((long) Math.floor(i.x() / celda), (long) Math.floor(i.y() / celda));
        ListaEnlazada<Interseccion> l = celdas.obtener(k);
        if (l == null) {
            l = new ListaEnlazada<>();
            celdas.poner(k, l);
        }
        l.agregarFinal(i);
    }

    /** Intersección más cercana a (x, y) dentro del radio (metros), o null si no hay ninguna. */
    public Interseccion masCercana(double x, double y, double radio) {
        long cx = (long) Math.floor(x / celda), cy = (long) Math.floor(y / celda);
        int alcance = (int) Math.ceil(radio / celda);
        Interseccion mejor = null;
        double mejorD = radio;
        for (long i = cx - alcance; i <= cx + alcance; i++) {
            for (long j = cy - alcance; j <= cy + alcance; j++) {
                ListaEnlazada<Interseccion> l = celdas.obtener(clave(i, j));
                if (l == null) continue;
                for (Interseccion n : l) {
                    double d = Math.hypot(n.x() - x, n.y() - y);
                    if (d <= mejorD) { mejorD = d; mejor = n; }
                }
            }
        }
        return mejor;
    }
}
