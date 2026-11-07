package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.estructuras.ArbolAvl;
import smarttraffic.estructuras.ColaEnlazada;
import smarttraffic.estructuras.ColaPrioridad;
import smarttraffic.estructuras.Grafo;
import smarttraffic.estructuras.ListaEnlazada;
import smarttraffic.estructuras.TablaHash;

/** Pruebas de las estructuras de datos propias, comparadas contra la biblioteca estándar de Java. */
class EstructurasTest {

    @Test @DisplayName("Lista enlazada: extremos, índice, eliminación y iterador con remove")
    void lista() {
        ListaEnlazada<Integer> l = new ListaEnlazada<>();
        assertTrue(l.estaVacia());
        l.agregarFinal(2); l.agregarFinal(3); l.agregarInicio(1);
        assertEquals(List.of(1, 2, 3), l.aLista());
        assertEquals(1, l.primero()); assertEquals(3, l.ultimo()); assertEquals(2, l.obtener(1));
        assertTrue(l.eliminar(2)); assertFalse(l.eliminar(99));
        assertEquals(List.of(1, 3), l.aLista());
        assertEquals(3, l.quitarFinal()); assertEquals(1, l.quitarInicio());
        assertThrows(NoSuchElementException.class, l::quitarInicio);
        assertThrows(NoSuchElementException.class, l::quitarFinal);
        assertThrows(IndexOutOfBoundsException.class, () -> l.obtener(0));
        for (int i = 0; i < 10; i++) l.agregarFinal(i);
        var it = l.iterator();
        while (it.hasNext()) if (it.next() % 2 == 0) it.remove(); // quita los pares mientras recorre
        assertEquals(List.of(1, 3, 5, 7, 9), l.aLista());
        assertEquals(5, l.tamano());
        l.limpiar();
        assertEquals(0, l.tamano());
    }

    @Test @DisplayName("Cola FIFO: respeta el orden de llegada")
    void cola() {
        ColaEnlazada<String> c = new ColaEnlazada<>();
        c.encolar("VEH-1"); c.encolar("VEH-2"); c.encolar("VEH-3");
        assertEquals("VEH-1", c.frente()); assertEquals("VEH-3", c.ultimo());
        assertEquals("VEH-1", c.desencolar());
        assertTrue(c.eliminar("VEH-3"));
        assertEquals(1, c.tamano());
        assertEquals("VEH-2", c.desencolar());
        assertTrue(c.estaVacia());
        assertThrows(NoSuchElementException.class, c::desencolar);
        List<Integer> salida = new ArrayList<>();
        ColaEnlazada<Integer> d = new ColaEnlazada<>();
        for (int i = 0; i < 100; i++) d.encolar(i);
        for (int x : d) salida.add(x);
        assertEquals(100, salida.size()); assertEquals(0, salida.get(0)); assertEquals(99, salida.get(99));
    }

    @Test @DisplayName("Cola de prioridad: extrae siempre en orden, igual que java.util.PriorityQueue")
    void colaPrioridad() {
        Random r = new Random(7);
        ColaPrioridad<Integer> propia = new ColaPrioridad<>(Comparator.naturalOrder());
        PriorityQueue<Integer> ref = new PriorityQueue<>();
        for (int i = 0; i < 5000; i++) {
            if (r.nextInt(3) > 0 || ref.isEmpty()) { int x = r.nextInt(1000); propia.insertar(x); ref.add(x); }
            else assertEquals(ref.poll(), propia.extraerMinimo());
            if (!ref.isEmpty()) assertEquals(ref.peek(), propia.minimo());
            assertEquals(ref.size(), propia.tamano());
        }
        while (!ref.isEmpty()) assertEquals(ref.poll(), propia.extraerMinimo());
        assertThrows(NoSuchElementException.class, propia::extraerMinimo);
    }

    @Test @DisplayName("Cola de prioridad: emergencias antes que vehículos particulares")
    void prioridadEmergencias() {
        record Sol(String tipo, int prioridad, int orden) {}
        ColaPrioridad<Sol> q = new ColaPrioridad<>(Comparator.comparingInt(Sol::prioridad).thenComparingInt(Sol::orden));
        q.insertar(new Sol("Particular", 4, 1)); q.insertar(new Sol("Policía", 3, 2));
        q.insertar(new Sol("Ambulancia", 1, 3)); q.insertar(new Sol("Bomberos", 2, 4)); q.insertar(new Sol("Ambulancia B", 1, 5));
        assertEquals("Ambulancia", q.extraerMinimo().tipo());
        assertEquals("Ambulancia B", q.extraerMinimo().tipo(), "a igual prioridad, el que llegó primero");
        assertEquals("Bomberos", q.extraerMinimo().tipo());
        assertEquals("Policía", q.extraerMinimo().tipo());
        assertEquals("Particular", q.extraerMinimo().tipo());
    }

    @Test @DisplayName("Tabla hash: 30.000 operaciones aleatorias iguales a HashMap, con redimensionamiento")
    void tablaHash() {
        Random r = new Random(11);
        TablaHash<String, Integer> t = new TablaHash<>();
        Map<String, Integer> ref = new HashMap<>();
        int capacidadInicial = t.capacidad();
        for (int i = 0; i < 30000; i++) {
            String k = "VEH-" + r.nextInt(2000);
            switch (r.nextInt(3)) {
                case 0 -> assertEquals(ref.put(k, i), t.poner(k, i));
                case 1 -> assertEquals(ref.remove(k), t.eliminar(k));
                default -> { assertEquals(ref.get(k), t.obtener(k)); assertEquals(ref.containsKey(k), t.contiene(k)); }
            }
            assertEquals(ref.size(), t.tamano());
        }
        assertTrue(t.capacidad() > capacidadInicial, "debe haber crecido");
        int recorridos = 0;
        for (var e : t) { assertEquals(ref.get(e.clave()), e.valor()); recorridos++; }
        assertEquals(ref.size(), recorridos);
        assertEquals(ref.size(), t.claves().tamano());
        assertEquals(7, t.obtenerODefecto("NO-EXISTE", 7));
        assertThrows(NullPointerException.class, () -> t.poner(null, 1));
    }

    @Test @DisplayName("Tabla hash: claves que colisionan se siguen distinguiendo")
    void colisiones() {
        record Mala(int v) { @Override public int hashCode() { return 42; } }
        TablaHash<Mala, Integer> t = new TablaHash<>();
        for (int i = 0; i < 200; i++) t.poner(new Mala(i), i);
        for (int i = 0; i < 200; i++) assertEquals(i, t.obtener(new Mala(i)));
        assertEquals(100, t.eliminar(new Mala(100)));
        assertFalse(t.contiene(new Mala(100)));
        assertEquals(199, t.tamano());
    }

    @Test @DisplayName("Árbol AVL: inserciones ordenadas quedan balanceadas (altura logarítmica)")
    void avlBalance() {
        ArbolAvl<Integer, String> avl = new ArbolAvl<>();
        int n = 20000;
        for (int i = 0; i < n; i++) avl.insertar(i, "v" + i); // peor caso para un ABB simple
        assertEquals(n, avl.tamano());
        assertTrue(avl.esAvlValido());
        double maxAltura = 1.4405 * (Math.log(n + 2.0) / Math.log(2)) - 0.3277;
        assertTrue(avl.altura() <= Math.ceil(maxAltura), "altura " + avl.altura() + " debe ser <= " + maxAltura);
    }

    @Test @DisplayName("Árbol AVL: 20.000 operaciones aleatorias iguales a TreeMap y siempre válido")
    void avlContraTreeMap() {
        Random r = new Random(5);
        ArbolAvl<Integer, Integer> avl = new ArbolAvl<>();
        TreeMap<Integer, Integer> ref = new TreeMap<>();
        for (int i = 0; i < 20000; i++) {
            int k = r.nextInt(3000);
            if (r.nextInt(3) == 0) { avl.eliminar(k); ref.remove(k); }
            else { avl.insertar(k, i); ref.put(k, i); }
            assertEquals(ref.size(), avl.tamano());
            if (i % 500 == 0) assertTrue(avl.esAvlValido(), "inválido en la operación " + i);
        }
        assertTrue(avl.esAvlValido());
        List<Integer> claves = new ArrayList<>();
        avl.inorden((k, v) -> { claves.add(k); assertEquals(ref.get(k), v); });
        assertEquals(new ArrayList<>(ref.keySet()), claves, "el recorrido en orden debe salir ordenado");
        assertEquals(ref.firstKey(), avl.minimo().getKey()); assertEquals(ref.lastKey(), avl.maximo().getKey());
        assertEquals(new ArrayList<>(ref.subMap(500, true, 900, true).values()), avl.rango(500, 900));
        List<Integer> top = new ArrayList<>(ref.descendingMap().values()).subList(0, 10);
        assertEquals(top, avl.mayores(10));
    }

    @Test @DisplayName("Árbol AVL: eliminar hoja, nodo con un hijo y nodo con dos hijos")
    void avlEliminar() {
        ArbolAvl<Integer, Integer> avl = new ArbolAvl<>();
        for (int k : new int[] {50, 30, 70, 20, 40, 60, 80, 35, 45}) avl.insertar(k, k);
        avl.eliminar(20); assertFalse(avl.contiene(20)); assertTrue(avl.esAvlValido());
        avl.eliminar(30); assertFalse(avl.contiene(30)); assertTrue(avl.esAvlValido());
        avl.eliminar(50); assertFalse(avl.contiene(50)); assertTrue(avl.esAvlValido());
        avl.eliminar(999); assertEquals(6, avl.tamano());
        assertNull(avl.buscar(50)); assertEquals(60, avl.buscar(60));
        ArbolAvl<Integer, Integer> vacio = new ArbolAvl<>();
        assertNull(vacio.minimo()); assertTrue(vacio.rango(1, 10).isEmpty()); assertEquals(0, vacio.altura());
    }

    @Test @DisplayName("Grafo dirigido: vértices, aristas, vecinos y eliminación")
    void grafo() {
        Grafo<String, Integer> g = new Grafo<>();
        for (String v : new String[] {"A", "B", "C", "D"}) g.agregarVertice(v, "dato " + v);
        g.agregarArista("A-B", "A", "B", 10); g.agregarArista("B-A", "B", "A", 10);
        g.agregarArista("B-C", "B", "C", 20); g.agregarArista("C-D", "C", "D", 5);
        assertEquals(4, g.numeroVertices()); assertEquals(4, g.numeroAristas());
        assertEquals(2, g.salientes("B").tamano()); assertEquals(1, g.entrantes("B").tamano());
        assertEquals("C", g.arista("B-C").hacia());
        assertThrows(IllegalArgumentException.class, () -> g.agregarVertice("A", "otra"));
        assertThrows(IllegalArgumentException.class, () -> g.agregarArista("A-B", "A", "B", 1));
        assertThrows(NoSuchElementException.class, () -> g.agregarArista("X", "A", "Z", 1));
        assertThrows(NoSuchElementException.class, () -> g.salientes("Z"));
        assertTrue(g.eliminarArista("B-A")); assertFalse(g.eliminarArista("B-A"));
        assertEquals(1, g.salientes("B").tamano());
        assertTrue(g.eliminarVertice("C"));
        assertEquals(3, g.numeroVertices()); assertEquals(1, g.numeroAristas());
        assertEquals(0, g.salientes("D").tamano()); assertEquals(0, g.entrantes("D").tamano());
        assertEquals(List.of("A", "B", "D"), g.idsVertices().aLista());
    }
}
