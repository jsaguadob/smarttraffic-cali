package smarttraffic.sim;

import smarttraffic.estructuras.ListaEnlazada;

/** Indicadores acumulados y serie de tiempo de una simulación. */
public final class Estadisticas {

    /** Una fila de la serie de tiempo (se toma una cada 5 segundos simulados). */
    public record Muestra(double t, int activos, int enEspera, double velocidadMediaKmh, int viasCongestionadas, int completados,
                          double tiempoViajeMedio, double esperaMedia, int emergenciasActivas) {}

    private static final int MAX_MUESTRAS = 2000;

    int generados;
    int completados;
    double sumaViaje;
    double sumaEspera;
    double sumaDistancia;
    int emergenciasAtendidas;
    double sumaRespuesta;
    int reruteos;
    final ListaEnlazada<Muestra> serie = new ListaEnlazada<>();

    void registrarFinal(Vehiculo v) {
        completados++;
        sumaViaje += v.tSalida - v.tSolicitud;
        sumaEspera += v.esperaTotal;
        sumaDistancia += v.distancia;
    }

    void registrarMuestra(Muestra m) {
        serie.agregarFinal(m);
        if (serie.tamano() > MAX_MUESTRAS) serie.quitarInicio();
    }

    public int generados() {
        return generados;
    }

    public int completados() {
        return completados;
    }

    /** Tiempo medio de viaje (de que el vehículo pide entrar hasta que sale), en segundos. */
    public double tiempoViajeMedio() {
        return completados == 0 ? 0 : sumaViaje / completados;
    }

    /** Tiempo medio detenido (en espera de entrar o con velocidad casi nula), en segundos por viaje. */
    public double esperaMedia() {
        return completados == 0 ? 0 : sumaEspera / completados;
    }

    public int emergenciasAtendidas() {
        return emergenciasAtendidas;
    }

    /** Tiempo medio que tarda una emergencia desde que sale hasta que llega al sitio, en segundos. */
    public double respuestaMedia() {
        return emergenciasAtendidas == 0 ? 0 : sumaRespuesta / emergenciasAtendidas;
    }

    public int reruteos() {
        return reruteos;
    }

    public ListaEnlazada<Muestra> serie() {
        return serie;
    }
}
