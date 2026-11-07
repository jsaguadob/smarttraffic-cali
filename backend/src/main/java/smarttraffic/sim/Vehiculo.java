package smarttraffic.sim;

import smarttraffic.red.Via;

/** Un vehículo de la simulación. La posición es la del parachoques delantero, en metros desde el inicio de la vía actual. */
public final class Vehiculo {
    public enum Estado { ESPERANDO_ENTRADA, EN_VIA, ATENDIENDO, FINALIZADO }

    final int numero;
    final String id;
    final TipoVehiculo tipo;
    final String origenId;
    final String destinoId;
    final double tSolicitud;

    Via[] ruta;
    int indiceVia = -1;
    double posicion;
    double velocidad;
    Estado estado = Estado.ESPERANDO_ENTRADA;
    double tEntrada = -1;
    double tSalida = -1;
    double esperaTotal;
    double tiempoDetenido;
    double distancia;
    int ultimoPaso = -1;
    int reruteos;
    double ultimoReruteo = -1e9;

    // Solo para vehículos de emergencia
    String incidenteId;
    double tDespacho;

    Vehiculo(int numero, TipoVehiculo tipo, String origenId, String destinoId, Via[] ruta, double tSolicitud) {
        this.numero = numero;
        this.id = (tipo.esEmergencia() ? "EMG-" : "VEH-") + String.format("%04d", numero);
        this.tipo = tipo;
        this.origenId = origenId;
        this.destinoId = destinoId;
        this.ruta = ruta;
        this.tSolicitud = tSolicitud;
    }

    public String id() {
        return id;
    }

    public TipoVehiculo tipo() {
        return tipo;
    }

    public double posicion() {
        return posicion;
    }

    public double velocidad() {
        return velocidad;
    }

    public Estado estado() {
        return estado;
    }

    public Via viaActual() {
        return indiceVia >= 0 && indiceVia < ruta.length ? ruta[indiceVia] : null;
    }

    boolean esEmergencia() {
        return tipo.esEmergencia();
    }

    boolean enUltimaVia() {
        return indiceVia == ruta.length - 1;
    }
}
