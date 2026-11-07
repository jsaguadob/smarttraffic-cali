package smarttraffic.sim;

import smarttraffic.estructuras.ColaEnlazada;
import smarttraffic.estructuras.ListaEnlazada;
import smarttraffic.red.Via;

/**
 * Estado de tráfico de una vía. Los vehículos particulares están en una cola FIFO ordenada por
 * orden de entrada (el del frente es el más adelantado); los vehículos de emergencia van en una
 * lista aparte porque pueden adelantar.
 */
public final class EstadoVia {
    final Via via;
    final int indice;
    /** Grupo de semáforo de esta aproximación: 0 si va más bien en horizontal, 1 si va más bien en vertical. */
    final int grupo;
    final ColaEnlazada<Vehiculo> cola = new ColaEnlazada<>();
    final ListaEnlazada<Vehiculo> emergencias = new ListaEnlazada<>();
    Incidente incidente;
    double velocidadMedia;
    double ocupacion;
    double puntaje;
    int nivel;
    long salidas;

    EstadoVia(Via via, int indice, int grupo) {
        this.via = via;
        this.indice = indice;
        this.grupo = grupo;
        this.velocidadMedia = via.velocidadMaxMs();
    }

    public Via via() {
        return via;
    }

    public boolean bloqueada() {
        return incidente != null;
    }

    public int vehiculos() {
        return cola.tamano() + emergencias.tamano();
    }

    /** 0 = fluido, 1 = moderado, 2 = alto, 3 = crítico. */
    public int nivel() {
        return nivel;
    }

    public double ocupacion() {
        return ocupacion;
    }

    public double velocidadMedia() {
        return velocidadMedia;
    }

    public double puntaje() {
        return puntaje;
    }
}
