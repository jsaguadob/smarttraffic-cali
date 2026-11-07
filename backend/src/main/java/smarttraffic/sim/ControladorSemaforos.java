package smarttraffic.sim;

import smarttraffic.estructuras.Grafo;
import smarttraffic.red.Via;
import smarttraffic.sim.Semaforo.Fase;

/**
 * Lógica de control de los semáforos.
 *
 * <p>Modo FIJO: cada grupo tiene el mismo tiempo de verde en cada ciclo.
 *
 * <p>Modo INTELIGENTE: para cada grupo se calcula una puntuación de congestión
 * {@code P = pesoV·V + pesoT·T}, donde V es la cantidad normalizada de vehículos esperando cerca de
 * la línea de pare y T es el tiempo normalizado que lleva esperando el primero. El verde del grupo
 * se amplía entre {@code verdeMin} y {@code verdeMax} según P; si no queda nadie esperando y el otro
 * sentido sí tiene demanda, se cede el paso antes. Siempre se respetan el amarillo y el rojo total.
 * Si una emergencia se acerca, su sentido recibe verde.
 */
final class ControladorSemaforos {
    private final Simulacion sim;
    private final Config cfg;

    ControladorSemaforos(Simulacion sim, Config cfg) {
        this.sim = sim;
        this.cfg = cfg;
    }

    private boolean inteligente() {
        return cfg.modoSemaforos == ModoSemaforos.INTELIGENTE;
    }

    void actualizar(Semaforo s, double dt) {
        if (!s.activo) return;
        s.tFase += dt;
        switch (s.fase) {
            case VERDE_0, VERDE_1 -> actualizarVerde(s, s.fase == Fase.VERDE_0 ? 0 : 1);
            case AMARILLO_0 -> { if (s.tFase >= cfg.amarillo) pasarA(s, Fase.ROJO_0); }
            case AMARILLO_1 -> { if (s.tFase >= cfg.amarillo) pasarA(s, Fase.ROJO_1); }
            case ROJO_0 -> { if (s.tFase >= cfg.todoRojo) iniciarVerde(s, siguienteGrupo(s, 0)); }
            case ROJO_1 -> { if (s.tFase >= cfg.todoRojo) iniciarVerde(s, siguienteGrupo(s, 1)); }
        }
    }

    private int siguienteGrupo(Semaforo s, int terminado) {
        int otro = 1 - terminado;
        if (s.manualGrupo >= 0) return s.manualGrupo;
        // Una emergencia que acaba de quedar en rojo recupera el verde en lugar de esperar a todo el otro sentido.
        if (inteligente() && emergenciaAproximando(s, terminado) && !emergenciaAproximando(s, otro)) return terminado;
        return otro;
    }

    private void actualizarVerde(Semaforo s, int g) {
        int otro = 1 - g;
        if (s.manualGrupo >= 0) {
            if (s.manualGrupo != g && s.tFase >= 2) terminarVerde(s, g);
            return; // el operador lo mantiene en verde
        }
        if (!inteligente()) {
            if (s.tFase >= cfg.verdeFijo) terminarVerde(s, g);
            return;
        }
        if (emergenciaAproximando(s, g)) return; // se mantiene el verde mientras pasa la emergencia
        if (emergenciaAproximando(s, otro) && s.tFase >= 2) { terminarVerde(s, g); return; }
        double pc = presion(s, g), po = presion(s, otro);
        s.verdeObjetivo = Math.max(s.verdeObjetivo, cfg.verdeMin + (cfg.verdeMax - cfg.verdeMin) * pc);
        if (po <= 0) return; // nadie espera en el otro sentido: se queda en verde
        boolean sinDemandaActual = pc <= 0 && s.tFase >= 5;
        if (s.tFase >= s.verdeObjetivo || sinDemandaActual || s.tFase >= cfg.verdeMax) terminarVerde(s, g);
    }

    private void terminarVerde(Semaforo s, int g) {
        pasarA(s, g == 0 ? Fase.AMARILLO_0 : Fase.AMARILLO_1);
    }

    private void pasarA(Semaforo s, Fase f) {
        s.fase = f;
        s.tFase = 0;
    }

    void iniciarVerde(Semaforo s, int grupo) {
        s.fase = grupo == 0 ? Fase.VERDE_0 : Fase.VERDE_1;
        s.tFase = 0;
        s.cambios++;
        s.verdeObjetivo = inteligente() ? cfg.verdeMin + (cfg.verdeMax - cfg.verdeMin) * presion(s, grupo) : cfg.verdeFijo;
    }

    /** Puntuación de congestión P = pesoV·V + pesoT·T del grupo, entre 0 y 1. */
    double presion(Semaforo s, int grupo) {
        double cola = 0, maxEspera = 0;
        for (Grafo.Arista<Via> a : sim.red().grafo().entrantes(s.nodo.id())) {
            EstadoVia ev = sim.estadoVia(a.id());
            if (ev.grupo != grupo || ev.bloqueada()) continue;
            double largo = ev.via.longitud();
            for (Vehiculo v : ev.cola) { // del frente hacia atrás: cada vez más lejos de la línea de pare
                if (largo - v.posicion > cfg.distanciaCola) break;
                cola += v.velocidad < 3 ? 1.0 : 0.4;
                maxEspera = Math.max(maxEspera, v.tiempoDetenido);
            }
        }
        double v = Math.min(1, cola / cfg.capacidadCola), t = Math.min(1, maxEspera / cfg.esperaReferencia);
        return cfg.pesoV * v + cfg.pesoT * t;
    }

    boolean emergenciaAproximando(Semaforo s, int grupo) {
        for (Grafo.Arista<Via> a : sim.red().grafo().entrantes(s.nodo.id())) {
            EstadoVia ev = sim.estadoVia(a.id());
            if (ev.grupo != grupo) continue;
            for (Vehiculo e : ev.emergencias) {
                if (e.estado == Vehiculo.Estado.EN_VIA && ev.via.longitud() - e.posicion <= cfg.radioEmergencia && !e.enUltimaVia()) return true;
            }
        }
        return false;
    }
}
