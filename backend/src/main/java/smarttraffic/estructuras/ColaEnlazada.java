package smarttraffic.estructuras;

import java.util.Iterator;

/**
 * Cola FIFO (el primero en entrar es el primero en salir).
 *
 * <p>Uso en SmartTraffic: cada vía mantiene la cola de los vehículos que la recorren, en el
 * orden en que entraron; el vehículo del frente es el que llega primero a la intersección. También
 * se usa para la fila de demanda que espera para entrar a la red.
 *
 * <p>Complejidad: encolar, desencolar y consultar el frente O(1).
 */
public class ColaEnlazada<T> implements Iterable<T> {

    private final ListaEnlazada<T> elementos = new ListaEnlazada<>();

    /** Agrega al final de la cola. O(1). */
    public void encolar(T dato) {
        elementos.agregarFinal(dato);
    }

    /** Quita y devuelve el elemento del frente. O(1). */
    public T desencolar() {
        return elementos.quitarInicio();
    }

    /** Elemento del frente (el más antiguo) sin quitarlo. O(1). */
    public T frente() {
        return elementos.primero();
    }

    /** Último elemento en llegar sin quitarlo. O(1). */
    public T ultimo() {
        return elementos.ultimo();
    }

    public int tamano() {
        return elementos.tamano();
    }

    public boolean estaVacia() {
        return elementos.estaVacia();
    }

    /** Saca un elemento que no está en el frente (p. ej. un vehículo que sale de una vía). O(n). */
    public boolean eliminar(T dato) {
        return elementos.eliminar(dato);
    }

    /** Recorre del frente hacia el final. */
    @Override
    public Iterator<T> iterator() {
        return elementos.iterator();
    }
}
