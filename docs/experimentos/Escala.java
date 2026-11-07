import java.nio.file.*;
import java.util.*;
import smarttraffic.datos.RedVialJson;
import smarttraffic.red.*;
import smarttraffic.sim.*;
import smarttraffic.sim.PlanificadorRutas.Punto;
public class Escala {
    public static void main(String[] a) throws Exception {
        String json = Files.readString(Path.of(a.length > 0 ? a[0] : "/tmp/red-grande.json"));
        RedVial red = RedVialJson.leer(json).red();
        System.out.println("red: " + red.intersecciones().size() + " intersecciones, " + red.vias().size() + " vías");
        Simulacion sim = new Simulacion(red, Config.inteligente(7, 200));
        sim.avanzar(240);
        System.out.println("vehículos activos tras 240 s simulados: " + sim.vehiculosActivos());
        // tiempo de un paso (0,1 s simulados) con el tráfico cargado
        for (int i = 0; i < 200; i++) sim.paso();
        long t0 = System.nanoTime(); int n = 1000; for (int i = 0; i < n; i++) sim.paso(); double msPaso = (System.nanoTime() - t0) / 1e6 / n;
        System.out.printf(Locale.ROOT, "un paso de simulación: %.3f ms (a 10 pasos por segundo real usa %.1f %% de un núcleo)%n", msPaso, msPaso * 10 / 10);
        System.out.printf(Locale.ROOT, "velocidad máxima sostenible: %.0f pasos por segundo real = %.0fx%n", 1000 / msPaso, 1000 / msPaso / 10);
        // planificador de rutas
        Random r = new Random(5); List<Interseccion> nodos = new ArrayList<>(red.intersecciones());
        for (int i = 0; i < 20; i++) { Interseccion x = nodos.get(r.nextInt(nodos.size())), y = nodos.get(r.nextInt(nodos.size())); if (x != y) try { PlanificadorRutas.planificar(sim, new Punto(x.x(), x.y()), new Punto(y.x(), y.y()), 2); } catch (Exception e) {} }
        double[] ms = new double[100]; int k = 0; double suma = 0, max = 0;
        for (int i = 0; i < 100; i++) { Interseccion x = nodos.get(r.nextInt(nodos.size())), y = nodos.get(r.nextInt(nodos.size())); if (x == y) continue; long s = System.nanoTime(); try { PlanificadorRutas.planificar(sim, new Punto(x.x(), x.y()), new Punto(y.x(), y.y()), 2); } catch (Exception e) { continue; } double d = (System.nanoTime() - s) / 1e6; suma += d; max = Math.max(max, d); k++; }
        System.out.printf(Locale.ROOT, "planificar ruta con 2 alternativas entre dos puntos al azar (%d casos): media %.1f ms, máximo %.1f ms%n", k, suma / k, max);
        // A* contra Dijkstra: nodos expandidos
        long ea = 0, ed = 0; int c = 0;
        for (int i = 0; i < 50; i++) { Interseccion x = nodos.get(r.nextInt(nodos.size())), y = nodos.get(r.nextInt(nodos.size())); if (x == y) continue; var ra = smarttraffic.ruta.AEstrella.buscar(red, x.id(), y.id(), v -> v.longitud() / v.velocidadMaxMs()); var rd = smarttraffic.ruta.Dijkstra.buscar(red, x.id(), y.id(), v -> v.longitud() / v.velocidadMaxMs()); if (ra == null || rd == null) continue; ea += ra.nodosExpandidos(); ed += rd.nodosExpandidos(); c++; if (Math.abs(ra.costo() - rd.costo()) > 1e-6) System.out.println("¡COSTOS DISTINTOS!"); }
        System.out.printf(Locale.ROOT, "nodos expandidos en %d consultas: Dijkstra %d, A* %d (A* explora %.0f %% de lo de Dijkstra, mismo costo)%n", c, ed, ea, 100.0 * ea / ed);
    }
}
