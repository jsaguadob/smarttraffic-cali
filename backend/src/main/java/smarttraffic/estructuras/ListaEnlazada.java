package smarttraffic.estructuras;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Lista doblemente enlazada.
 *
 * <p>Uso en SmartTraffic: historial de eventos de una simulación, vehículos de emergencia que
 * circulan por una vía y rutas calculadas.
 *
 * <p>Complejidad: agregar/quitar en los extremos O(1); buscar o eliminar por valor O(n);
 * obtener por posición O(n).
 */
public class ListaEnlazada<T> implements Iterable<T> {

    private static final class Nodo<T> {
        T dato;
        Nodo<T> anterior;
        Nodo<T> siguiente;

        Nodo(T dato) {
            this.dato = dato;
        }
    }

    private Nodo<T> cabeza;
    private Nodo<T> cola;
    private int tamano;

    public int tamano() {
        return tamano;
    }

    public boolean estaVacia() {
        return tamano == 0;
    }

    /** Inserta al final. O(1). */
    public void agregarFinal(T dato) {
        Nodo<T> nodo = new Nodo<>(dato);
        if (cola == null) {
            cabeza = cola = nodo;
        } else {
            nodo.anterior = cola;
            cola.siguiente = nodo;
            cola = nodo;
        }
        tamano++;
    }

    /** Inserta al inicio. O(1). */
    public void agregarInicio(T dato) {
        Nodo<T> nodo = new Nodo<>(dato);
        if (cabeza == null) {
            cabeza = cola = nodo;
        } else {
            nodo.siguiente = cabeza;
            cabeza.anterior = nodo;
            cabeza = nodo;
        }
        tamano++;
    }

    /** Quita y devuelve el primer elemento. O(1). */
    public T quitarInicio() {
        if (cabeza == null) throw new NoSuchElementException("La lista está vacía");
        T dato = cabeza.dato;
        desenlazar(cabeza);
        return dato;
    }

    /** Quita y devuelve el último elemento. O(1). */
    public T quitarFinal() {
        if (cola == null) throw new NoSuchElementException("La lista está vacía");
        T dato = cola.dato;
        desenlazar(cola);
        return dato;
    }

    public T primero() {
        if (cabeza == null) throw new NoSuchElementException("La lista está vacía");
        return cabeza.dato;
    }

    public T ultimo() {
        if (cola == null) throw new NoSuchElementException("La lista está vacía");
        return cola.dato;
    }

    /** Devuelve el elemento en la posición dada. O(n). */
    public T obtener(int indice) {
        if (indice < 0 || indice >= tamano) throw new IndexOutOfBoundsException("Índice " + indice);
        Nodo<T> actual = cabeza;
        for (int i = 0; i < indice; i++) actual = actual.siguiente;
        return actual.dato;
    }

    /** Elimina la primera aparición del valor. O(n). */
    public boolean eliminar(T dato) {
        for (Nodo<T> n = cabeza; n != null; n = n.siguiente) {
            if (Objects.equals(n.dato, dato)) {
                desenlazar(n);
                return true;
            }
        }
        return false;
    }

    public boolean contiene(T dato) {
        for (Nodo<T> n = cabeza; n != null; n = n.siguiente) {
            if (Objects.equals(n.dato, dato)) return true;
        }
        return false;
    }

    public void limpiar() {
        cabeza = cola = null;
        tamano = 0;
    }

    /** Copia los elementos a una lista de la biblioteca estándar (útil para serializar). */
    public List<T> aLista() {
        List<T> copia = new ArrayList<>(tamano);
        for (T t : this) copia.add(t);
        return copia;
    }

    private void desenlazar(Nodo<T> n) {
        if (n.anterior != null) n.anterior.siguiente = n.siguiente; else cabeza = n.siguiente;
        if (n.siguiente != null) n.siguiente.anterior = n.anterior; else cola = n.anterior;
        n.anterior = n.siguiente = null;
        tamano--;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private Nodo<T> siguiente = cabeza;
            private Nodo<T> ultimoDevuelto;

            @Override
            public boolean hasNext() {
                return siguiente != null;
            }

            @Override
            public T next() {
                if (siguiente == null) throw new NoSuchElementException();
                ultimoDevuelto = siguiente;
                siguiente = siguiente.siguiente;
                return ultimoDevuelto.dato;
            }

            @Override
            public void remove() {
                if (ultimoDevuelto == null) throw new IllegalStateException();
                desenlazar(ultimoDevuelto);
                ultimoDevuelto = null;
            }
        };
    }
}
