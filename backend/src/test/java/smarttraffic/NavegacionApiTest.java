package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import smarttraffic.datos.ProyeccionLocal;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.red.RedCali;
import smarttraffic.util.Json;
import smarttraffic.web.GestorSimulacion;
import smarttraffic.web.Seguridad;
import smarttraffic.web.Servidor;
import smarttraffic.web.ServicioRed;

/** Búsqueda de lugares y cálculo de rutas por la API, con un «Nominatim» falso que arma la propia prueba. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NavegacionApiTest {
    static final ProyeccionLocal PROY = new ProyeccionLocal(3.4516, -76.5320);
    static HttpServer nominatim;
    static final AtomicInteger consultasNominatim = new AtomicInteger();
    static final AtomicReference<String> modoNominatim = new AtomicReference<>("ok");
    static final List<String> cabecerasUserAgent = new CopyOnWriteArrayList<>();
    static final List<String> consultasCrudas = new CopyOnWriteArrayList<>();
    static Servidor servidor;
    static GestorSimulacion gestor;
    static String base, tokenAdmin, tokenOperador, tokenAnalista;
    static final HttpClient http = HttpClient.newHttpClient();
    static double n11x, n11y;

    record Resp(int estado, String texto) {
        Map<String, Object> objeto() { return Json.leerObjeto(texto); }
    }

    @BeforeAll
    static void iniciar() throws Exception {
        System.setProperty("smarttraffic.pbkdf2", "1000");
        var red = RedCali.crear();
        var n11 = red.interseccion("N11");
        n11x = n11.x();
        n11y = n11.y();
        nominatim = HttpServer.create(new InetSocketAddress(0), 0);
        nominatim.createContext("/search", ex -> {
            consultasNominatim.incrementAndGet();
            cabecerasUserAgent.add(String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            consultasCrudas.add(URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            if (modoNominatim.get().equals("503")) { ex.sendResponseHeaders(503, -1); ex.close(); return; }
            // un lugar dentro del mapa (en N11), otro lejos (fuera de la zona) y uno sin coordenadas
            String json = "[{\"lat\":\"" + PROY.lat(n11y) + "\",\"lon\":\"" + PROY.lon(n11x) + "\",\"name\":\"Clínica Ficticia\",\"display_name\":\"Clínica Ficticia, Calle 13, Cali, Valle del Cauca, Colombia\"},"
                    + "{\"lat\":\"4.9\",\"lon\":\"-76.0\",\"name\":\"Lejos\",\"display_name\":\"Lejos, otra ciudad\"},{\"name\":\"Roto\",\"display_name\":\"Roto\"}]";
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        nominatim.start();
        var repo = new Repositorio("jdbc:h2:mem:nav" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        for (String[] u : new String[][] {{"Admin", "admin@t.test", "ADMINISTRADOR"}, {"Operador", "operador@t.test", "OPERADOR"}, {"Analista", "analista@t.test", "ANALISTA"}}) repo.crearUsuario(u[0], u[1], Seguridad.hashClave("Prueba2026ok"), u[2]);
        repo.guardarRedActiva(red, PROY);
        gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, PROY)).red());
        Servidor.Opciones o = new Servidor.Opciones();
        o.puerto = 0;
        o.dirEstatico = System.getProperty("java.io.tmpdir");
        o.nominatimUrl = "http://localhost:" + nominatim.getAddress().getPort() + "/search";
        servidor = new Servidor(o, repo, gestor, new ServicioRed(repo, gestor, red, PROY));
        servidor.iniciar();
        base = "http://localhost:" + servidor.puerto();
        tokenAdmin = login("admin@t.test");
        tokenOperador = login("operador@t.test");
        tokenAnalista = login("analista@t.test");
    }

    @AfterAll
    static void cerrar() {
        servidor.close();
        gestor.close();
        nominatim.stop(0);
    }

    static String login(String correo) throws Exception {
        return (String) llamar("POST", "/api/auth/login", null, Json.obj("correo", correo, "clave", "Prueba2026ok")).objeto().get("token");
    }

    static Resp llamar(String metodo, String ruta, String token, Object cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (cuerpo != null) b.header("Content-Type", "application/json");
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(Json.escribir(cuerpo)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    static String q(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lista(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @Test @Order(1) @DisplayName("Búsqueda local: por calle o intersección, sin tildes ni mayúsculas, todas las palabras, y exige sesión")
    void busquedaLocal() throws Exception {
        assertEquals(401, llamar("GET", "/api/lugares?q=calle", null, null).estado());
        assertEquals(400, llamar("GET", "/api/lugares?q=a", tokenAnalista, null).estado());
        assertEquals(400, llamar("GET", "/api/lugares?q=" + q("x".repeat(200)), tokenAnalista, null).estado());
        Resp r = llamar("GET", "/api/lugares?q=" + q("CALLE 13"), tokenAnalista, null);
        assertEquals(200, r.estado(), r.texto());
        List<Map<String, Object>> locales = lista(r.objeto().get("locales"));
        assertFalse(locales.isEmpty());
        assertEquals("calle", locales.get(0).get("tipo"), "la calle sale primero");
        assertEquals("Calle 13", locales.get(0).get("nombre"));
        assertTrue(locales.stream().anyMatch(l -> l.get("nombre").toString().equals("Calle 13 con Carrera 15") && l.get("tipo").equals("interseccion")));
        assertTrue(locales.stream().allMatch(l -> l.containsKey("x") && l.containsKey("lat") && l.containsKey("lon")), "con ubicación real también trae latitud y longitud");
        // sin tildes: «Hospital Universitario» se encuentra escribiendo «hospítal univers»
        List<Map<String, Object>> hosp = lista(llamar("GET", "/api/lugares?q=" + q("hospítal univers"), tokenAnalista, null).objeto().get("locales"));
        assertEquals("servicio", hosp.get(0).get("tipo"));
        // varias palabras en cualquier orden
        List<Map<String, Object>> varias = lista(llamar("GET", "/api/lugares?q=" + q("carrera 24 calle 9"), tokenAnalista, null).objeto().get("locales"));
        assertTrue(varias.stream().anyMatch(l -> l.get("nombre").toString().equals("Calle 9 con Carrera 24")), varias.toString());
        assertTrue(lista(llamar("GET", "/api/lugares?q=" + q("zzzz inexistente"), tokenAnalista, null).objeto().get("locales")).isEmpty());
        assertEquals(0, consultasNominatim.get(), "la búsqueda local nunca llama al servicio externo");
    }

    @Test @Order(2) @DisplayName("Buscar lugares en OpenStreetMap: se identifica, acota la zona, deja fuera lo lejano y usa caché")
    void busquedaExterna() throws Exception {
        Resp r = llamar("GET", "/api/lugares?externo=1&q=" + q("clínica ficticia"), tokenAnalista, null);
        assertEquals(200, r.estado(), r.texto());
        List<Map<String, Object>> ext = lista(r.objeto().get("externos"));
        assertEquals(1, ext.size(), "solo el lugar que cae dentro de la zona del mapa");
        assertEquals("Clínica Ficticia", ext.get(0).get("nombre"));
        assertEquals(n11x, ((Number) ext.get(0).get("x")).doubleValue(), 1.0, "se convierte de latitud y longitud a metros del mapa");
        assertEquals(n11y, ((Number) ext.get(0).get("y")).doubleValue(), 1.0);
        assertTrue(ext.get(0).get("detalle").toString().contains("Cali"));
        assertEquals(1, consultasNominatim.get());
        assertTrue(cabecerasUserAgent.get(0).startsWith("SmartTraffic/1.0"), "se identifica ante el servicio: " + cabecerasUserAgent.get(0));
        String cruda = consultasCrudas.get(0);
        assertTrue(cruda.contains("bounded=1") && cruda.contains("viewbox=") && cruda.contains("countrycodes=co") && cruda.contains("q=clínica ficticia"), cruda);
        // la misma búsqueda otra vez sale de la caché
        llamar("GET", "/api/lugares?externo=1&q=" + q("clínica ficticia"), tokenOperador, null);
        assertEquals(1, consultasNominatim.get(), "la repetición no vuelve a consultar el servicio externo");
        // dos búsquedas distintas seguidas: se espera al menos un segundo entre consultas (política de uso)
        long t0 = System.currentTimeMillis();
        llamar("GET", "/api/lugares?externo=1&q=" + q("otra cosa"), tokenAnalista, null);
        llamar("GET", "/api/lugares?externo=1&q=" + q("y otra distinta"), tokenAnalista, null);
        assertTrue(System.currentTimeMillis() - t0 >= 1000, "máximo una consulta por segundo");
        assertEquals(3, consultasNominatim.get());
    }

    @Test @Order(3) @DisplayName("Si el servicio externo falla, se avisa y la búsqueda local sigue funcionando")
    void externoFalla() throws Exception {
        modoNominatim.set("503");
        Map<String, Object> r = llamar("GET", "/api/lugares?externo=1&q=" + q("calle 5"), tokenAnalista, null).objeto();
        assertTrue(r.get("aviso").toString().contains("503"), r.toString());
        assertFalse(lista(r.get("locales")).isEmpty(), "los resultados de la red siguen apareciendo");
        assertTrue(lista(r.get("externos")).isEmpty());
        modoNominatim.set("ok");
    }

    @Test @Order(4) @DisplayName("Ruta: acepta x/y, latitud/longitud o una intersección, y devuelve opciones con pasos, tiempos y geometría")
    void ruta() throws Exception {
        assertEquals(401, llamar("POST", "/api/ruta", null, Json.obj("origen", Json.obj("nodo", "N00"), "destino", Json.obj("nodo", "N33"))).estado());
        Resp r = llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", Json.obj("nodo", "N00"), "destino", Json.obj("nodo", "N33"), "alternativas", 2));
        assertEquals(200, r.estado(), r.texto());
        List<Map<String, Object>> ops = lista(r.objeto().get("opciones"));
        assertFalse(ops.isEmpty());
        Map<String, Object> mejor = ops.get(0);
        assertEquals("Más rápida", mejor.get("etiqueta"));
        assertTrue(((Number) mejor.get("tiempoActual")).doubleValue() >= ((Number) mejor.get("tiempoLibre")).doubleValue());
        assertTrue(((List<?>) mejor.get("geometria")).size() >= 8 && ((List<?>) mejor.get("geometria")).size() % 2 == 0);
        List<Map<String, Object>> pasos = lista(mejor.get("pasos"));
        assertEquals("salida", pasos.get(0).get("giro")); assertEquals("llegada", pasos.get(pasos.size() - 1).get("giro"));
        assertTrue(pasos.stream().allMatch(p -> !p.get("texto").toString().isBlank()));
        assertTrue(r.objeto().get("aviso").toString().contains("estimaciones"));
        // con latitud y longitud: es la misma ruta que con x e y
        var n0 = RedCali.crear().interseccion("N00");
        var n3 = RedCali.crear().interseccion("N33");
        Resp geo = llamar("POST", "/api/ruta", tokenOperador, Json.obj("origen", Json.obj("lat", PROY.lat(n0.y()), "lon", PROY.lon(n0.x())), "destino", Json.obj("lat", PROY.lat(n3.y()), "lon", PROY.lon(n3.x())), "alternativas", 0));
        assertEquals(200, geo.estado(), geo.texto());
        assertEquals(1, lista(geo.objeto().get("opciones")).size(), "sin alternativas, una sola opción");
        Resp xy = llamar("POST", "/api/ruta", tokenAdmin, Json.obj("origen", Json.obj("x", n0.x(), "y", n0.y()), "destino", Json.obj("x", n3.x(), "y", n3.y()), "alternativas", 0));
        assertEquals(lista(geo.objeto().get("opciones")).get(0).get("distancia"), lista(xy.objeto().get("opciones")).get(0).get("distancia"));
    }

    @Test @Order(5) @DisplayName("Ruta: peticiones inválidas se rechazan con un mensaje claro")
    void rutaInvalida() throws Exception {
        Object ok = Json.obj("nodo", "N00");
        assertEquals(400, llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", ok)).estado(), "falta el destino");
        assertEquals(400, llamar("POST", "/api/ruta", tokenAnalista, Json.obj("destino", ok)).estado(), "falta el origen");
        assertEquals(400, llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", ok, "destino", ok)).estado(), "mismo lugar");
        assertEquals(400, llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", ok, "destino", Json.obj("nodo", "NO-EXISTE"))).estado());
        assertEquals(400, llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", ok, "destino", Json.obj("nombre", "algo"))).estado());
        Resp lejos = llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", ok, "destino", Json.obj("x", 9000, "y", 9000)));
        assertEquals(400, lejos.estado());
        assertTrue(lejos.objeto().get("error").toString().contains("fuera de las calles"));
        assertEquals(400, llamar("POST", "/api/ruta", tokenAnalista, Json.obj("origen", ok, "destino", Json.obj("nodo", "N33"), "alternativas", 9)).estado());
    }

    @Test @Order(6) @DisplayName("Un accidente cambia la ruta: la nueva evita la calle cerrada")
    void accidenteCambiaLaRuta() throws Exception {
        Object cuerpo = Json.obj("origen", Json.obj("nodo", "N00"), "destino", Json.obj("nodo", "N33"), "alternativas", 0);
        Map<String, Object> antes = lista(llamar("POST", "/api/ruta", tokenAnalista, cuerpo).objeto().get("opciones")).get(0);
        @SuppressWarnings("unchecked") List<String> vias = (List<String>) antes.get("vias");
        String cortada = vias.get(vias.size() / 2);
        assertEquals(201, llamar("POST", "/api/sim/incidentes", tokenOperador, Json.obj("via", cortada, "fraccion", 0.5, "gravedad", 3)).estado());
        Map<String, Object> despues = lista(llamar("POST", "/api/ruta", tokenAnalista, cuerpo).objeto().get("opciones")).get(0);
        @SuppressWarnings("unchecked") List<String> nuevas = (List<String>) despues.get("vias");
        assertFalse(nuevas.contains(cortada), "la ruta ya no pasa por la calle bloqueada");
        assertTrue(((Number) despues.get("tiempoActual")).doubleValue() >= ((Number) antes.get("tiempoActual")).doubleValue() - 1);
    }
}
