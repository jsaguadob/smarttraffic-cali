package smarttraffic.datos;

import java.util.ArrayList;
import java.util.List;
import smarttraffic.estructuras.ArbolAvl;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;

/**
 * Puntos críticos de accidentalidad: asigna cada accidente registrado a la intersección más
 * cercana (con un índice espacial) y ordena las intersecciones por puntaje con un árbol AVL.
 * Puntaje = 1 por accidente con solo daños, 3 con heridos y 10 con muertos.
 */
public final class PuntosCriticos {
    private PuntosCriticos() {}

    public record Critico(String interseccionId, String nombre, double lat, double lon, int accidentes, int puntaje) {}

    /** Resultado: el ranking, los puntos para el mapa de calor ({x, y, peso}) y cuántos accidentes cayeron dentro de la red. */
    public record Resultado(List<Critico> ranking, List<double[]> calor, int asignados, int fueraDeRed) {}

    private record Clave(int puntaje, String id) implements Comparable<Clave> {
        @Override
        public int compareTo(Clave o) {
            int c = Integer.compare(puntaje, o.puntaje);
            return c != 0 ? c : o.id.compareTo(id);
        }
    }

    static int peso(int gravedad) {
        return gravedad >= 3 ? 10 : gravedad == 2 ? 3 : 1;
    }

    public static Resultado calcular(RedVial red, ProyeccionLocal proy, List<DatosAbiertos.Accidente> accidentes, double radio, int cuantos) {
        IndiceEspacial idx = new IndiceEspacial(100);
        for (Interseccion i : red.intersecciones()) idx.agregar(i);
        TablaHash<String, int[]> acumulado = new TablaHash<>(); // {accidentes, puntaje}
        List<double[]> calor = new ArrayList<>();
        int asignados = 0, fuera = 0;
        for (DatosAbiertos.Accidente a : accidentes) {
            double x = proy.x(a.lon()), y = proy.y(a.lat());
            Interseccion i = idx.masCercana(x, y, radio);
            if (i == null) { fuera++; continue; }
            asignados++;
            int[] acc = acumulado.obtener(i.id());
            if (acc == null) {
                acc = new int[2];
                acumulado.poner(i.id(), acc);
            }
            acc[0]++;
            acc[1] += peso(a.gravedad());
            if (calor.size() < 20000) calor.add(new double[] {x, y, peso(a.gravedad())});
        }
        ArbolAvl<Clave, Critico> arbol = new ArbolAvl<>();
        for (var e : acumulado) {
            Interseccion i = red.interseccion(e.clave());
            arbol.insertar(new Clave(e.valor()[1], i.id()), new Critico(i.id(), i.nombre(), i.latitud(), i.longitud(), e.valor()[0], e.valor()[1]));
        }
        return new Resultado(arbol.mayores(cuantos), calor, asignados, fuera);
    }
}
