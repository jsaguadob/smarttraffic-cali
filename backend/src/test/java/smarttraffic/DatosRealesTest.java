package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.datos.DatosAbiertos;
import smarttraffic.datos.ImportadorOsm;
import smarttraffic.datos.IndiceEspacial;
import smarttraffic.datos.ProyeccionLocal;
import smarttraffic.datos.PuntosCriticos;
import smarttraffic.datos.RedVialJson;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedCali;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.sim.Config;
import smarttraffic.sim.Simulacion;
import smarttraffic.util.Json;

/**
 * Pruebas del importador de OpenStreetMap y de los datos abiertos con un mapa pequeño ficticio que usa
 * exactamente el formato de la API Overpass (no son datos reales de Cali).
 */
class DatosRealesTest {
    static final double LAT0 = 3.4500, LON0 = -76.5350, PASO = 0.0012; // ~133 m entre intersecciones

    static List<Object> elementos = new ArrayList<>();
    static ImportadorOsm.Resultado resultado;

    static double lat(int i) { return LAT0 + i * PASO; }
    static double lon(int j) { return LON0 + j * PASO; }
    static long id(int i, int j) { return 1000 + i * 10L + j; }

    static void nodo(long id, double lat, double lon, Object... tags) {
        elementos.add(Json.obj("type", "node", "id", id, "lat", lat, "lon", lon, "tags", tags.length == 0 ? null : Json.obj(tags)));
    }

    static void via(long id, long[] nodos, Object... tags) {
        List<Object> ids = new ArrayList<>();
        for (long n : nodos) ids.add(n);
        elementos.add(Json.obj("type", "way", "id", id, "nodes", ids, "tags", Json.obj(tags)));
    }

    @BeforeAll
    static void construir() {
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) nodo(id(i, j), lat(i), lon(j), i == 1 && j == 1 ? new Object[] {"highway", "traffic_signals"} : new Object[0]);
        // nodos de forma (no son intersecciones) para curvar el tramo (3,0)→(3,1)
        nodo(4001, lat(3) + 0.0004, lon(0) + 0.0004);
        nodo(4002, lat(3) + 0.0004, lon(0) + 0.0008);
        // glorieta: anillo cerrado con cuatro nodos y dos calles que llegan a ella
        double cLat = lat(1) + 0.0006, cLon = lon(1) + 0.0006; // cerca del centro del bloque (1,1)-(2,2)
        nodo(6001, cLat + 0.0002, cLon);
        nodo(6002, cLat, cLon + 0.0002);
        nodo(6003, cLat - 0.0002, cLon);
        nodo(6004, cLat, cLon - 0.0002);
        // isla desconectada, peatonal y vía de servicio
        nodo(8001, lat(0) + 0.01, lon(0));
        nodo(8002, lat(0) + 0.01, lon(1));
        // calles horizontales (filas) con nombre
        via(2000, new long[] {id(0, 0), id(0, 1), id(0, 2), id(0, 3)}, "highway", "secondary", "name", "Calle 10");
        via(2001, new long[] {id(1, 0), id(1, 1), id(1, 2), id(1, 3)}, "highway", "primary", "name", "Calle 11", "maxspeed", "40");
        via(2002, new long[] {id(2, 0), id(2, 1), id(2, 2), id(2, 3)}, "highway", "tertiary", "name", "Calle 12", "maxspeed", "30 mph");
        via(2003, new long[] {id(3, 0), 4001, 4002, id(3, 1), id(3, 2), id(3, 3)}, "highway", "residential", "name", "Calle 13", "maxspeed", "walk");
        // calles verticales (columnas); la columna 2 es de un solo sentido
        for (int j = 0; j < 4; j++) via(3000 + j, new long[] {id(0, j), id(1, j), id(2, j), id(3, j)}, "highway", "residential", "name", "Carrera " + (5 + j), "oneway", j == 2 ? "yes" : "no");
        via(6000, new long[] {6001, 6002, 6003, 6004, 6001}, "highway", "residential", "junction", "roundabout");
        via(6010, new long[] {6001, id(1, 1)}, "highway", "residential", "name", "Acceso norte");
        via(6011, new long[] {6003, id(2, 2)}, "highway", "residential", "name", "Acceso sur");
        via(7000, new long[] {id(0, 0), id(0, 1)}, "highway", "footway");
        via(7001, new long[] {id(0, 1), id(0, 2)}, "highway", "service");
        via(7002, new long[] {id(1, 2), id(2, 2)}, "highway", "residential", "motor_vehicle", "no");
        via(8000, new long[] {8001, 8002}, "highway", "residential", "name", "Isla");
        String json = Json.escribir(Json.obj("version", 0.6, "generator", "fixture", "elements", elementos));
        List<Object> servicios = new ArrayList<>();
        servicios.add(Json.obj("type", "way", "id", 9001, "center", Json.obj("lat", lat(3) - 0.0002, "lon", lon(3) + 0.0002), "tags", Json.obj("amenity", "hospital", "name", "Hospital Ficticio")));
        servicios.add(Json.obj("type", "node", "id", 9002, "lat", lat(0) + 0.0001, "lon", lon(3), "tags", Json.obj("amenity", "fire_station", "name", "Bomberos Ficticios")));
        servicios.add(Json.obj("type", "node", "id", 9003, "lat", lat(3), "lon", lon(0) - 0.0001, "tags", Json.obj("amenity", "police")));
        servicios.add(Json.obj("type", "node", "id", 9004, "lat", lat(2), "lon", lon(2), "tags", Json.obj("amenity", "school")));
        resultado = ImportadorOsm.importar(json, Json.escribir(Json.obj("elements", servicios)), new ImportadorOsm.Opciones());
    }

    private static Via via(RedVial r, String nombre, boolean sentidoOeste) {
        for (Via v : r.vias()) if (v.nombre().equals(nombre)) return v;
        return null;
    }

    @Test @DisplayName("Importador: intersecciones, vías, sentidos únicos, glorieta y descarte de lo que no es para autos")
    void estructura() {
        RedVial red = resultado.red();
        assertEquals(18, red.intersecciones().size(), "16 de la cuadrícula + 2 de la glorieta; la isla se descarta");
        assertEquals(51, red.vias().size());
        assertEquals(2, resultado.descartadasPorConexion(), "los dos nodos de la isla");
        assertTrue(resultado.tramosIgnorados() >= 3, "peatonal, servicio y prohibida a motores: " + resultado.tramosIgnorados());
        assertTrue(red.fuertementeConexa());
        assertTrue(red.validarParaSimulacion().isEmpty(), red.validarParaSimulacion().toString());
        for (Via v : red.vias()) assertNotEquals("Isla", v.nombre());
        // Carrera 7 (columna 2) es de un solo sentido: 3 tramos y ninguno en sentido contrario
        List<Via> unSentido = new ArrayList<>();
        for (Via v : red.vias()) if (v.nombre().equals("Carrera 7")) unSentido.add(v);
        assertEquals(3, unSentido.size());
        for (Via v : unSentido) for (Via w : red.vias()) assertFalse(w.desde().equals(v.hacia()) && w.hacia().equals(v.desde()) && w.nombre().equals("Carrera 7"), "no debe existir el sentido contrario");
        // La glorieta: dos vías sin nombre propio, con sentido único
        int glorieta = 0;
        for (Via v : red.vias()) if (v.clase().equals("residential") && v.nombre().equals("Calle sin nombre")) glorieta++;
        assertEquals(2, glorieta, "los dos arcos de la glorieta");
    }

    @Test @DisplayName("Importador: velocidades de etiquetas (40, 30 mph, walk) y por tipo de calle")
    void velocidades() {
        RedVial red = resultado.red();
        assertEquals(40, via(red, "Calle 11", true).velocidadMaxKmh(), 1e-9);
        assertEquals(30 * 1.609344, via(red, "Calle 12", true).velocidadMaxKmh(), 1e-9);
        assertEquals(50, via(red, "Calle 10", true).velocidadMaxKmh(), 1e-9, "secondary sin maxspeed");
        assertEquals(30, via(red, "Calle 13", true).velocidadMaxKmh(), 1e-9, "«walk» no es un número: se usa la velocidad de residencial");
    }

    @Test @DisplayName("Importador: la geometría curva se conserva y la longitud es la de la curva, no la de la recta")
    void geometria() {
        RedVial red = resultado.red();
        Via curva = null;
        for (Via v : red.vias()) if (v.nombre().equals("Calle 13") && v.puntos().length == 8) { curva = v; break; }
        assertNotNull(curva, "el tramo con dos nodos de forma tiene 4 puntos");
        Interseccion a = red.interseccion(curva.desde()), b = red.interseccion(curva.hacia());
        double recta = a.distanciaA(b);
        assertTrue(curva.longitud() > recta * 1.1, "curva " + curva.longitud() + " vs recta " + recta);
        assertEquals(curva.longitudGeometria(), curva.longitud(), 1e-6);
        double[] medio = curva.puntoEn(curva.longitud() / 2);
        assertEquals(3, medio.length);
        double[] fin = curva.puntoEn(curva.longitud());
        assertEquals(b.x(), fin[0], 1e-6); assertEquals(b.y(), fin[1], 1e-6);
        // 133 m entre intersecciones: la escala de la proyección es la correcta
        Via recto = via(red, "Calle 10", true);
        assertEquals(PASO * Math.PI / 180 * 6371008.8 * Math.cos(Math.toRadians(resultado.proyeccion().lat0())), recto.longitud(), 1.5);
    }

    @Test @DisplayName("Importador: semáforos, nombres de intersección, entradas y bases de emergencia")
    void semaforosYServicios() {
        RedVial red = resultado.red();
        assertEquals(1, resultado.semaforos());
        Interseccion semaforo = red.interseccion("N" + id(1, 1));
        assertTrue(semaforo.semaforizada());
        assertTrue(semaforo.nombre().contains("Calle 11") && semaforo.nombre().contains("Carrera 6"), semaforo.nombre());
        assertFalse(red.interseccion("N" + id(2, 2)).semaforizada());
        assertTrue(red.entradasSalidas().size() >= 2);
        for (Interseccion i : red.entradasSalidas()) assertTrue(i.latitud() > 3 && i.longitud() < -76, "conserva las coordenadas reales");
        assertEquals(3, resultado.bases(), "hospital, bomberos y policía (la escuela no cuenta)");
        Interseccion hospital = red.interseccion("N" + id(3, 3));
        assertTrue(hospital.servicios().contains("hospital"));
        assertTrue(hospital.nombre().startsWith("Hospital Ficticio"), hospital.nombre());
        assertTrue(red.interseccion("N" + id(0, 3)).servicios().contains("bomberos"));
        assertTrue(red.interseccion("N" + id(3, 0)).servicios().contains("policia"));
    }

    @Test @DisplayName("Los semáforos de los datos abiertos se asignan a la intersección más cercana")
    void semaforosAbiertos() {
        RedVial red = ImportadorOsm.importar(Json.escribir(Json.obj("elements", elementos)), null, new ImportadorOsm.Opciones()).red();
        ProyeccionLocal p = resultado.proyeccion();
        List<DatosAbiertos.Punto> puntos = List.of(
                new DatosAbiertos.Punto(lat(2) + 0.00005, lon(2) + 0.00005, Map.of()),   // a ~7 m de N1022
                new DatosAbiertos.Punto(lat(1) + 0.00001, lon(1), Map.of()),            // ya tenía semáforo en OSM
                new DatosAbiertos.Punto(lat(0) + 0.0006, lon(0) + 0.0006, Map.of()));   // en mitad de una cuadra: nadie cerca
        assertEquals(2, ImportadorOsm.marcarSemaforos(red, p, puntos, 35));
        assertTrue(red.interseccion("N" + id(2, 2)).semaforizada());
    }

    @Test @DisplayName("La red importada se puede simular: salen y llegan vehículos")
    void simularRedImportada() {
        Simulacion sim = new Simulacion(resultado.red(), Config.inteligente(1, 25));
        sim.avanzar(400);
        assertTrue(sim.estadisticas().completados() > 20, "completados: " + sim.estadisticas().completados());
        assertEquals(sim.estadisticas().generados(), sim.estadisticas().completados() + sim.particularesEnVia() + sim.vehiculosEnEspera());
    }

    @Test @DisplayName("Guardar y leer la red conserva todo, con coordenadas de precisión de centímetros")
    void jsonDeLaRed() {
        for (RedVial original : new RedVial[] {resultado.red(), RedCali.crear()}) {
            ProyeccionLocal p = original == resultado.red() ? resultado.proyeccion() : null;
            RedVialJson.Cargada c = RedVialJson.leer(RedVialJson.escribir(original, p));
            assertEquals(original.intersecciones().size(), c.red().intersecciones().size());
            assertEquals(original.vias().size(), c.red().vias().size());
            for (Interseccion i : original.intersecciones()) {
                Interseccion j = c.red().interseccion(i.id());
                assertEquals(i.x(), j.x(), 1e-6); assertEquals(i.tipo(), j.tipo()); assertEquals(i.semaforizada(), j.semaforizada()); assertEquals(i.servicios(), j.servicios());
                if (!Double.isNaN(i.latitud())) { assertEquals(i.latitud(), j.latitud(), 1e-7); assertEquals(i.longitud(), j.longitud(), 1e-7); }
            }
            for (Via v : original.vias()) {
                Via w = c.red().via(v.id());
                assertEquals(v.longitud(), w.longitud(), 1e-3); assertEquals(v.velocidadMaxKmh(), w.velocidadMaxKmh(), 1e-6); assertEquals(v.puntos().length, w.puntos().length);
            }
            assertEquals(p != null, c.proyeccion() != null);
        }
    }

    @Test @DisplayName("Proyección: ida y vuelta entre grados y metros")
    void proyeccion() {
        ProyeccionLocal p = new ProyeccionLocal(3.4516, -76.5320);
        assertEquals(0, p.x(-76.5320), 1e-9); assertEquals(0, p.y(3.4516), 1e-9);
        double x = p.x(-76.5300), y = p.y(3.4540);
        assertEquals(-76.5300, p.lon(x), 1e-9); assertEquals(3.4540, p.lat(y), 1e-9);
        assertTrue(x > 0, "al este es x positivo"); assertTrue(y < 0, "al norte es y negativo (como la pantalla)");
        assertEquals(222.4, Math.hypot(x, y) > 0 ? x : 0, 1.0, "0,002° de longitud a esta latitud son ~222 m");
    }

    @Test @DisplayName("Índice espacial: igual que buscar en todas las intersecciones, en 2.000 consultas")
    void indiceEspacial() {
        Random r = new Random(1);
        List<Interseccion> todas = new ArrayList<>();
        IndiceEspacial idx = new IndiceEspacial(100);
        for (int i = 0; i < 600; i++) { Interseccion n = new Interseccion("n" + i, "n" + i, r.nextDouble() * 3000 - 1500, r.nextDouble() * 3000 - 1500, TipoNodo.INTERIOR); todas.add(n); idx.agregar(n); }
        for (int q = 0; q < 2000; q++) {
            double x = r.nextDouble() * 3600 - 1800, y = r.nextDouble() * 3600 - 1800, radio = 20 + r.nextDouble() * 250;
            Interseccion mejor = null;
            double md = radio;
            for (Interseccion n : todas) { double d = Math.hypot(n.x() - x, n.y() - y); if (d <= md) { md = d; mejor = n; } }
            Interseccion obtenida = idx.masCercana(x, y, radio);
            assertEquals(mejor == null ? null : mejor.id(), obtenida == null ? null : obtenida.id());
        }
    }

    @Test @DisplayName("Datos abiertos: CSV con separador «;» y decimales con coma, y GeoJSON de puntos")
    void leerDatosAbiertos() {
        String csv = "\uFEFFFecha del hecho;Gravedad;Latitud;Longitud;Barrio\n2023-05-04;Con muertos;3,4521;-76,5330;Centro\n2022-11-30;Con heridos;3,4530;-76,5310;\"San Antonio; alto\"\n2021-01-01;Solo daños;sin dato;-76,53;Centro\n2020-02-02;Solo daños;999;-76,5;Centro\n";
        List<DatosAbiertos.Punto> p = DatosAbiertos.leerPuntos(csv);
        assertEquals(2, p.size(), "se descartan la fila sin coordenadas y la de coordenadas imposibles");
        assertEquals(3.4521, p.get(0).lat(), 1e-9); assertEquals(-76.5330, p.get(0).lon(), 1e-9);
        assertEquals("San Antonio; alto", p.get(1).propiedades().get("barrio"), "las comillas protegen el separador");
        List<DatosAbiertos.Accidente> a = DatosAbiertos.aAccidentes(p);
        assertEquals(2023, a.get(0).anio()); assertEquals(3, a.get(0).gravedad()); assertEquals(2, a.get(1).gravedad());
        String geo = "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-76.5320,3.4516]},\"properties\":{\"Año\":2024,\"Clase\":\"Atropello con lesionados\"}},"
                + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1]]},\"properties\":{}}]}";
        List<DatosAbiertos.Punto> g = DatosAbiertos.leerPuntos(geo);
        assertEquals(1, g.size(), "solo se leen puntos");
        assertEquals(3.4516, g.get(0).lat(), 1e-9);
        DatosAbiertos.Accidente acc = DatosAbiertos.aAccidentes(g).get(0);
        assertEquals(2024, acc.anio()); assertEquals(2, acc.gravedad());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DatosAbiertos.leerPuntos("a,b\n1,2\n"));
        assertTrue(e.getMessage().contains("latitud"));
    }

    @Test @DisplayName("Puntos críticos: ranking por puntaje (1 daños, 3 heridos, 10 muertos) con árbol AVL")
    void puntosCriticos() {
        RedVial red = resultado.red();
        ProyeccionLocal p = resultado.proyeccion();
        List<DatosAbiertos.Accidente> acc = new ArrayList<>();
        acc.add(new DatosAbiertos.Accidente(lat(1) + 0.00003, lon(1), 2023, 1));
        acc.add(new DatosAbiertos.Accidente(lat(1), lon(1) - 0.00003, 2023, 2));
        acc.add(new DatosAbiertos.Accidente(lat(1) - 0.00002, lon(1) + 0.00002, 2024, 3));  // 1 + 3 + 10 = 14 en N1011
        acc.add(new DatosAbiertos.Accidente(lat(2), lon(2), 2022, 2));
        acc.add(new DatosAbiertos.Accidente(lat(2), lon(2) + 0.00004, 2022, 2));              // 6 en N1022
        acc.add(new DatosAbiertos.Accidente(lat(0) + 0.0006, lon(0) + 0.0006, 2021, 3));      // a mitad de cuadra: fuera
        acc.add(new DatosAbiertos.Accidente(4.0, -76.0, 2021, 3));                            // lejos de la red
        PuntosCriticos.Resultado r = PuntosCriticos.calcular(red, p, acc, 40, 5);
        assertEquals(5, r.asignados()); assertEquals(2, r.fueraDeRed());
        assertEquals(2, r.ranking().size());
        assertEquals("N" + id(1, 1), r.ranking().get(0).interseccionId()); assertEquals(14, r.ranking().get(0).puntaje()); assertEquals(3, r.ranking().get(0).accidentes());
        assertEquals(6, r.ranking().get(1).puntaje());
        assertEquals(5, r.calor().size());
        assertEquals(1, PuntosCriticos.calcular(red, p, acc, 40, 1).ranking().size());
    }

    @Test @DisplayName("El importador entiende el JSON tal como lo entrega Overpass (con espacios, enteros y campos extra)")
    void formatoOverpassReal() {
        String json = """
            {
              "version": 0.6,
              "generator": "Overpass API 0.7.62.1 (test)",
              "osm3s": {"timestamp_osm_base": "2026-11-01T00:00:00Z", "copyright": "The data included in this document is from www.openstreetmap.org. The data is made available under ODbL."},
              "elements": [
                {"type": "node", "id": 1, "lat": 3.45000, "lon": -76.53500},
                {"type": "node", "id": 2, "lat": 3.45000, "lon": -76.53300, "tags": {"highway": "traffic_signals"}},
                {"type": "node", "id": 3, "lat": 3.45000, "lon": -76.53100},
                {"type": "way", "id": 10, "nodes": [1, 2, 3], "tags": {"highway": "primary", "name": "Avenida Prueba", "maxspeed": "60", "lanes": "2"}}
              ]
            }""";
        ImportadorOsm.Resultado r = ImportadorOsm.importar(json, null, new ImportadorOsm.Opciones());
        assertEquals(3, r.red().intersecciones().size(), "el semáforo parte la calle en dos tramos");
        assertEquals(4, r.red().vias().size(), "dos tramos en doble sentido");
        assertEquals(1, r.semaforos());
        assertEquals(60, r.red().vias().get(0).velocidadMaxKmh(), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> ImportadorOsm.importar("{\"elements\":[]}", null, new ImportadorOsm.Opciones()));
    }

    @Test @DisplayName("Las consultas Overpass piden las calles y los servicios dentro del cuadro indicado")
    void consultas() {
        String q = ImportadorOsm.consultaCalles(3.43, -76.55, 3.47, -76.51);
        assertTrue(q.contains("3.43,-76.55,3.47,-76.51") && q.contains("highway") && q.contains("out body"));
        String s = ImportadorOsm.consultaServicios(3.43, -76.55, 3.47, -76.51);
        assertTrue(s.contains("hospital|fire_station|police") && s.contains("out center"));
    }
}
