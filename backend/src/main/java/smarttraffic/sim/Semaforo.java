package smarttraffic.sim;

import smarttraffic.red.Interseccion;

/**
 * Semáforo de dos fases: el grupo 0 controla las aproximaciones más horizontales y el grupo 1 las
 * más verticales. Entre una fase verde y la siguiente siempre hay amarillo y un instante con todo
 * en rojo, de modo que nunca se habilitan dos grupos que se cruzan al mismo tiempo.
 */
public final class Semaforo {
    public enum Fase { VERDE_0, AMARILLO_0, ROJO_0, VERDE_1, AMARILLO_1, ROJO_1 }

    public enum Luz { VERDE, AMARILLO, ROJO }

    final Interseccion nodo;
    /** Falso si todas las vías que llegan son del mismo grupo: no hay cruce que controlar. */
    final boolean activo;
    Fase fase = Fase.VERDE_0;
    double tFase;
    double verdeObjetivo;
    /** Grupo que el operador obliga a mantener en verde (-1 si no hay control manual). */
    int manualGrupo = -1;
    int cambios;

    Semaforo(Interseccion nodo, boolean activo) {
        this.nodo = nodo;
        this.activo = activo;
    }

    public Interseccion nodo() {
        return nodo;
    }

    public Fase fase() {
        return fase;
    }

    public double tiempoEnFase() {
        return tFase;
    }

    public boolean esActivo() {
        return activo;
    }

    public int cambios() {
        return cambios;
    }

    public Luz luzPara(int grupo) {
        if (!activo) return Luz.VERDE;
        return switch (fase) {
            case VERDE_0 -> grupo == 0 ? Luz.VERDE : Luz.ROJO;
            case AMARILLO_0 -> grupo == 0 ? Luz.AMARILLO : Luz.ROJO;
            case VERDE_1 -> grupo == 1 ? Luz.VERDE : Luz.ROJO;
            case AMARILLO_1 -> grupo == 1 ? Luz.AMARILLO : Luz.ROJO;
            default -> Luz.ROJO;
        };
    }

    /** Grupo que tiene luz verde ahora, o -1 si ninguno. */
    public int grupoVerde() {
        return fase == Fase.VERDE_0 ? 0 : fase == Fase.VERDE_1 ? 1 : -1;
    }

    /** Grupo que está en la parte verde o amarilla de su fase, o -1 durante el rojo total. */
    int grupoDeLaFase() {
        return switch (fase) {
            case VERDE_0, AMARILLO_0 -> 0;
            case VERDE_1, AMARILLO_1 -> 1;
            default -> -1;
        };
    }
}
