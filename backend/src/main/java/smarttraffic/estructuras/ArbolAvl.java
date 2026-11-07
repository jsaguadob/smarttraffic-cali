package smarttraffic.estructuras;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Árbol AVL: árbol binario de búsqueda que se auto-balancea. Para cada nodo, la diferencia de
 * alturas entre sus subárboles es -1, 0 o 1, lo que garantiza altura O(log n).
 *
 * <p>Uso en SmartTraffic: índice ordenado de incidentes (por consecutivo) y ranking de vías por
 * puntaje de congestión, para obtener «las N más congestionadas» sin ordenar todo cada vez.
 *
 * <p>Complejidad: insertar, buscar y eliminar O(log n); recorrer en orden O(n); mayores(k) y
 * rango(a, b) O(log n + k).
 */
public class ArbolAvl<K extends Comparable<K>, V> {

    private static final class Nodo<K, V> {
        K clave;
        V valor;
        int altura = 1;
        Nodo<K, V> izquierdo;
        Nodo<K, V> derecho;

        Nodo(K clave, V valor) {
            this.clave = clave;
            this.valor = valor;
        }
    }

    private Nodo<K, V> raiz;
    private int tamano;

    public int tamano() {
        return tamano;
    }

    public boolean estaVacio() {
        return tamano == 0;
    }

    public int altura() {
        return altura(raiz);
    }

    /** Inserta la pareja; si la clave ya existe reemplaza el valor. O(log n). */
    public void insertar(K clave, V valor) {
        raiz = insertar(raiz, clave, valor);
    }

    private Nodo<K, V> insertar(Nodo<K, V> nodo, K clave, V valor) {
        if (nodo == null) {
            tamano++;
            return new Nodo<>(clave, valor);
        }
        int c = clave.compareTo(nodo.clave);
        if (c < 0) nodo.izquierdo = insertar(nodo.izquierdo, clave, valor);
        else if (c > 0) nodo.derecho = insertar(nodo.derecho, clave, valor);
        else {
            nodo.valor = valor;
            return nodo;
        }
        return balancear(nodo);
    }

    /** Valor de la clave o null si no existe. O(log n). */
    public V buscar(K clave) {
        Nodo<K, V> n = raiz;
        while (n != null) {
            int c = clave.compareTo(n.clave);
            if (c == 0) return n.valor;
            n = c < 0 ? n.izquierdo : n.derecho;
        }
        return null;
    }

    public boolean contiene(K clave) {
        Nodo<K, V> n = raiz;
        while (n != null) {
            int c = clave.compareTo(n.clave);
            if (c == 0) return true;
            n = c < 0 ? n.izquierdo : n.derecho;
        }
        return false;
    }

    /** Elimina la clave si existe. O(log n). */
    public void eliminar(K clave) {
        raiz = eliminar(raiz, clave);
    }

    private Nodo<K, V> eliminar(Nodo<K, V> nodo, K clave) {
        if (nodo == null) return null;
        int c = clave.compareTo(nodo.clave);
        if (c < 0) nodo.izquierdo = eliminar(nodo.izquierdo, clave);
        else if (c > 0) nodo.derecho = eliminar(nodo.derecho, clave);
        else {
            tamano--;
            if (nodo.izquierdo == null) return nodo.derecho;
            if (nodo.derecho == null) return nodo.izquierdo;
            Nodo<K, V> sucesor = nodo.derecho; // dos hijos: se reemplaza por el sucesor en orden
            while (sucesor.izquierdo != null) sucesor = sucesor.izquierdo;
            nodo.clave = sucesor.clave;
            nodo.valor = sucesor.valor;
            tamano++; // la llamada siguiente vuelve a descontar el nodo del sucesor
            nodo.derecho = eliminar(nodo.derecho, sucesor.clave);
        }
        return balancear(nodo);
    }

    public Map.Entry<K, V> minimo() {
        if (raiz == null) return null;
        Nodo<K, V> n = raiz;
        while (n.izquierdo != null) n = n.izquierdo;
        return new AbstractMap.SimpleImmutableEntry<>(n.clave, n.valor);
    }

    public Map.Entry<K, V> maximo() {
        if (raiz == null) return null;
        Nodo<K, V> n = raiz;
        while (n.derecho != null) n = n.derecho;
        return new AbstractMap.SimpleImmutableEntry<>(n.clave, n.valor);
    }

    /** Recorre de menor a mayor clave. O(n). */
    public void inorden(BiConsumer<K, V> accion) {
        inorden(raiz, accion);
    }

    private void inorden(Nodo<K, V> n, BiConsumer<K, V> accion) {
        if (n == null) return;
        inorden(n.izquierdo, accion);
        accion.accept(n.clave, n.valor);
        inorden(n.derecho, accion);
    }

    /** Valores con clave en [desde, hasta], de menor a mayor. O(log n + k). */
    public List<V> rango(K desde, K hasta) {
        List<V> salida = new ArrayList<>();
        rango(raiz, desde, hasta, salida);
        return salida;
    }

    private void rango(Nodo<K, V> n, K desde, K hasta, List<V> salida) {
        if (n == null) return;
        if (desde.compareTo(n.clave) < 0) rango(n.izquierdo, desde, hasta, salida);
        if (desde.compareTo(n.clave) <= 0 && hasta.compareTo(n.clave) >= 0) salida.add(n.valor);
        if (hasta.compareTo(n.clave) > 0) rango(n.derecho, desde, hasta, salida);
    }

    /** Los k valores de mayor clave, de mayor a menor. O(log n + k). */
    public List<V> mayores(int k) {
        List<V> salida = new ArrayList<>();
        mayores(raiz, k, salida);
        return salida;
    }

    private void mayores(Nodo<K, V> n, int k, List<V> salida) {
        if (n == null || salida.size() >= k) return;
        mayores(n.derecho, k, salida);
        if (salida.size() < k) salida.add(n.valor);
        mayores(n.izquierdo, k, salida);
    }

    /** Verifica el orden de claves y el balance en todo el árbol (se usa en las pruebas). */
    public boolean esAvlValido() {
        return validar(raiz, null, null) >= 0;
    }

    private int validar(Nodo<K, V> n, K minimo, K maximo) {
        if (n == null) return 0;
        if (minimo != null && n.clave.compareTo(minimo) <= 0) return -1;
        if (maximo != null && n.clave.compareTo(maximo) >= 0) return -1;
        int hi = validar(n.izquierdo, minimo, n.clave), hd = validar(n.derecho, n.clave, maximo);
        if (hi < 0 || hd < 0 || Math.abs(hi - hd) > 1 || n.altura != 1 + Math.max(hi, hd)) return -1;
        return n.altura;
    }

    private int altura(Nodo<K, V> n) {
        return n == null ? 0 : n.altura;
    }

    private void actualizar(Nodo<K, V> n) {
        n.altura = 1 + Math.max(altura(n.izquierdo), altura(n.derecho));
    }

    private Nodo<K, V> rotarDerecha(Nodo<K, V> y) {
        Nodo<K, V> x = y.izquierdo;
        y.izquierdo = x.derecho;
        x.derecho = y;
        actualizar(y);
        actualizar(x);
        return x;
    }

    private Nodo<K, V> rotarIzquierda(Nodo<K, V> x) {
        Nodo<K, V> y = x.derecho;
        x.derecho = y.izquierdo;
        y.izquierdo = x;
        actualizar(x);
        actualizar(y);
        return y;
    }

    private Nodo<K, V> balancear(Nodo<K, V> n) {
        actualizar(n);
        int balance = altura(n.izquierdo) - altura(n.derecho);
        if (balance > 1) { // pesado a la izquierda
            if (altura(n.izquierdo.izquierdo) < altura(n.izquierdo.derecho)) n.izquierdo = rotarIzquierda(n.izquierdo);
            return rotarDerecha(n);
        }
        if (balance < -1) { // pesado a la derecha
            if (altura(n.derecho.derecho) < altura(n.derecho.izquierdo)) n.derecho = rotarDerecha(n.derecho);
            return rotarIzquierda(n);
        }
        return n;
    }
}
