package smarttraffic.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedCali;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.sim.PlanificadorRutas.Opcion;
import smarttraffic.sim.PlanificadorRutas.Punto;

class PlanificadorRutasTest {

    /** Cruz en B: A al oeste, D al este, C al sur y E al norte. Calle Uno (A-B-D) y Carrera Dos (E-B-C). Todo de doble sentido. */
    private static Simulacion cruz() {
        RedVial r = new RedVial("cruz");
        for (Object[] n : new Object[][] {{"A", 0, 0}, {"B", 200, 0}, {"D", 400, 0}, {"C", 200, 200}, {"E", 200, -200}}) {
            r.agregarInterseccion(new Interseccion((String) n[0], (String) n[0], ((Number) n[1]).doubleValue(), ((Number) n[2]).doubleValue(), "B".equals(n[0]) ? TipoNodo.INTERIOR : TipoNodo.ENTRADA_SALIDA));
        }
        r.agregarCalle("AB", "A", "B", true, "Calle Uno", 36);
        r.agregarCalle("BD", "B", "D", true, "Calle Uno", 36);
        r.agregarCalle("BC", "B", "C", true, "Carrera Dos", 36);
        r.agregarCalle("BE", "B", "E", true, "Carrera Dos", 36);
        return new Simulacion(r, Config.tradicional(1, 0));
    }

    private static Opcion mejor(Simulacion s, double x0, double y0, double x1, double y1) {
        return PlanificadorRutas.planificar(s, new Punto(x0, y0), new Punto(x1, y1), 0).get(0);
    }

    private static List<String> giros(Opcion o) {
        return o.pasos().stream().map(PlanificadorRutas.Paso::giro).toList();
    }

    @Test @DisplayName("Los giros salen bien: a la derecha, a la izquierda y recto (con el eje y hacia abajo, como en pantalla)")
    void giros() {
        Simulacion s = cruz();
        Opcion derecha = mejor(s, 5, 0, 200, 195);   // hacia el este y luego al sur = derecha
        assertEquals(List.of("salida", "derecha", "llegada"), giros(derecha));
        assertEquals("Gira a la derecha en Carrera Dos", derecha.pasos().get(1).texto());
        assertEquals("Sal por Calle Uno", derecha.pasos().get(0).texto());
        Opcion izquierda = mejor(s, 5, 0, 200, -195);  // hacia el este y luego al norte = izquierda
        assertEquals(List.of("salida", "izquierda", "llegada"), giros(izquierda));
        assertEquals("Gira a la izquierda en Carrera Dos", izquierda.pasos().get(1).texto());
        Opcion recto = mejor(s, 5, 0, 395, 0);
        assertEquals(List.of("salida", "llegada"), giros(recto), "seguir por la misma calle no genera instrucciones nuevas");
        assertEquals("Has llegado a tu destino", recto.pasos().get(recto.pasos().size() - 1).texto());
    }

    @Test @DisplayName("Clasificación de giros por ángulo")
    void clasificacion() {
        assertEquals("recto", PlanificadorRutas.clasificar(10));
        assertEquals("derecha_suave", PlanificadorRutas.clasificar(40));
        assertEquals("izquierda_suave", PlanificadorRutas.clasificar(-40));
        assertEquals("derecha", PlanificadorRutas.clasificar(90));
        assertEquals("izquierda", PlanificadorRutas.clasificar(-90));
        assertEquals("derecha_fuerte", PlanificadorRutas.clasificar(150));
        assertEquals("izquierda_fuerte", PlanificadorRutas.clasificar(-150));
        assertEquals("vuelta", PlanificadorRutas.clasificar(180));
        assertEquals("vuelta", PlanificadorRutas.clasificar(-175));
    }

    @Test @DisplayName("Se puede salir y llegar a mitad de cuadra, no solo en las esquinas")
    void mitadDeCuadra() {
        Simulacion s = cruz();
        Opcion o = mejor(s, 100, 6, 206, 100);   // mitad de Calle Uno (a 6 m de la calle) hasta mitad de Carrera Dos
        assertEquals(200.0, o.distancia(), 6.0, "100 m hasta la esquina y 100 m más");
        double[] g = o.geometria();
        assertTrue(Math.hypot(g[0] - 100, g[1] - 0) < 1.0, "empieza sobre la calle, en el punto pedido");
        assertTrue(Math.hypot(g[g.length - 2] - 200, g[g.length - 1] - 100) < 1.0, "termina sobre la otra calle, en el punto pedido");
        assertEquals(o.distancia(), o.pasos().stream().mapToDouble(PlanificadorRutas.Paso::distancia).sum(), 0.5, "las distancias de las instrucciones suman el total");
        assertEquals(o.tiempoActual(), o.pasos().stream().mapToDouble(PlanificadorRutas.Paso::tiempo).sum(), 0.5, "y los tiempos también");
        // misma calle, el destino está atrás: se puede ir por el otro sentido de la calle
        Opcion atras = mejor(s, 150, 5, 50, 5);
        assertEquals(100.0, atras.distancia(), 3.0);
    }

    @Test @DisplayName("Respeta las calles de un solo sentido: da la vuelta por donde se puede")
    void unSentido() {
        RedVial r = new RedVial("sentido");
        for (Object[] n : new Object[][] {{"A", 0, 0}, {"B", 300, 0}, {"F", 0, 200}, {"G", 300, 200}}) r.agregarInterseccion(new Interseccion((String) n[0], (String) n[0], ((Number) n[1]).doubleValue(), ((Number) n[2]).doubleValue(), TipoNodo.ENTRADA_SALIDA));
        r.agregarVia("AB", "A", "B", "Calle Directa", null, 36);   // solo de A hacia B
        r.agregarCalle("AF", "A", "F", true, "Carrera Oeste", 36);
        r.agregarCalle("FG", "F", "G", true, "Calle Sur", 36);
        r.agregarCalle("GB", "G", "B", true, "Carrera Este", 36);
        Simulacion s = new Simulacion(r, Config.tradicional(1, 0));
        Opcion ida = mejor(s, 0, 0, 300, 0);
        assertEquals(300.0, ida.distancia(), 6.0, "de A a B se va directo");
        Opcion vuelta = mejor(s, 300, 0, 0, 0);
        assertEquals(700.0, vuelta.distancia(), 6.0, "de B a A no se puede ir contra el sentido: se rodea (200 + 300 + 200)");
        assertFalse(vuelta.vias().contains("AB"));
    }

    @Test @DisplayName("Un accidente cierra una calle: la ruta la evita, tarda más; y si no hay otra salida, se dice")
    void cierres() {
        Simulacion s = new Simulacion(RedCali.crear(), Config.inteligente(3, 0));
        Interseccion a = s.red().interseccion("N00"), b = s.red().interseccion("N33");
        Opcion antes = mejor(s, a.x(), a.y(), b.x(), b.y());
        String viaCortada = antes.vias().get(antes.vias().size() / 2);
        s.crearIncidente(viaCortada, 0.5, 3);
        Opcion despues = mejor(s, a.x(), a.y(), b.x(), b.y());
        assertFalse(despues.vias().contains(viaCortada), "la ruta nueva no pasa por la calle bloqueada");
        assertTrue(despues.tiempoActual() >= antes.tiempoActual() - 1e-6, "rodear no puede ser más rápido que el camino directo libre");

        Simulacion callejon = cruz();
        callejon.crearIncidente("AB:AB", 0.5, 2);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> mejor(callejon, 5, 0, 395, 0));
        assertTrue(e.getMessage().contains("No hay ruta"), e.getMessage());
    }

    @Test @DisplayName("Alternativas: rutas distintas, ordenadas por tiempo y con su etiqueta")
    void alternativas() {
        Simulacion s = new Simulacion(RedCali.crear(), Config.inteligente(3, 0));
        Interseccion a = s.red().interseccion("N00"), b = s.red().interseccion("N33");
        List<Opcion> ops = PlanificadorRutas.planificar(s, new Punto(a.x(), a.y()), new Punto(b.x(), b.y()), 2);
        assertTrue(ops.size() >= 2, "en una malla hay más de un camino razonable: " + ops.size());
        assertEquals("Más rápida", ops.get(0).etiqueta()); assertEquals("Alternativa 1", ops.get(1).etiqueta());
        for (int i = 1; i < ops.size(); i++) assertTrue(ops.get(i).tiempoActual() >= ops.get(i - 1).tiempoActual());
        Set<Set<String>> distintas = new HashSet<>();
        for (Opcion o : ops) distintas.add(new HashSet<>(o.vias()));
        assertEquals(ops.size(), distintas.size(), "no se repite la misma ruta");
    }

    @Test @DisplayName("El tiempo con tráfico no es menor que el tiempo sin tráfico, y crece cuando la red se llena")
    void trafico() {
        Simulacion s = new Simulacion(RedCali.crear(), Config.tradicional(5, 160));
        Interseccion a = s.red().interseccion("N00"), b = s.red().interseccion("N33");
        Opcion vacia = mejor(s, a.x(), a.y(), b.x(), b.y());
        assertTrue(vacia.tiempoActual() >= vacia.tiempoLibre());
        s.avanzar(400);
        Opcion llena = mejor(s, a.x(), a.y(), b.x(), b.y());
        assertTrue(llena.tiempoActual() >= llena.tiempoLibre());
        assertTrue(llena.tiempoActual() > vacia.tiempoActual() * 0.99, "con la red cargada el viaje no se estima más rápido");
    }

    @Test @DisplayName("La geometría es continua y un punto lejos de toda calle se rechaza")
    void geometriaYLimites() {
        Simulacion s = new Simulacion(RedCali.crear(), Config.tradicional(1, 0));
        Interseccion a = s.red().interseccion("N01"), b = s.red().interseccion("N32");
        Opcion o = mejor(s, a.x(), a.y(), b.x(), b.y());
        double[] g = o.geometria();
        assertTrue(g.length >= 8);
        for (int i = 2; i < g.length; i += 2) assertTrue(Math.hypot(g[i] - g[i - 2], g[i + 1] - g[i - 1]) < 400, "sin saltos entre puntos");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mejor(s, a.x(), a.y(), 5000, 5000));
        assertTrue(e.getMessage().contains("fuera de las calles"), e.getMessage());
    }
}
