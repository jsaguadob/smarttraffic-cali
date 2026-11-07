package smarttraffic.sim;

import java.util.ArrayList;
import java.util.List;
import smarttraffic.red.RedVial;

/**
 * Ejecuta dos simulaciones con la misma red, la misma semilla de demanda y los mismos eventos:
 * una «tradicional» (semáforos de tiempo fijo, rutas sin reaccionar) y otra «SmartTraffic»
 * (semáforos inteligentes, prioridad a emergencias y reruteo dinámico). Los resultados son
 * estimaciones del modelo, no mediciones del tráfico real.
 */
public final class ComparadorEscenarios {
    private ComparadorEscenarios() {}

    /** Un evento programado: {@code "incidente"} (vía + gravedad) o {@code "emergencia"} (tipo + nodo destino). */
    public record Accion(double t, String tipo, String viaId, int gravedad, String nodo) {}

    public record Resultado(String nombre, double duracion, int generados, int completados, double viajeMedio, double viajeMedioAjustado, double esperaMedia,
                            double velocidadMedia, double viasCongestionadasMedia, double respuestaMedia, int emergenciasAtendidas, int reruteos,
                            List<Estadisticas.Muestra> serie) {}

    public static Resultado[] comparar(RedVial red, long semilla, double vehiculosPorMinuto, double duracion, List<Accion> acciones) {
        return new Resultado[] {
            ejecutar("Tráfico tradicional", red, Config.tradicional(semilla, vehiculosPorMinuto), duracion, acciones),
            ejecutar("SmartTraffic", red, Config.inteligente(semilla, vehiculosPorMinuto), duracion, acciones)
        };
    }

    public static Resultado ejecutar(String nombre, RedVial red, Config cfg, double duracion, List<Accion> acciones) {
        Simulacion sim = new Simulacion(red, cfg);
        List<Accion> pendientes = new ArrayList<>(acciones);
        pendientes.sort((a, b) -> Double.compare(a.t(), b.t()));
        int siguiente = 0;
        while (sim.tiempo() < duracion) {
            while (siguiente < pendientes.size() && pendientes.get(siguiente).t() <= sim.tiempo()) aplicar(sim, pendientes.get(siguiente++));
            sim.paso();
        }
        Estadisticas e = sim.estadisticas();
        double vel = 0, cong = 0;
        int n = 0;
        for (Estadisticas.Muestra m : e.serie()) { vel += m.velocidadMediaKmh(); cong += m.viasCongestionadas(); n++; }
        return new Resultado(nombre, duracion, e.generados(), e.completados(), e.tiempoViajeMedio(), sim.viajeMedioAjustado(), e.esperaMedia(),
                n == 0 ? 0 : vel / n, n == 0 ? 0 : cong / n, e.respuestaMedia(), e.emergenciasAtendidas(), e.reruteos(), e.serie().aLista());
    }

    private static void aplicar(Simulacion sim, Accion a) {
        switch (a.tipo()) {
            case "incidente" -> sim.crearIncidente(a.viaId(), 0.5, a.gravedad());
            case "emergencia" -> sim.despacharEmergencia(TipoVehiculo.AMBULANCIA, a.nodo());
            default -> throw new IllegalArgumentException("Acción desconocida: " + a.tipo());
        }
    }
}
