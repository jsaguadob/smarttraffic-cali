package smarttraffic.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import smarttraffic.red.RedVial;
import smarttraffic.sim.Config;
import smarttraffic.sim.Estadisticas;
import smarttraffic.sim.Incidente;
import smarttraffic.sim.ModoSemaforos;
import smarttraffic.sim.Simulacion;
import smarttraffic.sim.TipoVehiculo;
import smarttraffic.util.Json;

/**
 * Mantiene la simulación en vivo. Un único hilo avanza el tiempo (10 veces por segundo) y ejecuta los
 * comandos de los operadores, de modo que la simulación nunca se toca desde dos hilos a la vez.
 * Cada instantánea se reparte a los navegadores suscritos (SSE).
 */
public final class GestorSimulacion implements AutoCloseable {

    /** Un navegador conectado a la transmisión en vivo. Guarda solo las dos instantáneas más recientes. */
    public static final class Suscriptor {
        private final BlockingQueue<String> cola = new ArrayBlockingQueue<>(2);

        public String siguiente(long milisegundos) throws InterruptedException {
            return cola.poll(milisegundos, TimeUnit.MILLISECONDS);
        }

        void entregar(String s) {
            while (!cola.offer(s)) cola.poll();
        }
    }

    private final ScheduledThreadPoolExecutor hilo = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "simulacion");
        t.setDaemon(true);
        return t;
    });
    private final List<Suscriptor> suscriptores = new CopyOnWriteArrayList<>();
    private Simulacion sim;
    private Config cfg = Config.inteligente(42, 40);
    private RedVial red;
    private volatile boolean corriendo;
    private volatile double velocidad = 1;
    private double acumulado;
    private int ticksPausa;
    private volatile String ultima = "{}";

    public GestorSimulacion(RedVial red) {
        this.red = red;
        this.sim = new Simulacion(red, cfg);
        this.ultima = construir();
        hilo.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            if (corriendo) {
                acumulado += velocidad;
                int pasos = (int) acumulado;
                acumulado -= pasos;
                for (int i = 0; i < pasos; i++) sim.paso();
                publicar();
            } else if (++ticksPausa >= 10) { // en pausa se reenvía una vez por segundo para que las pantallas sigan sincronizadas
                ticksPausa = 0;
                publicar();
            }
        } catch (Throwable e) {
            System.err.println("Error en la simulación: " + e);
            corriendo = false;
        }
    }

    private String construir() {
        String json = sim.instantaneaJson();
        return "{\"corriendo\":" + corriendo + ",\"velocidad\":" + velocidad + ",\"semilla\":" + cfg.semilla + "," + json.substring(1);
    }

    private void publicar() {
        ultima = construir();
        for (Suscriptor s : suscriptores) s.entregar(ultima);
    }

    public Suscriptor suscribir() {
        Suscriptor s = new Suscriptor();
        s.entregar(ultima);
        suscriptores.add(s);
        return s;
    }

    public void cancelar(Suscriptor s) {
        suscriptores.remove(s);
    }

    public String ultimaInstantanea() {
        return ultima;
    }

    /** Ejecuta una operación en el hilo de la simulación y devuelve su resultado (o lanza su error). */
    private <T> T ejecutar(Callable<T> c) {
        try {
            T r = hilo.submit(() -> {
                T valor = c.call();
                publicar();
                return valor;
            }).get(10, TimeUnit.SECONDS);
            return r;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("La simulación no respondió a tiempo");
        }
    }

    // ------------------------------------------------------------------ control

    public void iniciar() {
        ejecutar(() -> { corriendo = true; return null; });
    }

    public void pausar() {
        ejecutar(() -> { corriendo = false; return null; });
    }

    public void reiniciar(Long semilla) {
        ejecutar(() -> {
            if (semilla != null) cfg.semilla = semilla;
            sim = new Simulacion(red, cfg.copia());
            acumulado = 0;
            return null;
        });
    }

    public void velocidad(double v) {
        if (v < 0.25 || v > 32) throw new IllegalArgumentException("La velocidad debe estar entre 0,25x y 32x");
        ejecutar(() -> { velocidad = v; return null; });
    }

    public void configurar(String modo, Boolean reruteo, Double vehiculosPorMinuto) {
        ejecutar(() -> {
            if (modo != null) {
                try { cfg.modoSemaforos = ModoSemaforos.valueOf(modo.toUpperCase()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("El modo debe ser FIJO o INTELIGENTE"); }
            }
            if (reruteo != null) cfg.reruteoDinamico = reruteo;
            if (vehiculosPorMinuto != null) {
                if (vehiculosPorMinuto < 0 || vehiculosPorMinuto > 600) throw new IllegalArgumentException("Los vehículos por minuto deben estar entre 0 y 600");
                cfg.vehiculosPorMinuto = vehiculosPorMinuto;
            }
            // la simulación en marcha comparte el objeto de configuración: los cambios se aplican de inmediato
            sim.config().modoSemaforos = cfg.modoSemaforos;
            sim.config().reruteoDinamico = cfg.reruteoDinamico;
            sim.config().vehiculosPorMinuto = cfg.vehiculosPorMinuto;
            return null;
        });
    }

    public int agregarVehiculos(int n) {
        return ejecutar(() -> sim.agregarVehiculos(n));
    }

    public String crearIncidente(String viaId, double fraccion, int gravedad) {
        return ejecutar(() -> {
            Incidente i = sim.crearIncidente(viaId, fraccion, gravedad);
            return i.id();
        });
    }

    public boolean resolverIncidente(String id) {
        return ejecutar(() -> sim.resolverIncidente(id));
    }

    public void despachar(String tipo, String nodo) {
        ejecutar(() -> {
            TipoVehiculo t;
            try { t = TipoVehiculo.valueOf(tipo.toUpperCase()); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("El tipo debe ser AMBULANCIA, BOMBEROS o POLICIA"); }
            sim.despacharEmergencia(t, nodo);
            return null;
        });
    }

    public void forzarSemaforo(String nodo, int grupo) {
        ejecutar(() -> { sim.forzarSemaforo(nodo, grupo); return null; });
    }

    public void sincronizar(List<String> nodos) {
        ejecutar(() -> { sim.sincronizar(nodos); return null; });
    }

    /** Cambia la red (después de editarla) y reinicia la simulación con ella. */
    public void reemplazarRed(RedVial nueva) {
        ejecutar(() -> {
            Simulacion nuevaSim = new Simulacion(nueva, cfg.copia()); // si la red no sirve, lanza y no se cambia nada
            red = nueva;
            sim = nuevaSim;
            acumulado = 0;
            return null;
        });
    }

    /** Indicadores y serie de tiempo de la simulación en vivo. */
    public String estadisticasJson() {
        return ejecutar(() -> {
            Estadisticas e = sim.estadisticas();
            List<Object> serie = new ArrayList<>();
            for (Estadisticas.Muestra m : e.serie()) {
                serie.add(Json.obj("t", m.t(), "activos", m.activos(), "enEspera", m.enEspera(), "velocidadKmh", m.velocidadMediaKmh(), "congestionadas", m.viasCongestionadas(),
                        "completados", m.completados(), "viajeMedio", m.tiempoViajeMedio(), "esperaMedia", m.esperaMedia(), "emergencias", m.emergenciasActivas()));
            }
            return Json.escribir(Json.obj("t", sim.tiempo(), "generados", e.generados(), "completados", e.completados(), "viajeMedio", e.tiempoViajeMedio(), "viajeMedioAjustado", sim.viajeMedioAjustado(),
                    "esperaMedia", e.esperaMedia(), "emergenciasAtendidas", e.emergenciasAtendidas(), "respuestaMedia", e.respuestaMedia(), "reruteos", e.reruteos(), "serie", serie));
        });
    }

    @Override
    public void close() {
        hilo.shutdownNow();
    }
}
