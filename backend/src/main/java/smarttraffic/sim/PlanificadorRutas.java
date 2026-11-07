package smarttraffic.sim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import smarttraffic.estructuras.Grafo;
import smarttraffic.red.Interseccion;
import smarttraffic.red.Via;
import smarttraffic.ruta.AEstrella;
import smarttraffic.ruta.FuncionCosto;
import smarttraffic.ruta.Ruta;

/**
 * Navegación: calcula cómo ir de un punto a otro por las calles de la red, con el tráfico actual de la
 * simulación (calles lentas, semáforos y cierres por accidente), alternativas e instrucciones giro a giro.
 *
 * <p>Cada punto se «pega» a la calle más cercana (en cualquier posición de su tramo, no solo en las esquinas).
 * Los tiempos son estimaciones del modelo: no vienen de dispositivos reales.
 */
public final class PlanificadorRutas {
    private PlanificadorRutas() {}

    /** Distancia máxima entre el punto pedido y la calle más cercana. */
    public static final double RADIO_MAXIMO = 120;

    public record Punto(double x, double y) {}

    /**
     * Una instrucción. {@code giro}: salida, recto, derecha_suave, derecha, derecha_fuerte, izquierda_suave,
     * izquierda, izquierda_fuerte, vuelta o llegada. Distancia en metros y tiempo en segundos hasta la siguiente.
     */
    public record Paso(String giro, String texto, String calle, double distancia, double tiempo, double x, double y) {}

    public record Opcion(String etiqueta, double distancia, double tiempoLibre, double tiempoActual, double[] geometria, List<Paso> pasos, List<String> vias) {}

    private record Fijacion(Via via, double pos, double dist) {}

    /** Parte de una vía que se recorre, en metros lógicos desde su inicio. */
    private record Tramo(Via via, double desde, double hasta) {}

    private record Candidato(List<Tramo> tramos, double costo, Set<String> ids) {}

    // ------------------------------------------------------------------ tiempos con el tráfico actual

    private static double velocidad(Simulacion sim, Via v) {
        EstadoVia ev = sim.estadoVia(v.id());
        return Math.max(2.5, Math.min(v.velocidadMaxMs(), ev.velocidadMedia));
    }

    /** Espera media estimada al cruzar un semáforo: más corta con el control inteligente. */
    private static double demora(Simulacion sim, Via v) {
        Semaforo s = sim.semaforo(v.hacia());
        return s == null || !s.esActivo() ? 0 : sim.config().modoSemaforos == ModoSemaforos.INTELIGENTE ? 6 : 12;
    }

    private static double tiempoTramo(Simulacion sim, Tramo t, boolean cruzaAlFinal) {
        return (t.hasta() - t.desde()) / velocidad(sim, t.via()) + (cruzaAlFinal ? demora(sim, t.via()) : 0);
    }

    private static double costoVia(Simulacion sim, Via v) {
        if (sim.estadoVia(v.id()).bloqueada()) return Double.POSITIVE_INFINITY;
        return v.longitud() / velocidad(sim, v) + demora(sim, v);
    }

    // ------------------------------------------------------------------ geometría

    private static Fijacion fijar(Simulacion sim, Punto p) {
        Fijacion mejor = null;
        for (Via v : sim.red().vias()) {
            double[] q = v.puntos();
            double geom = v.longitudGeometria(), acum = 0;
            for (int i = 2; i < q.length; i += 2) {
                double x0 = q[i - 2], y0 = q[i - 1], dx = q[i] - x0, dy = q[i + 1] - y0, l2 = dx * dx + dy * dy, seg = Math.sqrt(l2);
                double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((p.x() - x0) * dx + (p.y() - y0) * dy) / l2));
                double d = Math.hypot(p.x() - (x0 + dx * t), p.y() - (y0 + dy * t));
                if (mejor == null || d < mejor.dist()) mejor = new Fijacion(v, (acum + seg * t) * (v.longitud() / geom), d);
                acum += seg;
            }
        }
        if (mejor == null || mejor.dist() > RADIO_MAXIMO) throw new IllegalArgumentException("Ese punto queda fuera de las calles del mapa cargado (a más de " + (int) RADIO_MAXIMO + " m de una calle).");
        return mejor;
    }

    private static Via inversa(Simulacion sim, Via v) {
        for (Grafo.Arista<Via> a : sim.red().grafo().salientes(v.hacia())) if (a.hacia().equals(v.desde())) return a.dato();
        return null;
    }

    /** Puntos de la vía entre dos posiciones lógicas (metros desde su inicio). */
    private static List<double[]> subLinea(Via v, double desde, double hasta) {
        double geom = v.longitudGeometria(), k = geom / v.longitud(), a = Math.max(0, desde * k), b = Math.min(geom, hasta * k);
        double[] q = v.puntos();
        List<double[]> salida = new ArrayList<>();
        double acum = 0;
        for (int i = 2; i < q.length; i += 2) {
            double x0 = q[i - 2], y0 = q[i - 1], x1 = q[i], y1 = q[i + 1], seg = Math.hypot(x1 - x0, y1 - y0);
            double ini = acum, fin = acum + seg;
            if (fin >= a && ini <= b && seg > 0) {
                double fa = Math.max(0, (a - ini) / seg), fb = Math.min(1, (b - ini) / seg);
                if (salida.isEmpty()) salida.add(new double[] {x0 + (x1 - x0) * fa, y0 + (y1 - y0) * fa});
                salida.add(new double[] {x0 + (x1 - x0) * fb, y0 + (y1 - y0) * fb});
            }
            acum = fin;
        }
        if (salida.isEmpty()) {
            double[] p = v.puntoEn(a);
            salida.add(new double[] {p[0], p[1]});
        }
        return salida;
    }

    private static double rumboEntrada(Via v) {
        double[] q = v.puntos();
        return Math.atan2(q[3] - q[1], q[2] - q[0]);
    }

    private static double rumboSalida(Via v) {
        double[] d = v.direccionFinal();
        return Math.atan2(d[1], d[0]);
    }

    // ------------------------------------------------------------------ planificación

    /** Devuelve la ruta más rápida y hasta {@code alternativas} rutas distintas, ordenadas por tiempo estimado. */
    public static List<Opcion> planificar(Simulacion sim, Punto origen, Punto destino, int alternativas) {
        if (Math.hypot(origen.x() - destino.x(), origen.y() - destino.y()) < 5) throw new IllegalArgumentException("El origen y el destino son el mismo lugar.");
        Fijacion fo = fijar(sim, origen), fd = fijar(sim, destino);
        List<Opcion> opciones = new ArrayList<>();
        Set<String> usadas = new HashSet<>();
        List<Set<String>> previas = new ArrayList<>();
        for (int intento = 0; intento <= alternativas + 2 && opciones.size() <= alternativas; intento++) {
            Set<String> yaUsadas = new HashSet<>(usadas);
            FuncionCosto f = v -> costoVia(sim, v) * (yaUsadas.contains(v.id()) ? 2.4 : 1.0);
            Candidato c = mejor(sim, fo, fd, f);
            if (c == null) {
                if (opciones.isEmpty()) throw new IllegalStateException("No hay ruta entre esos puntos con las calles abiertas ahora.");
                break;
            }
            usadas.addAll(c.ids());
            boolean repetida = false;
            for (Set<String> p : previas) {
                Set<String> inter = new HashSet<>(p);
                inter.retainAll(c.ids());
                if (inter.size() >= 0.85 * Math.min(p.size(), c.ids().size())) repetida = true; // casi la misma ruta
            }
            if (repetida) continue;
            previas.add(c.ids());
            Opcion o = describir(sim, c);
            if (!opciones.isEmpty() && o.tiempoActual() > opciones.get(0).tiempoActual() * 1.8 + 120) continue; // una alternativa demasiado lenta no sirve
            opciones.add(o);
        }
        opciones.sort((a, b) -> Double.compare(a.tiempoActual(), b.tiempoActual()));
        List<Opcion> etiquetadas = new ArrayList<>();
        for (int i = 0; i < opciones.size(); i++) {
            Opcion o = opciones.get(i);
            etiquetadas.add(new Opcion(i == 0 ? "Más rápida" : "Alternativa " + i, o.distancia(), o.tiempoLibre(), o.tiempoActual(), o.geometria(), o.pasos(), o.vias()));
        }
        return etiquetadas;
    }

    /** Prueba salir hacia adelante o por el sentido contrario de la calle (si existe) y llegar por cualquiera de los dos sentidos. */
    private static Candidato mejor(Simulacion sim, Fijacion fo, Fijacion fd, FuncionCosto f) {
        List<Tramo> inicios = new ArrayList<>(), finales = new ArrayList<>();
        inicios.add(new Tramo(fo.via(), fo.pos(), fo.via().longitud()));
        Via invO = inversa(sim, fo.via());
        if (invO != null) inicios.add(new Tramo(invO, invO.longitud() * (1 - fo.pos() / fo.via().longitud()), invO.longitud()));
        finales.add(new Tramo(fd.via(), 0, fd.pos()));
        Via invD = inversa(sim, fd.via());
        if (invD != null) finales.add(new Tramo(invD, 0, invD.longitud() * (1 - fd.pos() / fd.via().longitud())));
        Candidato mejor = null;
        for (Tramo ini : inicios) {
            for (Tramo fin : finales) {
                Candidato c = combinar(sim, ini, fin, f);
                if (c != null && (mejor == null || c.costo() < mejor.costo())) mejor = c;
            }
        }
        return mejor;
    }

    /** Un tramo de menos de un metro es el mismo punto de la esquina: se omite para no empezar «por la calle equivocada». */
    private static boolean util(Tramo t) {
        return t.hasta() - t.desde() >= 1.0;
    }

    private static Candidato combinar(Simulacion sim, Tramo ini, Tramo fin, FuncionCosto f) {
        EstadoVia evIni = sim.estadoVia(ini.via().id());
        if (evIni.bloqueada() && evIni.incidente.posicion > ini.desde() && util(ini)) return null; // el accidente está justo adelante
        // el punto de salida y el de llegada están en la misma calle y en orden: se recorre un solo tramo
        if (ini.via().id().equals(fin.via().id()) && ini.desde() <= fin.hasta()) {
            Tramo t = new Tramo(ini.via(), ini.desde(), fin.hasta());
            if (!util(t)) return null;
            double costo = f.costo(ini.via()) * ((t.hasta() - t.desde()) / ini.via().longitud());
            return Double.isInfinite(costo) ? null : new Candidato(List.of(t), costo, Set.of(ini.via().id()));
        }
        List<Tramo> tramos = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        double costo = 0;
        if (util(ini)) {
            costo += f.costo(ini.via()) * ((ini.hasta() - ini.desde()) / ini.via().longitud());
            tramos.add(ini);
            ids.add(ini.via().id());
        }
        if (util(fin)) costo += f.costo(fin.via()) * ((fin.hasta() - fin.desde()) / fin.via().longitud());
        if (Double.isInfinite(costo)) return null;
        String desde = ini.via().hacia(), hasta = fin.via().desde();
        if (!desde.equals(hasta)) {
            Ruta r = AEstrella.buscar(sim.red(), desde, hasta, f);
            if (r == null) return null;
            for (Via v : r.vias()) { tramos.add(new Tramo(v, 0, v.longitud())); ids.add(v.id()); }
            costo += r.costo();
        }
        if (util(fin)) {
            tramos.add(fin);
            ids.add(fin.via().id());
        }
        return tramos.isEmpty() ? null : new Candidato(tramos, costo, ids);
    }

    // ------------------------------------------------------------------ descripción de la ruta

    private static Opcion describir(Simulacion sim, Candidato c) {
        List<Tramo> tr = c.tramos();
        double distancia = 0, libre = 0, actual = 0;
        List<double[]> puntos = new ArrayList<>();
        List<String> vias = new ArrayList<>();
        // maniobras: índice del primer tramo de cada instrucción
        List<Integer> inicio = new ArrayList<>();
        List<String> giros = new ArrayList<>();
        inicio.add(0);
        giros.add("salida");
        for (int i = 0; i < tr.size(); i++) {
            Tramo t = tr.get(i);
            double largo = t.hasta() - t.desde();
            boolean cruza = i < tr.size() - 1;
            distancia += largo;
            libre += largo / t.via().velocidadMaxMs();
            actual += tiempoTramo(sim, t, cruza);
            vias.add(t.via().id());
            for (double[] p : subLinea(t.via(), t.desde(), t.hasta())) {
                if (puntos.isEmpty() || Math.hypot(p[0] - puntos.get(puntos.size() - 1)[0], p[1] - puntos.get(puntos.size() - 1)[1]) > 0.05) puntos.add(p);
            }
            if (i > 0) {
                double giro = Math.toDegrees(rumboEntrada(t.via()) - rumboSalida(tr.get(i - 1).via()));
                while (giro > 180) giro -= 360;
                while (giro <= -180) giro += 360;
                boolean cambiaNombre = !t.via().nombre().equals(tr.get(i - 1).via().nombre());
                if (cambiaNombre || Math.abs(giro) >= 50) {
                    inicio.add(i);
                    giros.add(clasificar(giro));
                }
            }
        }
        double[] geometria = new double[puntos.size() * 2];
        for (int i = 0; i < puntos.size(); i++) { geometria[i * 2] = puntos.get(i)[0]; geometria[i * 2 + 1] = puntos.get(i)[1]; }

        List<Paso> pasos = new ArrayList<>();
        for (int m = 0; m < inicio.size(); m++) {
            int a = inicio.get(m), b = m + 1 < inicio.size() ? inicio.get(m + 1) : tr.size();
            double dist = 0, tiempo = 0;
            for (int i = a; i < b; i++) {
                dist += tr.get(i).hasta() - tr.get(i).desde();
                tiempo += tiempoTramo(sim, tr.get(i), i < tr.size() - 1);
            }
            Via primera = tr.get(a).via();
            double[] pos = primera.puntoEn(tr.get(a).desde());
            String calle = primera.nombre();
            pasos.add(new Paso(giros.get(m), texto(giros.get(m), calle), calle, dist, tiempo, pos[0], pos[1]));
        }
        Tramo ultimo = tr.get(tr.size() - 1);
        double[] fin = ultimo.via().puntoEn(ultimo.hasta());
        pasos.add(new Paso("llegada", "Has llegado a tu destino", ultimo.via().nombre(), 0, 0, fin[0], fin[1]));
        return new Opcion("", distancia, libre, actual, geometria, pasos, vias);
    }

    static String clasificar(double giro) {
        double a = Math.abs(giro);
        String lado = giro > 0 ? "derecha" : "izquierda"; // con el eje y hacia abajo, un ángulo que crece es un giro a la derecha
        if (a < 25) return "recto";
        if (a < 60) return lado + "_suave";
        if (a < 135) return lado;
        if (a < 170) return lado + "_fuerte";
        return "vuelta";
    }

    static String texto(String giro, String calle) {
        return switch (giro) {
            case "salida" -> "Sal por " + calle;
            case "recto" -> "Continúa por " + calle;
            case "derecha_suave" -> "Gira ligeramente a la derecha hacia " + calle;
            case "izquierda_suave" -> "Gira ligeramente a la izquierda hacia " + calle;
            case "derecha" -> "Gira a la derecha en " + calle;
            case "izquierda" -> "Gira a la izquierda en " + calle;
            case "derecha_fuerte" -> "Gira pronunciadamente a la derecha en " + calle;
            case "izquierda_fuerte" -> "Gira pronunciadamente a la izquierda en " + calle;
            case "vuelta" -> "Da la vuelta en U en " + calle;
            default -> calle;
        };
    }

    /** Intersección más cercana a un punto (para ofrecer sitios como «Calle 5 con Carrera 39»). */
    static Interseccion esquinaCercana(Simulacion sim, Punto p) {
        Interseccion mejor = null;
        double md = Double.MAX_VALUE;
        for (Interseccion i : sim.red().intersecciones()) {
            double d = Math.hypot(i.x() - p.x(), i.y() - p.y());
            if (d < md) { md = d; mejor = i; }
        }
        return mejor;
    }
}
