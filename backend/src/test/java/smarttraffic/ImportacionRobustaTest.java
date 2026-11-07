package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.red.RedCali;
import smarttraffic.util.Json;
import smarttraffic.web.GestorSimulacion;
import smarttraffic.web.Seguridad;
import smarttraffic.web.Servidor;
import smarttraffic.web.ServicioRed;

/** Cuando el servicio de mapas falla (servidor caído, sin internet), el sistema lo explica y ofrece alternativas. */
class ImportacionRobustaTest {
    static final String CALLES = ImportacionOsmTest.CALLES;
    static HttpServer vivo;
    static int puertoMuerto;
    static final HttpClient http = HttpClient.newHttpClient();
    static final java.util.List<AutoCloseable> cierres = new java.util.ArrayList<>();

    record Sitio(Servidor servidor, String base, String admin, String operador) {}

    @BeforeAll
    static void iniciar() throws Exception {
        System.setProperty("smarttraffic.pbkdf2", "1000");
        vivo = HttpServer.create(new InetSocketAddress(0), 0);
        vivo.createContext("/", ex -> {
            String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String consulta = URLDecoder.decode(cuerpo.replaceFirst("^data=", ""), StandardCharsets.UTF_8);
            byte[] b = (ex.getRequestMethod().equals("POST") ? (consulta.contains("out center") ? "{\"elements\":[]}" : CALLES) : "OK").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        vivo.start();
        try (ServerSocket s = new ServerSocket(0)) { puertoMuerto = s.getLocalPort(); } // un puerto donde no escucha nadie
    }

    @AfterAll
    static void cerrar() throws Exception {
        vivo.stop(0);
        for (AutoCloseable c : cierres) c.close();
    }

    static String url(int puerto, String ruta) {
        return "http://localhost:" + puerto + ruta;
    }

    static Sitio sitio(String overpass) throws Exception {
        var repo = new Repositorio("jdbc:h2:mem:rob" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        repo.crearUsuario("Admin", "admin@t.test", Seguridad.hashClave("Prueba2026ok"), "ADMINISTRADOR");
        repo.crearUsuario("Operador", "operador@t.test", Seguridad.hashClave("Prueba2026ok"), "OPERADOR");
        var red = RedCali.crear();
        repo.guardarRedActiva(red, null);
        var gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, null)).red());
        Servidor.Opciones o = new Servidor.Opciones();
        o.puerto = 0;
        o.dirEstatico = System.getProperty("java.io.tmpdir");
        o.overpassUrl = overpass;
        o.overpassIntentos = 1;
        o.overpassEsperaMs = 0;
        o.nominatimUrl = url(vivo.getAddress().getPort(), "/search");
        o.teselaPrueba = url(vivo.getAddress().getPort(), "/0/0/0.png");
        var s = new Servidor(o, repo, gestor, new ServicioRed(repo, gestor, red, null));
        s.iniciar();
        cierres.add(s::close);
        cierres.add(gestor::close);
        String base = "http://localhost:" + s.puerto();
        return new Sitio(s, base, token(base, "admin@t.test"), token(base, "operador@t.test"));
    }

    static String token(String base, String correo) throws Exception {
        return (String) llamar(base, "POST", "/api/auth/login", null, Json.escribir(Json.obj("correo", correo, "clave", "Prueba2026ok"))).objeto().get("token");
    }

    record Resp(int estado, String texto) {
        Map<String, Object> objeto() { return Json.leerObjeto(texto); }
    }

    static Resp llamar(String base, String metodo, String ruta, String token, String cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (cuerpo != null) b.header("Content-Type", "application/json");
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    static Map<String, Object> esperarFin(Sitio s) throws Exception {
        long fin = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < fin) {
            Map<String, Object> e = llamar(s.base(), "GET", "/api/red/importar-osm", s.admin(), null).objeto();
            if (e.get("estado").equals("LISTO") || e.get("estado").equals("ERROR")) return e;
            Thread.sleep(100);
        }
        return fail("no terminó a tiempo");
    }

    @Test @DisplayName("Si el primer servidor de mapas no responde, se prueba el siguiente y la importación sale bien")
    void espejo() throws Exception {
        Sitio s = sitio(url(puertoMuerto, "/api/interpreter") + "," + url(vivo.getAddress().getPort(), "/api/interpreter"));
        assertEquals(202, llamar(s.base(), "POST", "/api/red/importar-osm", s.admin(), null).estado());
        Map<String, Object> fin = esperarFin(s);
        assertEquals("LISTO", fin.get("estado"), fin.toString());
        assertEquals(4, ((List<?>) llamar(s.base(), "GET", "/api/red", s.admin(), null).objeto().get("intersecciones")).size());
    }

    @Test @DisplayName("Si todos fallan, el mensaje dice qué pasó en cada servidor y nunca muestra «null»")
    void todosFallan() throws Exception {
        Sitio s = sitio(url(puertoMuerto, "/api/interpreter") + "," + url(puertoMuerto, "/otro"));
        llamar(s.base(), "POST", "/api/red/importar-osm", s.admin(), null);
        Map<String, Object> fin = esperarFin(s);
        assertEquals("ERROR", fin.get("estado"));
        String m = fin.get("mensaje").toString();
        assertTrue(m.startsWith("No se pudo descargar el mapa."), m);
        assertTrue(m.contains("localhost → la conexión fue rechazada o bloqueada"), m);
        assertTrue(m.split("localhost →").length == 3, "aparecen los dos servidores probados: " + m);
        assertFalse(m.contains("null"), m);
        assertEquals(18, ((List<?>) llamar(s.base(), "GET", "/api/red", s.admin(), null).objeto().get("intersecciones")).size(), "la red de antes sigue activa");
    }

    @Test @DisplayName("Los errores de red se explican por su causa verdadera aunque Java la esconda dentro del error")
    void describirCausas() {
        var conexion = new java.net.ConnectException();
        conexion.initCause(new java.net.UnknownHostException("overpass-api.de"));
        assertTrue(smarttraffic.datos.ImportarCali.describir(conexion).contains("DNS"), "la causa es el DNS, no «conexión rechazada»");
        assertTrue(smarttraffic.datos.ImportarCali.describir(new java.net.ConnectException()).contains("rechazada o bloqueada"));
        assertTrue(smarttraffic.datos.ImportarCali.describir(new java.net.http.HttpTimeoutException("x")).contains("demasiado"));
        assertTrue(smarttraffic.datos.ImportarCali.describir(new java.net.http.HttpConnectTimeoutException("x")).contains("a tiempo"));
        assertTrue(smarttraffic.datos.ImportarCali.describir(new javax.net.ssl.SSLHandshakeException("PKIX failed")).contains("PKIX"));
        assertEquals("EOFException", smarttraffic.datos.ImportarCali.describir(new java.io.EOFException()), "un error sin texto muestra al menos su tipo, nunca «null»");
    }

    @Test @DisplayName("Diagnóstico: dice cuáles servicios alcanza el servidor y cuáles no, con la causa y una explicación")
    void diagnostico() throws Exception {
        Sitio parcial = sitio(url(vivo.getAddress().getPort(), "/api/interpreter") + "," + url(puertoMuerto, "/api/interpreter"));
        assertEquals(403, llamar(parcial.base(), "GET", "/api/red/diagnostico", parcial.operador(), null).estado());
        Map<String, Object> r = llamar(parcial.base(), "GET", "/api/red/diagnostico", parcial.admin(), null).objeto();
        @SuppressWarnings("unchecked") List<Map<String, Object>> pruebas = (List<Map<String, Object>>) r.get("pruebas");
        assertEquals(4, pruebas.size(), "dos servidores de calles, el buscador y las teselas");
        assertEquals(Boolean.TRUE, pruebas.get(0).get("ok")); assertEquals(200.0, ((Number) pruebas.get(0).get("http")).doubleValue());
        assertEquals(Boolean.FALSE, pruebas.get(1).get("ok"));
        assertTrue(pruebas.get(1).get("error").toString().contains("la conexión fue rechazada o bloqueada"), pruebas.get(1).toString());
        assertFalse(((List<?>) pruebas.get(0).get("direcciones")).isEmpty(), "muestra a qué direcciones IP resuelve el nombre");
        assertTrue(r.get("resumen").toString().contains("llega a algunos"), r.get("resumen").toString());

        Sitio sinRed = sitio(url(puertoMuerto, "/api/interpreter"));
        // el buscador y las teselas de prueba apuntan al servidor vivo; se comprueba solo la lectura del resumen con todo caído
        Map<String, Object> todo = llamar(sinRed.base(), "GET", "/api/red/diagnostico", sinRed.admin(), null).objeto();
        @SuppressWarnings("unchecked") List<Map<String, Object>> p2 = (List<Map<String, Object>>) todo.get("pruebas");
        assertEquals(Boolean.FALSE, p2.get(0).get("ok"));
        assertEquals(Boolean.TRUE, p2.get(1).get("ok"));
    }

    @Test @DisplayName("Alternativa sin internet en el servidor: el enlace de descarga y la subida del archivo")
    void subirArchivo() throws Exception {
        Sitio s = sitio(url(puertoMuerto, "/api/interpreter"));
        Resp enlace = llamar(s.base(), "GET", "/api/red/importar-osm/enlace?sur=3.442&oeste=-76.542&norte=3.462&este=-76.522", s.admin(), null);
        assertEquals(200, enlace.estado(), enlace.texto());
        String u = enlace.objeto().get("url").toString();
        assertTrue(u.startsWith(url(puertoMuerto, "/api/interpreter") + "?data="), u);
        String consulta = URLDecoder.decode(u.substring(u.indexOf("data=") + 5), StandardCharsets.UTF_8);
        assertTrue(consulta.contains("3.442,-76.542,3.462,-76.522") && consulta.contains("out body"), consulta);
        assertEquals(400, llamar(s.base(), "GET", "/api/red/importar-osm/enlace?sur=3.0&oeste=-77&norte=4&este=-76", s.admin(), null).estado(), "zona demasiado grande");
        assertEquals(400, llamar(s.base(), "GET", "/api/red/importar-osm/enlace?sur=3.4", s.admin(), null).estado(), "faltan valores");
        assertEquals(403, llamar(s.base(), "GET", "/api/red/importar-osm/enlace?sur=3.442&oeste=-76.542&norte=3.462&este=-76.522", s.operador(), null).estado());

        assertEquals(403, llamar(s.base(), "POST", "/api/red/importar-overpass", s.operador(), CALLES).estado());
        assertEquals(400, llamar(s.base(), "POST", "/api/red/importar-overpass", s.admin(), "esto no es json").estado());
        assertEquals(400, llamar(s.base(), "POST", "/api/red/importar-overpass", s.admin(), "{\"elements\":[]}").estado());
        assertEquals(18, ((List<?>) llamar(s.base(), "GET", "/api/red", s.admin(), null).objeto().get("intersecciones")).size(), "un archivo inservible no cambia la red");
        Resp ok = llamar(s.base(), "POST", "/api/red/importar-overpass", s.admin(), CALLES);
        assertEquals(200, ok.estado(), ok.texto());
        assertEquals("LISTO", ok.objeto().get("estado"));
        Map<String, Object> red = llamar(s.base(), "GET", "/api/red", s.admin(), null).objeto();
        assertEquals(4, ((List<?>) red.get("intersecciones")).size());
        assertNotNull(red.get("origen"), "con ubicación real");
        assertEquals("LISTO", llamar(s.base(), "GET", "/api/red/importar-osm", s.admin(), null).objeto().get("estado"));
    }
}
