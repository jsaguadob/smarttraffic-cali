package smarttraffic.estructuras;

import java.util.Arrays;
import java.util.Comparator;
import java.util.NoSuchElementException;

/**
 * Cola de prioridad implementada como montículo binario mínimo (min-heap) sobre un arreglo.
 *
 * <p>Uso en SmartTraffic: (1) conjunto abierto de Dijkstra y A*, (2) atención de incidentes por
 * gravedad, (3) solicitudes de emergencia (ambulancia antes que bomberos antes que policía) y
 * (4) agenda de llegada de vehículos por tiempo.
 *
 * <p>Complejidad: insertar y extraer el mínimo O(log n); consultar el mínimo O(1).
 * Para desempates deterministas, el comparador debe incluir un criterio final (p. ej. un
 * consecutivo).
 */
public class ColaPrioridad<T> {

    private Object[] monticulo;
    private int tamano;
    private final Comparator<? super T> comparador;

    public ColaPrioridad(Comparator<? super T> comparador) {
        this.comparador = comparador;
        this.monticulo = new Object[16];
    }

    public int tamano() {
        return tamano;
    }

    public boolean estaVacia() {
        return tamano == 0;
    }

    @SuppressWarnings("unchecked")
    private T en(int i) {
        return (T) monticulo[i];
    }

    /** Inserta un elemento. O(log n). */
    public void insertar(T elemento) {
        if (tamano == monticulo.length) monticulo = Arrays.copyOf(monticulo, tamano * 2);
        monticulo[tamano] = elemento;
        subir(tamano);
        tamano++;
    }

    /** Elemento de mayor prioridad (el menor según el comparador) sin quitarlo. O(1). */
    public T minimo() {
        if (tamano == 0) throw new NoSuchElementException("La cola de prioridad está vacía");
        return en(0);
    }

    /** Quita y devuelve el elemento de mayor prioridad. O(log n). */
    public T extraerMinimo() {
        if (tamano == 0) throw new NoSuchElementException("La cola de prioridad está vacía");
        T raiz = en(0);
        tamano--;
        monticulo[0] = monticulo[tamano];
        monticulo[tamano] = null;
        if (tamano > 0) bajar(0);
        return raiz;
    }

    public void limpiar() {
        Arrays.fill(monticulo, 0, tamano, null);
        tamano = 0;
    }

    private void subir(int i) {
        while (i > 0) {
            int padre = (i - 1) / 2;
            if (comparador.compare(en(i), en(padre)) >= 0) break;
            intercambiar(i, padre);
            i = padre;
        }
    }

    private void bajar(int i) {
        while (true) {
            int izq = 2 * i + 1, der = izq + 1, menor = i;
            if (izq < tamano && comparador.compare(en(izq), en(menor)) < 0) menor = izq;
            if (der < tamano && comparador.compare(en(der), en(menor)) < 0) menor = der;
            if (menor == i) break;
            intercambiar(i, menor);
            i = menor;
        }
    }

    private void intercambiar(int a, int b) {
        Object t = monticulo[a];
        monticulo[a] = monticulo[b];
        monticulo[b] = t;
    }
}
