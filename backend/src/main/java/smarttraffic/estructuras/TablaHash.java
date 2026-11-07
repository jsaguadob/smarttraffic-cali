package smarttraffic.estructuras;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Tabla hash con encadenamiento separado y redimensionamiento automático.
 *
 * <p>Uso en SmartTraffic: acceso inmediato por identificador a vehículos (VEH-001), intersecciones,
 * vías e incidentes. El orden de recorrido depende solo de los códigos hash, así que es repetible
 * entre ejecuciones (necesario para comparar escenarios con la misma semilla).
 *
 * <p>Complejidad promedio: poner, obtener y eliminar O(1); peor caso O(n) si todas las claves
 * colisionan. Se duplica la capacidad cuando el factor de carga supera 0,75.
 */
public class TablaHash<K, V> implements Iterable<TablaHash.Entrada<K, V>> {

    public static final class Entrada<K, V> {
        private final K clave;
        private V valor;
        private Entrada<K, V> siguiente;

        Entrada(K clave, V valor) {
            this.clave = clave;
            this.valor = valor;
        }

        public K clave() {
            return clave;
        }

        public V valor() {
            return valor;
        }
    }

    private static final double FACTOR_CARGA = 0.75;
    private Entrada<K, V>[] cubetas;
    private int tamano;

    public TablaHash() {
        this(16);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public TablaHash(int capacidadInicial) {
        int cap = 16;
        while (cap < capacidadInicial) cap <<= 1;
        cubetas = (Entrada<K, V>[]) new Entrada[cap];
    }

    public int tamano() {
        return tamano;
    }

    public boolean estaVacia() {
        return tamano == 0;
    }

    private static int indice(Object clave, int capacidad) {
        int h = Objects.requireNonNull(clave, "La clave no puede ser nula").hashCode();
        h ^= (h >>> 16); // mezcla los bits altos para repartir mejor
        return h & (capacidad - 1);
    }

    /** Asocia el valor a la clave y devuelve el valor anterior (o null). O(1) promedio. */
    public V poner(K clave, V valor) {
        int i = indice(clave, cubetas.length);
        for (Entrada<K, V> e = cubetas[i]; e != null; e = e.siguiente) {
            if (e.clave.equals(clave)) {
                V anterior = e.valor;
                e.valor = valor;
                return anterior;
            }
        }
        Entrada<K, V> nueva = new Entrada<>(clave, valor);
        nueva.siguiente = cubetas[i];
        cubetas[i] = nueva;
        if (++tamano > cubetas.length * FACTOR_CARGA) redimensionar();
        return null;
    }

    /** Valor asociado a la clave o null. O(1) promedio. */
    public V obtener(K clave) {
        for (Entrada<K, V> e = cubetas[indice(clave, cubetas.length)]; e != null; e = e.siguiente) {
            if (e.clave.equals(clave)) return e.valor;
        }
        return null;
    }

    public V obtenerODefecto(K clave, V defecto) {
        V v = obtener(clave);
        return v == null ? defecto : v;
    }

    public boolean contiene(K clave) {
        for (Entrada<K, V> e = cubetas[indice(clave, cubetas.length)]; e != null; e = e.siguiente) {
            if (e.clave.equals(clave)) return true;
        }
        return false;
    }

    /** Elimina la clave y devuelve su valor (o null). O(1) promedio. */
    public V eliminar(K clave) {
        int i = indice(clave, cubetas.length);
        Entrada<K, V> previa = null;
        for (Entrada<K, V> e = cubetas[i]; e != null; previa = e, e = e.siguiente) {
            if (e.clave.equals(clave)) {
                if (previa == null) cubetas[i] = e.siguiente; else previa.siguiente = e.siguiente;
                tamano--;
                return e.valor;
            }
        }
        return null;
    }

    public ListaEnlazada<K> claves() {
        ListaEnlazada<K> lista = new ListaEnlazada<>();
        for (Entrada<K, V> e : this) lista.agregarFinal(e.clave);
        return lista;
    }

    public ListaEnlazada<V> valores() {
        ListaEnlazada<V> lista = new ListaEnlazada<>();
        for (Entrada<K, V> e : this) lista.agregarFinal(e.valor);
        return lista;
    }

    /** Capacidad actual de cubetas (para pruebas y para explicar el redimensionamiento). */
    public int capacidad() {
        return cubetas.length;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void redimensionar() {
        Entrada<K, V>[] viejas = cubetas;
        cubetas = (Entrada<K, V>[]) new Entrada[viejas.length * 2];
        for (Entrada<K, V> cabeza : viejas) {
            for (Entrada<K, V> e = cabeza; e != null; ) {
                Entrada<K, V> sig = e.siguiente;
                int i = indice(e.clave, cubetas.length);
                e.siguiente = cubetas[i];
                cubetas[i] = e;
                e = sig;
            }
        }
    }

    @Override
    public Iterator<Entrada<K, V>> iterator() {
        return new Iterator<>() {
            private int cubeta = -1;
            private Entrada<K, V> siguiente = avanzar(null);

            private Entrada<K, V> avanzar(Entrada<K, V> actual) {
                if (actual != null && actual.siguiente != null) return actual.siguiente;
                while (++cubeta < cubetas.length) {
                    if (cubetas[cubeta] != null) return cubetas[cubeta];
                }
                return null;
            }

            @Override
            public boolean hasNext() {
                return siguiente != null;
            }

            @Override
            public Entrada<K, V> next() {
                if (siguiente == null) throw new NoSuchElementException();
                Entrada<K, V> actual = siguiente;
                siguiente = avanzar(actual);
                return actual;
            }
        };
    }
}
