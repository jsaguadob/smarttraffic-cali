package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedCali;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.ruta.AEstrella;
import smarttraffic.ruta.Dijkstra;
import smarttraffic.ruta.FuncionCosto;
import smarttraffic.ruta.Ruta;

class RedYRutasTest {

    private static RedVial cuadro() { // A---B / | | / C---D, todas de doble sentido, 100 m, 36 km/h (10 m/s)
        RedVial r = new RedVial("cuadro");
        r.agregarInterseccion(new Interseccion("A", "A", 0, 0, TipoNodo.ENTRADA_SALIDA));
        r.agregarInterseccion(new Interseccion("B", "B", 100, 0, TipoNodo.ENTRADA_SALIDA));
        r.agregarInterseccion(new Interseccion("C", "C", 0, 100, TipoNodo.ENTRADA_SALIDA));
        r.agregarInterseccion(new Interseccion("D", "D", 100, 100, TipoNodo.ENTRADA_SALIDA));
        r.agregarCalle("AB", "A", "B", true, "AB", 36); r.agregarCalle("CD", "C", "D", true, "CD", 36);
        r.agregarCalle("AC", "A", "C", true, "AC", 36); r.agregarCalle("BD", "B", "D", true, "BD", 36);
        return r;
    }

    @Test @DisplayName("Red de Cali: tamaño, conexidad, entradas y bases de emergencia")
    void redCali() {
        RedVial red = RedCali.crear();
        assertEquals(18, red.intersecciones().size());
        assertEquals(12, red.entradasSalidas().size());
        assertEquals(2, red.bases().size());
        assertTrue(red.fuertementeConexa(), "con calles de un solo sentido se debe poder ir de cualquier punto a cualquier otro");
        assertTrue(red.validarParaSimulacion().isEmpty(), red.validarParaSimulacion().toString());
        assertEquals(40, red.vias().size(), "18 horizontales + 18 verticales + 4 de acceso");
        assertTrue(red.factorHeuristica() <= 1.0);
    }

    @Test @DisplayName("Red: validaciones al crear y editar intersecciones y vías")
    void validaciones() {
        RedVial r = cuadro();
        assertThrows(IllegalArgumentException.class, () -> r.agregarInterseccion(new Interseccion("A", "otra", 5, 5, TipoNodo.INTERIOR)));
        assertThrows(IllegalArgumentException.class, () -> r.agregarVia("X", "A", "A", "bucle", null, 40), "no se permiten bucles");
        assertThrows(IllegalArgumentException.class, () -> r.agregarVia("X", "A", "Z", "fantasma", null, 40));
        assertThrows(IllegalArgumentException.class, () -> r.agregarVia("X", "A", "D", "rápida", null, 300), "velocidad fuera de rango");
        assertThrows(IllegalArgumentException.class, () -> r.agregarVia("X", "A", "D", "larga", -5.0, 40));
        assertThrows(IllegalArgumentException.class, () -> r.agregarVia("AB:AB", "A", "B", "repetida", null, 40));
        Via diagonal = r.agregarVia("DIAG", "A", "D", "diagonal", null, 40);
        assertEquals(Math.hypot(100, 100), diagonal.longitud(), 1e-9, "la longitud se calcula con las coordenadas");
        Via fija = r.agregarVia("FIJA", "B", "C", "fijada a mano", 500.0, 40);
        r.moverInterseccion("A", -100, 0);
        assertEquals(Math.hypot(200, 100), diagonal.longitud(), 1e-9, "al mover un nodo se recalculan las longitudes automáticas");
        r.moverInterseccion("B", 300, 0);
        assertEquals(500.0, fija.longitud(), 1e-9, "la longitud fijada a mano se respeta");
        assertTrue(r.eliminarInterseccion("D"));
        assertNull(r.via("DIAG")); assertNull(r.via("CD:AB")); assertEquals(3, r.intersecciones().size());
        assertThrows(java.util.NoSuchElementException.class, () -> r.moverInterseccion("D", 0, 0));
    }

    @Test @DisplayName("Red: detecta redes que no se pueden simular")
    void redInvalida() {
        RedVial r = new RedVial("vacía");
        assertFalse(r.validarParaSimulacion().isEmpty());
        r.agregarInterseccion(new Interseccion("A", "A", 0, 0, TipoNodo.ENTRADA_SALIDA));
        r.agregarInterseccion(new Interseccion("B", "B", 100, 0, TipoNodo.ENTRADA_SALIDA));
        r.agregarVia("AB", "A", "B", "solo ida", null, 40); // desde B no se puede volver a A
        assertFalse(r.fuertementeConexa());
        assertFalse(r.validarParaSimulacion().isEmpty());
        r.agregarVia("BA", "B", "A", "vuelta", null, 40);
        assertTrue(r.validarParaSimulacion().isEmpty());
    }

    @Test @DisplayName("Dijkstra: ruta más corta, sentidos y rutas alternativas cuando se cierra una vía")
    void dijkstra() {
        RedVial r = cuadro();
        Ruta ab = Dijkstra.buscar(r, "A", "B", FuncionCosto.TIEMPO_LIBRE);
        assertEquals(List.of("A", "B"), ab.nodos()); assertEquals(10.0, ab.costo(), 1e-9);
        // si se cierra la vía A→B, la alternativa es A→C→D→B
        Ruta alterna = Dijkstra.buscar(r, "A", "B", v -> v.id().equals("AB:AB") ? Double.POSITIVE_INFINITY : v.tiempoLibre());
        assertEquals(List.of("A", "C", "D", "B"), alterna.nodos()); assertEquals(30.0, alterna.costo(), 1e-9);
        assertTrue(alterna.longitudTotal() > ab.longitudTotal()); assertFalse(alterna.usa("AB:AB"));
        // mismo origen y destino
        assertEquals(0, Dijkstra.buscar(r, "A", "A", FuncionCosto.TIEMPO_LIBRE).vias().length);
        // aislado: se cierran todas las vías que llegan a B
        assertNull(Dijkstra.buscar(r, "A", "B", v -> v.hacia().equals("B") ? Double.POSITIVE_INFINITY : v.tiempoLibre()));
        assertThrows(IllegalArgumentException.class, () -> Dijkstra.buscar(r, "A", "Z", FuncionCosto.TIEMPO_LIBRE));
        assertThrows(IllegalArgumentException.class, () -> Dijkstra.buscar(r, "A", "B", v -> -1.0));
    }

    @Test @DisplayName("Dijkstra respeta los sentidos de circulación")
    void sentidos() {
        RedVial r = new RedVial("unsentido");
        for (String id : new String[] {"A", "B", "C"}) r.agregarInterseccion(new Interseccion(id, id, id.charAt(0) * 3, 0, TipoNodo.INTERIOR));
        r.agregarVia("AB", "A", "B", "ida", null, 40); r.agregarVia("BC", "B", "C", "ida", null, 40);
        assertNotNull(Dijkstra.buscar(r, "A", "C", FuncionCosto.TIEMPO_LIBRE));
        assertNull(Dijkstra.buscar(r, "C", "A", FuncionCosto.TIEMPO_LIBRE), "contra el sentido no hay ruta");
    }

    private static void verificarCamino(Ruta ruta, String origen, String destino) {
        String actual = origen;
        double suma = 0;
        for (Via v : ruta.vias()) { assertEquals(actual, v.desde(), "las vías deben encadenarse"); actual = v.hacia(); }
        assertEquals(destino, actual);
        assertEquals(origen, ruta.origen()); assertEquals(destino, ruta.destino());
        for (Via v : ruta.vias()) suma += v.tiempoLibre();
        assertTrue(ruta.costo() >= suma - 1e-6, "con congestión el costo no baja del tiempo libre");
    }

    @Test @DisplayName("A* y Dijkstra encuentran el mismo costo óptimo en 500 consultas con congestión y cierres")
    void aEstrellaIgualDijkstra() {
        RedVial red = RedCali.crear();
        Random rnd = new Random(3);
        List<Via> vias = red.vias();
        double[] factor = new double[vias.size()]; // cada vía tiene un recargo por congestión >= 1
        boolean[] cerrada = new boolean[vias.size()];
        for (int i = 0; i < factor.length; i++) { factor[i] = 1 + rnd.nextDouble() * 4; cerrada[i] = rnd.nextInt(14) == 0; }
        FuncionCosto f = v -> { int i = vias.indexOf(v); return cerrada[i] ? Double.POSITIVE_INFINITY : v.tiempoLibre() * factor[i]; };
        List<String> ids = new ArrayList<>(); for (Interseccion i : red.intersecciones()) ids.add(i.id());
        long expDijkstra = 0, expAStar = 0; int alcanzables = 0;
        for (int q = 0; q < 500; q++) {
            String o = ids.get(rnd.nextInt(ids.size())), d = ids.get(rnd.nextInt(ids.size()));
            Ruta rd = Dijkstra.buscar(red, o, d, f), ra = AEstrella.buscar(red, o, d, f);
            if (rd == null) { assertNull(ra, "si Dijkstra no encuentra ruta, A* tampoco"); continue; }
            assertNotNull(ra); alcanzables++;
            assertEquals(rd.costo(), ra.costo(), 1e-6, o + "→" + d);
            verificarCamino(rd, o, d); verificarCamino(ra, o, d);
            for (Via v : ra.vias()) assertFalse(cerrada[vias.indexOf(v)], "no debe usar vías cerradas");
            expDijkstra += rd.nodosExpandidos(); expAStar += ra.nodosExpandidos();
        }
        assertTrue(alcanzables > 300);
        assertTrue(expAStar <= expDijkstra, "A* expandió " + expAStar + " nodos y Dijkstra " + expDijkstra);
    }

    @Test @DisplayName("A* sigue siendo óptimo aunque se fijen a mano longitudes más cortas que la distancia recta")
    void heuristicaAdmisible() {
        RedVial r = new RedVial("atajos");
        r.agregarInterseccion(new Interseccion("A", "A", 0, 0, TipoNodo.INTERIOR));
        r.agregarInterseccion(new Interseccion("B", "B", 1000, 0, TipoNodo.INTERIOR));
        r.agregarInterseccion(new Interseccion("C", "C", 500, 400, TipoNodo.INTERIOR));
        r.agregarVia("AB", "A", "B", "túnel", 100.0, 60); // 100 m reales aunque estén a 1000 m en el plano
        r.agregarVia("AC", "A", "C", "larga", null, 60); r.agregarVia("CB", "C", "B", "larga", null, 60);
        assertTrue(r.factorHeuristica() < 1);
        assertEquals(Dijkstra.buscar(r, "A", "B", FuncionCosto.TIEMPO_LIBRE).costo(), AEstrella.buscar(r, "A", "B", FuncionCosto.TIEMPO_LIBRE).costo(), 1e-9);
    }
}
