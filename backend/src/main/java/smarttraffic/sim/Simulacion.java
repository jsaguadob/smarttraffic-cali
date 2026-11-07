package smarttraffic.sim;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import smarttraffic.estructuras.ArbolAvl;
import smarttraffic.estructuras.ColaEnlazada;
import smarttraffic.estructuras.ColaPrioridad;
import smarttraffic.estructuras.Grafo;
import smarttraffic.estructuras.ListaEnlazada;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.ruta.AEstrella;
import smarttraffic.ruta.FuncionCosto;
import smarttraffic.ruta.Ruta;
import smarttraffic.sim.Semaforo.Luz;
import smarttraffic.util.Json;

/**
 * Motor de estimación del flujo vehicular sobre una red vial (real o de demostración).
 *
 * <p>Cada vía lleva una cola FIFO con sus vehículos (el del frente es el más adelantado) y cada
 * vehículo sigue al que va delante sin chocarlo. Los semáforos se coordinan por fases, los incidentes
 * bloquean vías, los vehículos de emergencia salen según una cola de prioridad y las rutas se calculan
 * con A*. Con la misma semilla, la misma red y los mismos comandos el resultado es idéntico.
 *
 * <p>No es un modelo calibrado con mediciones reales: estima el comportamiento para apoyar decisiones
 * y demostrar los algoritmos. Todos los métodos deben llamarse desde un solo hilo.
 */
public final class Simulacion {
    static final double MIN_DISTANCIA = 2.0;
    static final double MARGEN_INCIDENTE = 6.0;
    static final double MARGEN_PARE = 1.0;
    private static final int MAX_EVENTOS = 200;

    private record Solicitud(TipoVehiculo tipo, String incidenteId, String destinoNodo, int gravedad, int orden, double t) {}

    private final RedVial red;
    private final Config cfg;
    private final Random rngDemanda;
    private final Random rngManual;
    private final ControladorSemaforos controlador;

    private double t;
    private int paso;
    private int siguienteNumero = 1;
    private double proximaLlegada;
    private double ultimoMuestreo = -1e9;
    private double ultimaActualizacion = -1e9;
    private double ultimoDespacho = -1e9;
    private int ordenSolicitud;

    private final EstadoVia[] viasPorIndice;
    private final TablaHash<String, EstadoVia> estados = new TablaHash<>();
    private final Semaforo[] semaforosPorNodo;
    private final TablaHash<String, Integer> indiceNodo = new TablaHash<>();
    private final TablaHash<String, Semaforo> semaforos = new TablaHash<>();
    private final ListaEnlazada<Semaforo> listaSemaforos = new ListaEnlazada<>();
    private final List<Interseccion> nodos;
    private final List<Interseccion> entradas = new ArrayList<>();
    private final TablaHash<String, Vehiculo> activos = new TablaHash<>();
    private final TablaHash<String, ColaEnlazada<Vehiculo>> esperaEntrada = new TablaHash<>();
    private final ColaPrioridad<Solicitud> solicitudes = new ColaPrioridad<>(
            Comparator.comparingInt((Solicitud s) -> s.tipo().prioridad()).thenComparing((a, b) -> Integer.compare(b.gravedad(), a.gravedad())).thenComparingInt(Solicitud::orden));
    private final int[] flota = new int[TipoVehiculo.values().length];
    private final GestorIncidentes incidentes = new GestorIncidentes();
    private final Estadisticas stats = new Estadisticas();
    private final ListaEnlazada<Evento> eventos = new ListaEnlazada<>();
    private final ArbolAvl<ClaveRanking, EstadoVia> ranking = new ArbolAvl<>();
    private final TablaHash<Integer, ClaveRanking> claveActual = new TablaHash<>();
    private int viasCongestionadas;
    private double velocidadMediaKmh;
    private int emergenciasActivas;

    public Simulacion(RedVial red, Config cfg) {
        this.red = red;
        this.cfg = cfg;
        this.rngDemanda = new Random(cfg.semilla);
        this.rngManual = new Random(cfg.semilla * 31 + 7);
        this.controlador = new ControladorSemaforos(this, cfg);
        List<String> problemas = red.validarParaSimulacion();
        if (!problemas.isEmpty()) throw new IllegalArgumentException("La red no se puede simular: " + problemas.get(0));

        nodos = red.intersecciones();
        List<Via> vias = red.vias();
        viasPorIndice = new EstadoVia[vias.size()];
        for (int i = 0; i < vias.size(); i++) {
            Via v = vias.get(i);
            double[] d = v.direccionFinal();
            EstadoVia ev = new EstadoVia(v, i, Math.abs(d[0]) >= Math.abs(d[1]) ? 0 : 1);
            viasPorIndice[i] = ev;
            estados.poner(v.id(), ev);
        }
        semaforosPorNodo = new Semaforo[nodos.size()];
        double ciclo = 2 * (cfg.verdeFijo + cfg.amarillo + cfg.todoRojo);
        for (int i = 0; i < nodos.size(); i++) {
            Interseccion n = nodos.get(i);
            indiceNodo.poner(n.id(), i);
            if (n.tipo() == TipoNodo.ENTRADA_SALIDA) {
                entradas.add(n);
                esperaEntrada.poner(n.id(), new ColaEnlazada<>());
            }
            boolean g0 = false, g1 = false;
            for (Grafo.Arista<Via> a : red.grafo().entrantes(n.id())) {
                if (estados.obtener(a.id()).grupo == 0) g0 = true; else g1 = true;
            }
            Semaforo s = new Semaforo(n, n.semaforizada() && g0 && g1);
            posicionarEnCiclo(s, (i * 7.3) % ciclo); // los semáforos no arrancan todos a la vez
            semaforosPorNodo[i] = s;
            semaforos.poner(n.id(), s);
            listaSemaforos.agregarFinal(s);
        }
        if (entradas.size() < 2) entradas.addAll(nodos);
        proximaLlegada = exponencial(rngDemanda);
    }

    // ================================================================= acceso

    public RedVial red() {
        return red;
    }

    public Config config() {
        return cfg;
    }

    public double tiempo() {
        return t;
    }

    public EstadoVia estadoVia(String idVia) {
        return estados.obtener(idVia);
    }

    EstadoVia[] vias() {
        return viasPorIndice;
    }

    public Semaforo semaforo(String idNodo) {
        return semaforos.obtener(idNodo);
    }

    public ListaEnlazada<Semaforo> semaforos() {
        return listaSemaforos;
    }

    public GestorIncidentes incidentes() {
        return incidentes;
    }

    public Estadisticas estadisticas() {
        return stats;
    }

    public int vehiculosActivos() {
        return activos.tamano();
    }

    public int vehiculosEnEspera() {
        int n = 0;
        for (Interseccion e : entradas) n += esperaEntrada.obtenerODefecto(e.id(), new ColaEnlazada<>()).tamano();
        return n;
    }

    /** Vehículos particulares que están circulando por alguna vía. */
    public int particularesEnVia() {
        int n = 0;
        for (EstadoVia ev : viasPorIndice) n += ev.cola.tamano();
        return n;
    }

    /**
     * Tiempo medio de viaje contando también a quienes todavía no terminan (con lo que llevan hasta ahora).
     * Evita que un escenario con más gente atascada parezca mejor solo porque termina menos viajes.
     */
    public double viajeMedioAjustado() {
        if (stats.generados == 0) return 0;
        double suma = stats.sumaViaje;
        for (EstadoVia ev : viasPorIndice) for (Vehiculo v : ev.cola) suma += t - v.tSolicitud;
        for (Interseccion e : entradas) for (Vehiculo v : esperaEntrada.obtener(e.id())) suma += t - v.tSolicitud;
        return suma / stats.generados;
    }

    public int viasCongestionadas() {
        return viasCongestionadas;
    }

    public double velocidadMediaKmh() {
        return velocidadMediaKmh;
    }

    public List<Evento> ultimosEventos(int n) {
        List<Evento> lista = eventos.aLista();
        return lista.subList(Math.max(0, lista.size() - n), lista.size());
    }

    /** Las n vías con mayor puntaje de congestión (usa el árbol AVL del ranking). */
    public List<EstadoVia> masCongestionadas(int n) {
        return ranking.mayores(n);
    }

    // ================================================================= avance del tiempo

    public void avanzar(double segundos) {
        long pasos = Math.round(segundos / cfg.dt);
        for (long i = 0; i < pasos; i++) paso();
    }

    public void paso() {
        paso++;
        t += cfg.dt;
        generarLlegadas();
        List<Incidente> resueltos = incidentes.avanzar(cfg.dt, t);
        for (Incidente i : resueltos) liberarIncidente(i, "se despejó solo");
        if (t - ultimoDespacho >= 1.0) { ultimoDespacho = t; procesarSolicitudes(); }
        for (Semaforo s : listaSemaforos) controlador.actualizar(s, cfg.dt);
        insertarEntradas();
        for (EstadoVia ev : viasPorIndice) {
            moverCola(ev);
            moverEmergencias(ev);
        }
        if (t - ultimaActualizacion >= 1.0) { ultimaActualizacion = t; actualizarTrafico(); }
        if (t - ultimoMuestreo >= 5.0) { ultimoMuestreo = t; muestrear(); }
    }

    // ================================================================= demanda

    private double exponencial(Random r) {
        double lambda = cfg.vehiculosPorMinuto / 60.0;
        if (lambda <= 0) return 1.0;
        return -Math.log(1 - r.nextDouble()) / lambda;
    }

    private void generarLlegadas() {
        while (proximaLlegada <= t) {
            Interseccion o = entradas.get(rngDemanda.nextInt(entradas.size()));
            int k = rngDemanda.nextInt(entradas.size() - 1);
            Interseccion d = entradas.get(k >= entradas.indexOf(o) ? k + 1 : k);
            double u = rngDemanda.nextDouble();
            TipoVehiculo tipo = u < cfg.probMoto ? TipoVehiculo.MOTO : u < cfg.probMoto + cfg.probBus ? TipoVehiculo.BUS : TipoVehiculo.AUTO;
            nuevaDemanda(o, d, tipo);
            proximaLlegada += exponencial(rngDemanda);
        }
    }

    private void nuevaDemanda(Interseccion origen, Interseccion destino, TipoVehiculo tipo) {
        Vehiculo v = new Vehiculo(siguienteNumero++, tipo, origen.id(), destino.id(), null, t);
        esperaEntrada.obtener(origen.id()).encolar(v);
        stats.generados++;
    }

    /** Agrega vehículos de inmediato en entradas al azar (no altera la secuencia de demanda programada). */
    public int agregarVehiculos(int cantidad) {
        int n = Math.max(0, Math.min(cantidad, 500));
        for (int i = 0; i < n; i++) {
            Interseccion o = entradas.get(rngManual.nextInt(entradas.size()));
            int k = rngManual.nextInt(entradas.size() - 1);
            Interseccion d = entradas.get(k >= entradas.indexOf(o) ? k + 1 : k);
            nuevaDemanda(o, d, rngManual.nextDouble() < 0.12 ? TipoVehiculo.MOTO : rngManual.nextDouble() < 0.1 ? TipoVehiculo.BUS : TipoVehiculo.AUTO);
        }
        if (n > 0) evento("trafico", "Se agregaron " + n + " vehículos a la red");
        return n;
    }

    private void insertarEntradas() {
        for (Interseccion o : entradas) {
            ColaEnlazada<Vehiculo> fila = esperaEntrada.obtener(o.id());
            if (fila.estaVacia()) continue;
            Vehiculo v = fila.frente();
            if (v.ruta == null) {
                Ruta r = AEstrella.buscar(red, v.origenId, v.destinoId, this::costoLibre);
                if (r == null || r.vias().length == 0) continue; // todavía no hay camino (cierres): sigue esperando
                v.ruta = r.vias();
            }
            EstadoVia ev = viasPorIndice[indiceVia(v.ruta[0])];
            double frente = v.tipo.largo() + 0.1;
            boolean hayEspacio = ev.cola.estaVacia() || ev.cola.ultimo().posicion - ev.cola.ultimo().tipo.largo() - MIN_DISTANCIA >= frente;
            if (ev.bloqueada() && ev.incidente.posicion < frente + 12) hayEspacio = false;
            if (!hayEspacio) continue;
            fila.desencolar();
            v.estado = Vehiculo.Estado.EN_VIA;
            v.indiceVia = 0;
            v.posicion = frente;
            v.velocidad = Math.min(ev.via.velocidadMaxMs() * 0.5, 6);
            v.tEntrada = t;
            v.ultimoPaso = paso;
            ev.cola.encolar(v);
            activos.poner(v.id, v);
        }
    }

    private int indiceVia(Via via) {
        return estados.obtener(via.id()).indice;
    }

    // ================================================================= costos y rutas

    private double costoLibre(Via via) {
        return estados.obtener(via.id()).bloqueada() ? Double.POSITIVE_INFINITY : via.tiempoLibre();
    }

    private double costoDinamico(Via via) {
        EstadoVia ev = estados.obtener(via.id());
        if (ev.bloqueada()) return Double.POSITIVE_INFINITY;
        double v = Math.max(2.0, Math.min(via.velocidadMaxMs(), ev.velocidadMedia));
        return via.longitud() / v;
    }

    /** Recalcula la ruta desde el final de la vía actual. Solo cambia si la nueva cuesta menos que {@code umbral} veces la actual. */
    private boolean rerutear(Vehiculo v, FuncionCosto f, double umbral) {
        if (v.enUltimaVia() || t - v.ultimoReruteo < 2.0) return false;
        Via actual = v.viaActual();
        String nodo = actual.hacia();
        v.ultimoReruteo = t;
        FuncionCosto sinRetorno = via -> via.desde().equals(nodo) && via.hacia().equals(actual.desde()) ? Double.POSITIVE_INFINITY : f.costo(via);
        Ruta nueva = AEstrella.buscar(red, nodo, v.destinoId, sinRetorno);
        double costoActual = 0;
        for (int i = v.indiceVia + 1; i < v.ruta.length; i++) costoActual += f.costo(v.ruta[i]);
        // Si el camino sigue cerrado y no hay otra salida, el conductor da la vuelta en U en la intersección.
        if (nueva == null && Double.isInfinite(costoActual)) nueva = AEstrella.buscar(red, nodo, v.destinoId, f);
        if (nueva == null) return false;
        if (!Double.isInfinite(costoActual) && nueva.costo() >= costoActual * umbral) return false;
        Via[] ruta = new Via[v.indiceVia + 1 + nueva.vias().length];
        System.arraycopy(v.ruta, 0, ruta, 0, v.indiceVia + 1);
        System.arraycopy(nueva.vias(), 0, ruta, v.indiceVia + 1, nueva.vias().length);
        v.ruta = ruta;
        v.reruteos++;
        stats.reruteos++;
        return true;
    }

    // ================================================================= movimiento de vehículos particulares

    private Luz luz(EstadoVia ev) {
        Semaforo s = semaforos.obtener(ev.via.hacia());
        return s == null ? Luz.VERDE : s.luzPara(ev.grupo);
    }

    private boolean puedeEntrar(Vehiculo v, Via siguiente, double avance) {
        EstadoVia nx = estados.obtener(siguiente.id());
        if (nx.bloqueada() && nx.incidente.posicion < v.tipo.largo() + 12) return false;
        if (nx.cola.estaVacia()) return true;
        Vehiculo ultimo = nx.cola.ultimo();
        return ultimo.posicion - ultimo.tipo.largo() - MIN_DISTANCIA >= Math.max(avance, 0.2);
    }

    private void moverCola(EstadoVia ev) {
        double dt = cfg.dt, largo = ev.via.longitud();
        Vehiculo lider = null;
        var it = ev.cola.iterator();
        while (it.hasNext()) {
            Vehiculo v = it.next();
            if (v.ultimoPaso == paso) { lider = v; continue; }
            double limite = Double.POSITIVE_INFINITY, velObstaculo = 0;
            if (lider != null) {
                limite = lider.posicion - lider.tipo.largo() - MIN_DISTANCIA;
                velObstaculo = lider.velocidad;
            }
            if (ev.bloqueada() && v.posicion < ev.incidente.posicion) { // el incidente actúa como un obstáculo detenido
                double l = ev.incidente.posicion - MARGEN_INCIDENTE;
                if (l < limite) { limite = l; velObstaculo = 0; }
            }
            double distAlFinal = largo - v.posicion;
            // Calles ya cerradas más adelante: se busca otro camino antes de llegar a la intersección.
            if (!v.enUltimaVia() && distAlFinal < 90 && estados.obtener(v.ruta[v.indiceVia + 1].id()).bloqueada()) {
                rerutear(v, this::costoLibre, 1.0);
            }
            if (lider == null && !v.enUltimaVia()) {
                Luz luz = luz(ev);
                double distFrenado = v.velocidad * v.velocidad / (2 * v.tipo.frenado());
                boolean pasa = luz == Luz.VERDE || (luz == Luz.AMARILLO && distAlFinal <= distFrenado + 1);
                if (pasa && distAlFinal < 30 && !puedeEntrar(v, v.ruta[v.indiceVia + 1], 0.2)) pasa = false; // no bloquear el cruce
                if (!pasa) {
                    double l = largo - MARGEN_PARE;
                    if (l < limite) { limite = l; velObstaculo = 0; }
                }
            }
            double vMax = ev.via.velocidadMaxMs() * v.tipo.factorVelocidad();
            double libre = Math.min(vMax, v.velocidad + v.tipo.aceleracion() * dt);
            double distancia = limite - v.posicion;
            double segura = Double.isInfinite(limite) ? Double.POSITIVE_INFINITY
                    : distancia <= 0 ? 0 : Math.sqrt(velObstaculo * velObstaculo + 2 * v.tipo.frenado() * distancia);
            double nueva = Math.max(0, Math.min(libre, segura));
            double nuevaPos = v.posicion + nueva * dt;
            if (!Double.isInfinite(limite)) nuevaPos = Math.min(nuevaPos, Math.max(v.posicion, limite));
            v.velocidad = nueva;
            v.distancia += nuevaPos - v.posicion;
            v.posicion = nuevaPos;
            if (v.velocidad < 0.3) { v.tiempoDetenido += dt; v.esperaTotal += dt; } else v.tiempoDetenido = 0;

            if (v.posicion >= largo) { // llegó al final de la vía
                if (v.enUltimaVia()) {
                    finalizar(v);
                    it.remove();
                    ev.salidas++;
                    continue;
                }
                double avance = v.posicion - largo;
                Via siguiente = v.ruta[v.indiceVia + 1];
                if (puedeEntrar(v, siguiente, avance)) {
                    it.remove();
                    ev.salidas++;
                    EstadoVia nx = estados.obtener(siguiente.id());
                    v.indiceVia++;
                    v.posicion = Math.max(avance, 0.2);
                    v.ultimoPaso = paso;
                    nx.cola.encolar(v);
                    if (cfg.reruteoDinamico) rerutear(v, this::costoDinamico, 0.9);
                    continue;
                }
                v.posicion = largo - MARGEN_PARE;
                v.velocidad = 0;
            }
            lider = v;
        }
    }

    private void finalizar(Vehiculo v) {
        v.estado = Vehiculo.Estado.FINALIZADO;
        v.tSalida = t;
        activos.eliminar(v.id);
        stats.registrarFinal(v);
    }

    // ================================================================= emergencias

    private void moverEmergencias(EstadoVia ev) {
        if (ev.emergencias.estaVacia()) return;
        double dt = cfg.dt, largo = ev.via.longitud();
        var it = ev.emergencias.iterator();
        while (it.hasNext()) {
            Vehiculo e = it.next();
            if (e.ultimoPaso == paso) continue;
            if (e.estado == Vehiculo.Estado.ATENDIENDO) continue;
            double limite = Double.POSITIVE_INFINITY;
            Incidente destino = e.incidenteId == null ? null : incidentes.obtener(e.incidenteId);
            boolean aquiElIncidente = destino != null && destino.abierto() && destino.viaId.equals(ev.via.id());
            if (ev.bloqueada() && e.posicion < ev.incidente.posicion) {
                limite = ev.incidente.posicion - (aquiElIncidente ? 8 : MARGEN_INCIDENTE + 2);
            }
            if (!e.enUltimaVia()) {
                Luz luz = luz(ev);
                double distFrenado = e.velocidad * e.velocidad / (2 * e.tipo.frenado());
                boolean pasa = luz == Luz.VERDE || (luz == Luz.AMARILLO && largo - e.posicion <= distFrenado + 1);
                if (!pasa) limite = Math.min(limite, largo - MARGEN_PARE);
            }
            double vMax = ev.via.velocidadMaxMs() * e.tipo.factorVelocidad();
            double libre = Math.min(vMax, e.velocidad + e.tipo.aceleracion() * dt);
            double segura = Double.isInfinite(limite) ? Double.POSITIVE_INFINITY : Math.sqrt(2 * e.tipo.frenado() * Math.max(0, limite - e.posicion));
            double nueva = Math.max(0, Math.min(libre, segura));
            double nuevaPos = e.posicion + nueva * dt;
            if (!Double.isInfinite(limite)) nuevaPos = Math.min(nuevaPos, Math.max(e.posicion, limite));
            e.velocidad = nueva;
            e.distancia += nuevaPos - e.posicion;
            e.posicion = nuevaPos;
            if (aquiElIncidente && e.posicion >= ev.incidente.posicion - 8.5) { // llegó al sitio
                e.estado = Vehiculo.Estado.ATENDIENDO;
                e.velocidad = 0;
                stats.emergenciasAtendidas++;
                stats.sumaRespuesta += t - e.tDespacho;
                incidentes.atender(destino);
                evento("emergencia", e.tipo.etiqueta() + " " + e.id + " llegó a " + ev.via.nombre() + " (" + Math.round(t - e.tDespacho) + " s)");
                continue;
            }
            if (e.posicion >= largo) {
                if (e.enUltimaVia()) { retirar(e); it.remove(); continue; }
                Luz luz = luz(ev);
                double distFrenado = e.velocidad * e.velocidad / (2 * e.tipo.frenado());
                if (luz == Luz.VERDE || luz == Luz.AMARILLO && distFrenado > 1) {
                    it.remove();
                    e.indiceVia++;
                    e.posicion = Math.max(e.posicion - largo, 0.2);
                    e.ultimoPaso = paso;
                    estados.obtener(e.ruta[e.indiceVia].id()).emergencias.agregarFinal(e);
                    continue;
                }
                e.posicion = largo - MARGEN_PARE;
                e.velocidad = 0;
            }
        }
    }

    private void retirar(Vehiculo e) {
        e.estado = Vehiculo.Estado.FINALIZADO;
        e.tSalida = t;
        if (activos.eliminar(e.id) != null) flota[e.tipo.ordinal()]--;
    }

    private int maxFlota(TipoVehiculo tipo) {
        return switch (tipo) {
            case AMBULANCIA -> cfg.maxAmbulancias;
            case BOMBEROS -> cfg.maxBomberos;
            default -> cfg.maxPolicias;
        };
    }

    private Interseccion baseDe(TipoVehiculo tipo) {
        String servicio = switch (tipo) {
            case AMBULANCIA -> "hospital";
            case BOMBEROS -> "bomberos";
            default -> "policia";
        };
        for (Interseccion n : nodos) if (n.servicios().contains(servicio)) return n;
        for (Interseccion n : nodos) if (!n.servicios().isEmpty()) return n;
        return entradas.get(0);
    }

    /** Despacha por prioridad (ambulancia, bomberos, policía; a igual prioridad, el incidente más grave). */
    private void procesarSolicitudes() {
        List<Solicitud> diferidas = new ArrayList<>();
        while (!solicitudes.estaVacia()) {
            Solicitud s = solicitudes.extraerMinimo();
            if (s.incidenteId() != null) {
                Incidente i = incidentes.obtener(s.incidenteId());
                if (i == null || !i.abierto()) continue; // ya no hace falta
            }
            if (flota[s.tipo().ordinal()] >= maxFlota(s.tipo()) || !despachar(s)) diferidas.add(s);
        }
        for (Solicitud s : diferidas) solicitudes.insertar(s);
    }

    private boolean despachar(Solicitud s) {
        Interseccion base = baseDe(s.tipo());
        Via[] ruta;
        if (s.incidenteId() != null) {
            Via via = red.via(incidentes.obtener(s.incidenteId()).viaId);
            Ruta r = base.id().equals(via.desde()) ? new Ruta(base.id(), via.desde(), new Via[0], 0, 0) : AEstrella.buscar(red, base.id(), via.desde(), this::costoLibre);
            if (r == null) return false;
            ruta = new Via[r.vias().length + 1];
            System.arraycopy(r.vias(), 0, ruta, 0, r.vias().length);
            ruta[ruta.length - 1] = via;
        } else {
            Ruta r = AEstrella.buscar(red, base.id(), s.destinoNodo(), this::costoLibre);
            if (r == null || r.vias().length == 0) return false;
            ruta = r.vias();
        }
        EstadoVia primera = viasPorIndice[indiceVia(ruta[0])];
        Vehiculo e = new Vehiculo(siguienteNumero++, s.tipo(), base.id(), s.destinoNodo() != null ? s.destinoNodo() : ruta[ruta.length - 1].desde(), ruta, t);
        e.estado = Vehiculo.Estado.EN_VIA;
        e.indiceVia = 0;
        e.posicion = e.tipo.largo() + 0.1;
        e.tEntrada = t;
        e.tDespacho = t;
        e.incidenteId = s.incidenteId();
        e.ultimoPaso = paso;
        primera.emergencias.agregarFinal(e);
        activos.poner(e.id, e);
        flota[e.tipo.ordinal()]++;
        evento("emergencia", e.tipo.etiqueta() + " " + e.id + " sale de " + base.nombre() + (s.incidenteId() != null ? " hacia " + s.incidenteId() : " hacia " + red.interseccion(s.destinoNodo()).nombre()));
        return true;
    }

    /** Despacha un vehículo de emergencia a una intersección (para probar la prioridad y la ruta). */
    public void despacharEmergencia(TipoVehiculo tipo, String nodoDestino) {
        if (!tipo.esEmergencia()) throw new IllegalArgumentException("Ese tipo de vehículo no es de emergencia");
        if (red.interseccion(nodoDestino) == null) throw new IllegalArgumentException("No existe la intersección " + nodoDestino);
        solicitudes.insertar(new Solicitud(tipo, null, nodoDestino, 0, ordenSolicitud++, t));
        ultimoDespacho = -1e9;
    }

    // ================================================================= incidentes

    /** Registra un accidente en la vía (fracción de 0 a 1 de su longitud) y la bloquea. */
    public Incidente crearIncidente(String idVia, double fraccion, int gravedad) {
        EstadoVia ev = estados.obtener(idVia);
        if (ev == null) throw new IllegalArgumentException("No existe la vía " + idVia);
        if (ev.bloqueada()) throw new IllegalStateException("Ya hay un incidente en " + ev.via.nombre());
        double pos = Math.max(0.15, Math.min(0.85, fraccion)) * ev.via.longitud();
        Incidente inc = incidentes.registrar(ev.via, pos, gravedad, t);
        ev.incidente = inc;
        evento("incidente", "Accidente " + new String[] {"", "leve", "moderado", "grave"}[gravedad] + " en " + ev.via.nombre() + " (" + inc.id + ")");
        if (cfg.despachoAutomatico) {
            TipoVehiculo[] equipo = switch (gravedad) {
                case 1 -> new TipoVehiculo[] {TipoVehiculo.POLICIA};
                case 2 -> new TipoVehiculo[] {TipoVehiculo.AMBULANCIA, TipoVehiculo.POLICIA};
                default -> new TipoVehiculo[] {TipoVehiculo.AMBULANCIA, TipoVehiculo.BOMBEROS};
            };
            for (TipoVehiculo tv : equipo) solicitudes.insertar(new Solicitud(tv, inc.id, null, gravedad, ordenSolicitud++, t));
            ultimoDespacho = -1e9;
        }
        if (cfg.reruteoDinamico) { // los conductores que tenían esa vía en su ruta buscan otro camino
            for (var e : activos) {
                Vehiculo v = e.valor();
                if (v.esEmergencia() || v.estado != Vehiculo.Estado.EN_VIA) continue;
                for (int i = v.indiceVia + 1; i < v.ruta.length; i++) {
                    if (v.ruta[i].id().equals(idVia)) { v.ultimoReruteo = -1e9; rerutear(v, this::costoDinamico, 1.0); break; }
                }
            }
        }
        return inc;
    }

    public boolean resolverIncidente(String id) {
        Incidente i = incidentes.obtener(id);
        if (i == null || !incidentes.resolver(id, t)) return false;
        liberarIncidente(i, "lo despejó el operador");
        return true;
    }

    private void liberarIncidente(Incidente i, String motivo) {
        EstadoVia ev = estados.obtener(i.viaId);
        if (ev.incidente == i) ev.incidente = null;
        evento("incidente", i.id + " resuelto en " + ev.via.nombre() + " (" + motivo + ")");
        List<Vehiculo> liberar = new ArrayList<>();
        for (var e : activos) if (i.id.equals(e.valor().incidenteId) && e.valor().estado == Vehiculo.Estado.ATENDIENDO) liberar.add(e.valor());
        for (Vehiculo e : liberar) {
            estados.obtener(e.viaActual().id()).emergencias.eliminar(e);
            retirar(e);
        }
    }

    // ================================================================= semáforos

    public void forzarSemaforo(String nodoId, int grupo) {
        Semaforo s = semaforos.obtener(nodoId);
        if (s == null || !s.activo) throw new IllegalArgumentException("Esa intersección no tiene semáforo");
        if (grupo < -1 || grupo > 1) throw new IllegalArgumentException("El grupo debe ser 0, 1 o -1 (automático)");
        s.manualGrupo = grupo;
        evento("semaforo", grupo < 0 ? "Semáforo de " + s.nodo.nombre() + " vuelve al control automático" : "El operador fija el verde del grupo " + grupo + " en " + s.nodo.nombre());
    }

    /** Onda verde: desfasa los semáforos de la lista según la distancia entre ellos (modo de tiempos fijos). */
    public void sincronizar(List<String> nodoIds) {
        double ciclo = 2 * (cfg.verdeFijo + cfg.amarillo + cfg.todoRojo), acumulado = 0;
        Interseccion anterior = null;
        int n = 0;
        for (String id : nodoIds) {
            Interseccion nodo = red.interseccion(id);
            Semaforo s = semaforos.obtener(id);
            if (nodo == null || s == null || !s.activo) continue;
            if (anterior != null) acumulado += anterior.distanciaA(nodo) / 11.0;
            posicionarEnCiclo(s, acumulado % ciclo);
            anterior = nodo;
            n++;
        }
        if (n > 0) evento("semaforo", "Se sincronizaron " + n + " semáforos (onda verde)");
    }

    private void posicionarEnCiclo(Semaforo s, double pos) {
        double v = cfg.verdeFijo, a = cfg.amarillo, r = cfg.todoRojo;
        Semaforo.Fase[] fases = {Semaforo.Fase.VERDE_0, Semaforo.Fase.AMARILLO_0, Semaforo.Fase.ROJO_0, Semaforo.Fase.VERDE_1, Semaforo.Fase.AMARILLO_1, Semaforo.Fase.ROJO_1};
        double[] dur = {v, a, r, v, a, r};
        for (int i = 0; i < 6; i++) {
            if (pos < dur[i]) { s.fase = fases[i]; s.tFase = pos; s.verdeObjetivo = v; return; }
            pos -= dur[i];
        }
        s.fase = Semaforo.Fase.VERDE_0;
        s.tFase = 0;
        s.verdeObjetivo = v;
    }

    // ================================================================= indicadores

    private void actualizarTrafico() {
        double sumaVel = 0;
        int cuenta = 0;
        viasCongestionadas = 0;
        emergenciasActivas = 0;
        for (EstadoVia ev : viasPorIndice) {
            int n = ev.cola.tamano();
            double largoTotal = 0, vel = 0;
            for (Vehiculo v : ev.cola) { largoTotal += v.tipo.largo() + MIN_DISTANCIA * 0.5; vel += v.velocidad; sumaVel += v.velocidad; cuenta++; }
            for (Vehiculo e : ev.emergencias) emergenciasActivas++;
            double vLim = ev.via.velocidadMaxMs();
            double promedio = n == 0 ? vLim : Math.min(vLim, vel / n);
            ev.velocidadMedia += (promedio - ev.velocidadMedia) * (n == 0 ? 0.1 : 0.3);
            ev.ocupacion = Math.min(1.5, largoTotal / ev.via.longitud());
            double puntaje = 0;
            if (n > 0) {
                double ocup = Math.min(1, ev.ocupacion / 0.5), lento = 1 - Math.min(1, ev.velocidadMedia / vLim), peso = Math.min(1, n / 3.0);
                puntaje = peso * (0.5 * ocup + 0.5 * lento);
            }
            if (ev.bloqueada()) puntaje = 1;
            ev.puntaje = puntaje;
            ev.nivel = puntaje < 0.25 ? 0 : puntaje < 0.5 ? 1 : puntaje < 0.75 ? 2 : 3;
            if ((ev.nivel >= 2 && n >= 3) || ev.bloqueada()) viasCongestionadas++;
            ClaveRanking nueva = new ClaveRanking(puntaje, ev.indice), vieja = claveActual.obtener(ev.indice);
            if (vieja != null) ranking.eliminar(vieja);
            if (puntaje > 0) { ranking.insertar(nueva, ev); claveActual.poner(ev.indice, nueva); } else claveActual.eliminar(ev.indice);
        }
        velocidadMediaKmh = cuenta == 0 ? 0 : sumaVel / cuenta * 3.6;
    }

    private void muestrear() {
        stats.registrarMuestra(new Estadisticas.Muestra(t, activos.tamano(), vehiculosEnEspera(), velocidadMediaKmh, viasCongestionadas,
                stats.completados, stats.tiempoViajeMedio(), stats.esperaMedia(), emergenciasActivas));
    }

    private void evento(String tipo, String mensaje) {
        eventos.agregarFinal(new Evento(t, tipo, mensaje));
        if (eventos.tamano() > MAX_EVENTOS) eventos.quitarInicio();
    }

    // ================================================================= instantánea para la pantalla

    /** Estado completo en JSON. Los vehículos van como arreglos para reducir el tamaño. */
    public String instantaneaJson() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"t\":").append(Math.round(t * 10) / 10.0).append(",\"modo\":\"").append(cfg.modoSemaforos).append("\",\"reruteo\":").append(cfg.reruteoDinamico);
        sb.append(",\"vpm\":").append(cfg.vehiculosPorMinuto);
        sb.append(",\"kpi\":");
        Json.escribir(sb, Json.obj("activos", activos.tamano(), "enEspera", vehiculosEnEspera(), "velocidadKmh", velocidadMediaKmh, "congestionadas", viasCongestionadas,
                "intersecciones", nodos.size(), "semaforos", listaSemaforos.aLista().stream().filter(Semaforo::esActivo).count(), "emergencias", emergenciasActivas,
                "incidentes", incidentes.abiertos().size(), "completados", stats.completados, "viajeMedio", stats.tiempoViajeMedio(), "esperaMedia", stats.esperaMedia(),
                "respuestaMedia", stats.respuestaMedia(), "reruteos", stats.reruteos, "generados", stats.generados));
        sb.append(",\"v\":[");
        boolean primero = true;
        for (EstadoVia ev : viasPorIndice) {
            for (Vehiculo v : ev.cola) {
                if (!primero) sb.append(',');
                primero = false;
                sb.append('[').append(v.numero).append(',').append(ev.indice).append(',').append(Math.round(v.posicion * 10) / 10.0).append(',').append(Math.round(v.velocidad * 10) / 10.0).append(',').append(v.tipo.ordinal()).append(']');
            }
        }
        sb.append("],\"e\":[");
        primero = true;
        for (EstadoVia ev : viasPorIndice) {
            for (Vehiculo e : ev.emergencias) {
                if (!primero) sb.append(',');
                primero = false;
                sb.append("{\"id\":\"").append(e.id).append("\",\"tipo\":").append(e.tipo.ordinal()).append(",\"via\":").append(ev.indice).append(",\"pos\":").append(Math.round(e.posicion * 10) / 10.0)
                        .append(",\"vel\":").append(Math.round(e.velocidad * 10) / 10.0).append(",\"atendiendo\":").append(e.estado == Vehiculo.Estado.ATENDIENDO).append(",\"ruta\":[");
                for (int i = e.indiceVia; i < e.ruta.length; i++) { if (i > e.indiceVia) sb.append(','); sb.append(indiceVia(e.ruta[i])); }
                sb.append("]}");
            }
        }
        sb.append("],\"s\":[");
        primero = true;
        for (int i = 0; i < semaforosPorNodo.length; i++) {
            Semaforo s = semaforosPorNodo[i];
            if (!s.activo) continue;
            if (!primero) sb.append(',');
            primero = false;
            sb.append('[').append(i).append(',').append(s.fase.ordinal()).append(',').append(s.manualGrupo).append(']');
        }
        sb.append("],\"c\":[");
        primero = true;
        for (EstadoVia ev : viasPorIndice) {
            if (ev.nivel == 0 && !ev.bloqueada() && ev.vehiculos() == 0) continue;
            if (!primero) sb.append(',');
            primero = false;
            sb.append('[').append(ev.indice).append(',').append(ev.nivel).append(',').append(ev.vehiculos()).append(',').append(ev.bloqueada() ? 1 : 0).append(']');
        }
        sb.append("],\"i\":");
        List<Object> lista = new ArrayList<>();
        for (Incidente i : incidentes.abiertos()) {
            lista.add(Json.obj("id", i.id, "via", estados.obtener(i.viaId).indice, "pos", i.posicion, "gravedad", i.gravedad, "estado", i.estado.name(), "restante", i.restante));
        }
        Json.escribir(sb, lista);
        sb.append(",\"rk\":");
        List<Object> top = new ArrayList<>();
        for (EstadoVia ev : masCongestionadas(5)) top.add(Json.obj("via", ev.indice, "nombre", ev.via.nombre(), "puntaje", ev.puntaje, "vehiculos", ev.vehiculos()));
        Json.escribir(sb, top);
        sb.append(",\"ev\":");
        List<Object> ult = new ArrayList<>();
        for (Evento e : ultimosEventos(12)) ult.add(Json.obj("t", e.t(), "tipo", e.tipo(), "msg", e.mensaje()));
        Json.escribir(sb, ult);
        sb.append('}');
        return sb.toString();
    }
}
