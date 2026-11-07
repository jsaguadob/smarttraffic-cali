package smarttraffic.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.red.RedCali;
import smarttraffic.red.RedVial;
import smarttraffic.red.Via;

class SimulacionTest {

    private static Simulacion nueva(Config c) {
        return new Simulacion(RedCali.crear(), c);
    }

    private static Config sinDespacho(Config c) {
        c.despachoAutomatico = false;
        return c;
    }

    /** Revisa las reglas físicas y lógicas que nunca deben romperse. */
    private static void verificarInvariantes(Simulacion sim) {
        for (EstadoVia ev : sim.vias()) {
            double largo = ev.via.longitud();
            Vehiculo lider = null;
            for (Vehiculo v : ev.cola) {
                assertTrue(v.posicion >= 0 && v.posicion <= largo + 0.001, v.id + " fuera de la vía " + ev.via.id() + ": " + v.posicion + " de " + largo);
                assertTrue(v.velocidad >= 0 && v.velocidad <= ev.via.velocidadMaxMs() * 1.6, v.id + " velocidad " + v.velocidad);
                if (lider != null) {
                    double hueco = lider.posicion - lider.tipo.largo() - v.posicion;
                    assertTrue(hueco >= Simulacion.MIN_DISTANCIA - 0.01, "choque en " + ev.via.id() + " t=" + sim.tiempo() + ": " + v.id + " detrás de " + lider.id + " con hueco " + hueco);
                }
                lider = v;
            }
        }
        for (Semaforo s : sim.semaforos()) {
            if (!s.esActivo()) continue;
            assertFalse(s.luzPara(0) != Semaforo.Luz.ROJO && s.luzPara(1) != Semaforo.Luz.ROJO, "dos grupos que se cruzan con luz a la vez en " + s.nodo().nombre());
        }
        Estadisticas e = sim.estadisticas();
        assertEquals(e.generados(), e.completados() + sim.particularesEnVia() + sim.vehiculosEnEspera(), "se perdió o se duplicó algún vehículo");
    }

    @Test @DisplayName("Invariantes con tráfico pesado: sin choques, sin dos verdes cruzados, sin vehículos perdidos")
    void invariantes() {
        for (Config c : new Config[] {Config.tradicional(5, 150), Config.inteligente(5, 150)}) {
            Simulacion sim = nueva(c);
            for (int s = 0; s < 600; s++) {
                sim.avanzar(1.0);
                verificarInvariantes(sim);
            }
            assertTrue(sim.estadisticas().completados() > 300, "debe haber viajes completados: " + sim.estadisticas().completados());
        }
    }

    @Test @DisplayName("Con accidentes y emergencias también se cumplen los invariantes")
    void invariantesConIncidentes() {
        Simulacion sim = nueva(Config.inteligente(9, 100));
        String[] vias = {"H12E", "V10:AB", "H20O", "V12N"};
        for (int s = 0; s < 500; s++) {
            if (s == 100) sim.crearIncidente(vias[0], 0.5, 3);
            if (s == 160) sim.crearIncidente(vias[1], 0.4, 2);
            if (s == 220) sim.crearIncidente(vias[3], 0.6, 1);
            if (s == 260) sim.despacharEmergencia(TipoVehiculo.AMBULANCIA, "N33");
            sim.avanzar(1.0);
            verificarInvariantes(sim);
        }
    }

    @Test @DisplayName("Misma semilla y mismos comandos dan exactamente el mismo resultado")
    void determinismo() {
        String[] json = new String[2];
        double[] viaje = new double[2];
        for (int k = 0; k < 2; k++) {
            Simulacion sim = nueva(Config.inteligente(77, 90));
            sim.avanzar(120);
            sim.crearIncidente("H12E", 0.5, 2);
            sim.agregarVehiculos(30);
            sim.avanzar(240);
            json[k] = sim.instantaneaJson();
            viaje[k] = sim.estadisticas().tiempoViajeMedio();
        }
        assertEquals(json[0], json[1]);
        assertEquals(viaje[0], viaje[1], 0);
        Simulacion otra = nueva(Config.inteligente(78, 90));
        otra.avanzar(360);
        assertNotEquals(json[0], otra.instantaneaJson(), "otra semilla debe dar otra historia");
    }

    private static EstadoVia viaMasOcupada(Simulacion sim) {
        EstadoVia mejor = null;
        for (EstadoVia ev : sim.vias()) if (mejor == null || ev.cola.tamano() > mejor.cola.tamano()) mejor = ev;
        return mejor;
    }

    @Test @DisplayName("Un accidente bloquea la vía: los que quedan atrás no pasan, y al despejarse siguen")
    void incidenteBloquea() {
        Simulacion sim = nueva(sinDespacho(Config.tradicional(3, 80)));
        sim.avanzar(150);
        EstadoVia ev = viaMasOcupada(sim);
        assertTrue(ev.cola.tamano() >= 2, "se necesita una vía con tráfico");
        Incidente inc = sim.crearIncidente(ev.via.id(), 0.5, 3);
        assertTrue(ev.bloqueada());
        assertThrows(IllegalStateException.class, () -> sim.crearIncidente(ev.via.id(), 0.5, 1), "no se duplica el incidente en la misma vía");
        Set<String> atras = new HashSet<>();
        for (Vehiculo v : ev.cola) if (v.posicion < inc.posicion) atras.add(v.id);
        for (int s = 0; s < 40; s++) {
            sim.avanzar(1.0);
            for (Vehiculo v : ev.cola) if (atras.contains(v.id)) assertTrue(v.posicion <= inc.posicion - Simulacion.MARGEN_INCIDENTE + 0.01, v.id + " atravesó el accidente");
            verificarInvariantes(sim);
        }
        assertTrue(sim.resolverIncidente(inc.id()));
        assertFalse(sim.resolverIncidente(inc.id()), "no se resuelve dos veces");
        assertFalse(ev.bloqueada());
        sim.avanzar(60);
        for (Vehiculo v : ev.cola) assertFalse(atras.contains(v.id) && v.posicion < inc.posicion - 20, "los vehículos retenidos debieron avanzar");
    }

    @Test @DisplayName("Con reruteo inteligente nadie entra a una vía bloqueada")
    void reruteoEvitaVia() {
        Simulacion sim = nueva(sinDespacho(Config.inteligente(4, 90)));
        sim.avanzar(150);
        EstadoVia ev = viaMasOcupada(sim);
        Set<String> iniciales = new HashSet<>();
        for (Vehiculo v : ev.cola) iniciales.add(v.id);
        sim.crearIncidente(ev.via.id(), 0.5, 3);
        for (int s = 0; s < 100; s++) {
            sim.avanzar(1.0);
            for (Vehiculo v : ev.cola) assertTrue(iniciales.contains(v.id), v.id + " entró a una vía bloqueada");
        }
        assertTrue(sim.estadisticas().reruteos() > 0, "algunos conductores debieron cambiar de ruta");
    }

    @Test @DisplayName("Emergencias: salen por prioridad, llegan y el incidente se despeja antes que solo")
    void emergencias() {
        Simulacion con = nueva(Config.inteligente(6, 60));
        con.avanzar(60);
        Incidente inc = con.crearIncidente("H12E", 0.5, 3);
        con.avanzar(5);
        List<String> mensajes = new ArrayList<>();
        for (Evento e : con.ultimosEventos(20)) if (e.tipo().equals("emergencia")) mensajes.add(e.mensaje());
        assertTrue(mensajes.size() >= 2, "un accidente grave despacha ambulancia y bomberos: " + mensajes);
        assertTrue(mensajes.get(0).startsWith("Ambulancia"), "la ambulancia sale antes que los bomberos: " + mensajes);
        assertTrue(mensajes.get(1).startsWith("Bomberos"), mensajes.toString());
        for (int s = 0; s < 400 && inc.abierto(); s++) { con.avanzar(1.0); verificarInvariantes(con); }
        assertEquals(Incidente.Estado.RESUELTO, inc.estado());
        assertTrue(con.estadisticas().emergenciasAtendidas() >= 2, "llegaron ambulancia y bomberos");
        assertTrue(con.estadisticas().respuestaMedia() > 5 && con.estadisticas().respuestaMedia() < 200, "tiempo de respuesta razonable: " + con.estadisticas().respuestaMedia());
        assertTrue(inc.tResuelto - inc.tInicio() < Incidente.duracion(3) - 5, "con el equipo en sitio se despeja antes que los " + Incidente.duracion(3) + " s naturales");

        Simulacion sin = nueva(sinDespacho(Config.inteligente(6, 60)));
        sin.avanzar(60);
        Incidente solo = sin.crearIncidente("H12E", 0.5, 3);
        sin.avanzar(Incidente.duracion(3) + 5);
        assertEquals(Incidente.Estado.RESUELTO, solo.estado());
        assertTrue(solo.tResuelto - solo.tInicio() >= Incidente.duracion(3) - 0.2, "sin equipo tarda la duración completa");
    }

    @Test @DisplayName("La flota de emergencia es limitada: nunca hay más ambulancias simultáneas que las disponibles")
    void flotaLimitada() {
        Config c = Config.inteligente(8, 40);
        c.maxAmbulancias = 1;
        Simulacion sim = nueva(c);
        sim.avanzar(30);
        sim.crearIncidente("H12E", 0.5, 2);
        sim.crearIncidente("V10:AB", 0.5, 2);
        int maximo = 0;
        for (int s = 0; s < 300; s++) {
            sim.avanzar(1.0);
            int ambulancias = 0;
            for (EstadoVia ev : sim.vias()) for (Vehiculo e : ev.emergencias) if (e.tipo == TipoVehiculo.AMBULANCIA) ambulancias++;
            maximo = Math.max(maximo, ambulancias);
        }
        assertEquals(1, maximo);
        assertTrue(sim.estadisticas().emergenciasAtendidas() >= 2, "la segunda ambulancia sale cuando queda libre la primera");
    }

    @Test @DisplayName("Semáforo manual: el operador fija el verde; y se puede sincronizar una avenida")
    void semaforoManual() {
        Simulacion sim = nueva(Config.inteligente(2, 40));
        Semaforo s = sim.semaforo("N11");
        assertTrue(s.esActivo());
        sim.forzarSemaforo("N11", 1);
        sim.avanzar(20);
        assertEquals(1, s.grupoVerde());
        sim.avanzar(120);
        assertEquals(1, s.grupoVerde(), "se mantiene en verde mientras el operador lo fije");
        sim.forzarSemaforo("N11", -1);
        sim.avanzar(120);
        assertTrue(s.cambios() > 1, "al volver a automático cambia de fase");
        assertThrows(IllegalArgumentException.class, () -> sim.forzarSemaforo("HOSP", 0), "una intersección sin semáforo no se puede forzar");
        assertThrows(IllegalArgumentException.class, () -> sim.forzarSemaforo("N11", 5));
        sim.sincronizar(List.of("N11", "N12", "N13"));
        assertTrue(sim.ultimosEventos(3).stream().anyMatch(e -> e.mensaje().contains("onda verde")));
    }

    @Test @DisplayName("Semáforos de tiempo fijo: ciclo con amarillo y rojo total entre fases")
    void cicloFijo() {
        Simulacion sim = nueva(Config.tradicional(1, 0));
        Semaforo s = sim.semaforo("N11");
        List<Semaforo.Fase> vistas = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            sim.paso();
            if (vistas.isEmpty() || vistas.get(vistas.size() - 1) != s.fase()) vistas.add(s.fase());
        }
        for (int i = 1; i < vistas.size(); i++) {
            Semaforo.Fase a = vistas.get(i - 1), b = vistas.get(i);
            assertEquals((a.ordinal() + 1) % 6, b.ordinal(), "las fases deben seguir el orden verde → amarillo → rojo total → verde del otro grupo: " + a + " → " + b);
        }
        assertTrue(vistas.size() >= 6);
    }

    @Test @DisplayName("SmartTraffic mejora los tiempos de viaje y de espera frente al tráfico tradicional (mismo tráfico de entrada)")
    void inteligenteMejora() {
        RedVial red = RedCali.crear();
        for (double vpm : new double[] {70, 150}) {
            for (long semilla : new long[] {1, 2, 3}) {
                var r = ComparadorEscenarios.comparar(red, semilla, vpm, 600, List.of());
                assertEquals(r[0].generados(), r[1].generados(), "ambos escenarios reciben exactamente la misma demanda");
                assertTrue(r[1].viajeMedioAjustado() < r[0].viajeMedioAjustado(), vpm + " veh/min, semilla " + semilla + ": " + r[1].viajeMedioAjustado() + " vs " + r[0].viajeMedioAjustado());
                assertTrue(r[1].esperaMedia() < r[0].esperaMedia(), "espera " + r[1].esperaMedia() + " vs " + r[0].esperaMedia());
                assertTrue(r[1].completados() >= r[0].completados());
            }
        }
    }

    @Test @DisplayName("La instantánea JSON es válida y trae los datos que dibuja la pantalla")
    void instantanea() {
        Simulacion sim = nueva(Config.inteligente(1, 80));
        sim.avanzar(90);
        sim.crearIncidente("H12E", 0.5, 2);
        sim.avanzar(10);
        var json = smarttraffic.util.Json.leerObjeto(sim.instantaneaJson());
        for (String k : new String[] {"t", "modo", "kpi", "v", "e", "s", "c", "i", "rk", "ev"}) assertTrue(json.containsKey(k), "falta " + k);
        assertTrue(((List<?>) json.get("v")).size() > 10);
        assertEquals(1, ((List<?>) json.get("i")).size());
        assertTrue(((List<?>) json.get("s")).size() >= 4);
    }
}
