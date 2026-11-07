package smarttraffic.sim;

/** Accidente o cierre de una vía. Mientras está activo, la vía queda bloqueada a partir de su posición. */
public final class Incidente {
    public enum Estado { ACTIVO, EN_ATENCION, RESUELTO }

    final String id;
    final int consecutivo;
    final String viaId;
    /** Metros desde el inicio de la vía. */
    final double posicion;
    /** 1 = leve, 2 = moderado, 3 = grave. */
    final int gravedad;
    final double tInicio;
    Estado estado = Estado.ACTIVO;
    double restante;
    double tResuelto = -1;

    Incidente(int consecutivo, String viaId, double posicion, int gravedad, double tInicio) {
        this.consecutivo = consecutivo;
        this.id = "INC-" + String.format("%03d", consecutivo);
        this.viaId = viaId;
        this.posicion = posicion;
        this.gravedad = gravedad;
        this.tInicio = tInicio;
        this.restante = duracion(gravedad);
    }

    /** Segundos que tardaría en despejarse solo. */
    static double duracion(int gravedad) {
        return switch (gravedad) {
            case 1 -> 45;
            case 2 -> 90;
            default -> 150;
        };
    }

    /** Segundos que tarda en despejarse una vez que llega el equipo de emergencia. */
    static double tiempoServicio(int gravedad) {
        return switch (gravedad) {
            case 1 -> 10;
            case 2 -> 20;
            default -> 30;
        };
    }

    public String id() {
        return id;
    }

    public String viaId() {
        return viaId;
    }

    public int gravedad() {
        return gravedad;
    }

    public Estado estado() {
        return estado;
    }

    public double posicion() {
        return posicion;
    }

    public double restante() {
        return restante;
    }

    public double tInicio() {
        return tInicio;
    }

    public boolean abierto() {
        return estado != Estado.RESUELTO;
    }
}
