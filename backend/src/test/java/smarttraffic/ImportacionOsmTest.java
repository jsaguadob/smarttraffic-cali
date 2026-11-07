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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.red.RedCali;
import smarttraffic.util.Json;
import smarttraffic.web.GestorSimulacion;
import smarttraffic.web.Seguridad;
import smarttraffic.web.Servidor;
import smarttraffic.web.ServicioRed;

/** La importación del mapa real corre en el servidor; aquí se prueba contra un «OpenStreetMap» falso que arma la propia prueba. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ImportacionOsmTest {
    static final String CALLES = """
        {"elements":[
          {"type":"node","id":1,"lat":3.4500,"lon":-76.5350},{"type":"node","id":2,"lat":3.4500,"lon":-76.5338,"tags":{"highway":"traffic_signals"}},
          {"type":"node","id":3,"lat":3.4500,"lon":-76.5326},{"type":"node","id":4,"lat":3.4512,"lon":-76.5338},
          {"type":"way","id":10,"nodes":[1,2,3],"tags":{"highway":"secondary","name":"Calle Uno"}},
          {"type":"way","id":11,"nodes":[2,4],"tags":{"highway":"residential","name":"Carrera Dos"}}
        ]}""";
    static final String SERVICIOS = "{\"elements\":[{\"type\":\"node\",\"id\":9,\"lat\":3.4512,\"lon\":-76.5338,\"tags\":{\"amenity\":\"hospital\",\"name\":\"Hospital X\"}}]}";

    static final AtomicReference<String> modo = new AtomicReference<>("ok");
    static final AtomicReference<String> ultimaConsulta = new AtomicReference<>("");
    static HttpServer osm;
    static Servidor servidor;
    static GestorSimulacion gestor;
    static Repositorio repo;
    static String base, tokenAdmin, tokenOperador;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void iniciar() throws Exception {
        System.setProperty("smarttraffic.pbkdf2", "1000");
        osm = HttpServer.create(new InetSocketAddress(0), 0);
        osm.createContext("/api/interpreter", ex -> {
            String cuerpo = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String consulta = URLDecoder.decode(cuerpo.replaceFirst("^data=", ""), StandardCharsets.UTF_8);
            ultimaConsulta.set(consulta);
            try {
                if (modo.get().equals("lento")) Thread.sleep(700);
            } catch (InterruptedException ignorar) {
                Thread.currentThread().interrupt();
            }
            if (modo.get().equals("429")) { ex.sendResponseHeaders(429, -1); ex.close(); return; }
            String json = modo.get().equals("vacio") ? "{\"elements\":[]}" : consulta.contains("out center") ? SERVICIOS : CALLES;
            byte[] b = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        osm.start();
        repo = new Repositorio("jdbc:h2:mem:osm" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        repo.crearUsuario("Admin", "admin@t.test", Seguridad.hashClave("Prueba2026ok"), "ADMINISTRADOR");
        repo.crearUsuario("Operador", "operador@t.test", Seguridad.hashClave("Prueba2026ok"), "OPERADOR");
        var red = RedCali.crear();
        repo.guardarRedActiva(red, null);
        gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, null)).red());
        Servidor.Opciones o = new Servidor.Opciones();
        o.puerto = 0;
        o.dirEstatico = System.getProperty("java.io.tmpdir");
        o.overpassUrl = "http://localhost:" + osm.getAddress().getPort() + "/api/interpreter";
        o.overpassIntentos = 1;
        o.overpassEsperaMs = 0;
        servidor = new Servidor(o, repo, gestor, new ServicioRed(repo, gestor, red, null));
        servidor.iniciar();
        base = "http://localhost:" + servidor.puerto();
        tokenAdmin = (String) llamar("POST", "/api/auth/login", null, Json.obj("correo", "admin@t.test", "clave", "Prueba2026ok")).objeto().get("token");
        tokenOperador = (String) llamar("POST", "/api/auth/login", null, Json.obj("correo", "operador@t.test", "clave", "Prueba2026ok")).objeto().get("token");
    }

    @AfterAll
    static void cerrar() {
        servidor.close();
        gestor.close();
        osm.stop(0);
    }

    record Resp(int estado, String texto) {
        Map<String, Object> objeto() { return Json.leerObjeto(texto); }
    }

    static Resp llamar(String metodo, String ruta, String token, Object cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (cuerpo != null) b.header("Content-Type", "application/json");
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo instanceof String s ? s : Json.escribir(cuerpo)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body());
    }

    /** Espera a que la importación termine y devuelve su estado final. */
    static Map<String, Object> esperarFin() throws Exception {
        long fin = System.currentTimeMillis() + 20000;
        while (System.currentTimeMillis() < fin) {
            Map<String, Object> e = llamar("GET", "/api/red/importar-osm", tokenAdmin, null).objeto();
            if (e.get("estado").equals("LISTO") || e.get("estado").equals("ERROR")) return e;
            Thread.sleep(100);
        }
        return fail("la importación no terminó a tiempo");
    }

    @Test @Order(1) @DisplayName("Solo el administrador puede importar el mapa")
    void permisos() throws Exception {
        assertEquals(401, llamar("POST", "/api/red/importar-osm", null, null).estado());
        assertEquals(403, llamar("POST", "/api/red/importar-osm", tokenOperador, null).estado());
        assertEquals(403, llamar("GET", "/api/red/importar-osm", tokenOperador, null).estado());
        assertEquals("OCIOSO", llamar("GET", "/api/red/importar-osm", tokenAdmin, null).objeto().get("estado"));
    }

    @Test @Order(2) @DisplayName("Zonas inválidas o demasiado grandes se rechazan antes de descargar nada")
    void zonas() throws Exception {
        String antes = ultimaConsulta.get();
        assertEquals(400, llamar("POST", "/api/red/importar-osm", tokenAdmin, Json.obj("sur", 3.5, "oeste", -76.5, "norte", 3.4, "este", -76.4)).estado(), "sur mayor que norte");
        assertEquals(400, llamar("POST", "/api/red/importar-osm", tokenAdmin, Json.obj("sur", 3.0, "oeste", -77.0, "norte", 4.0, "este", -76.0)).estado(), "demasiado grande");
        assertEquals(400, llamar("POST", "/api/red/importar-osm", tokenAdmin, Json.obj("sur", 3.4)).estado(), "faltan valores");
        assertEquals(400, llamar("POST", "/api/red/importar-osm", tokenAdmin, Json.obj("sur", 95, "oeste", -76.5, "norte", 96, "este", -76.4)).estado(), "fuera de rango");
        assertEquals(antes, ultimaConsulta.get(), "no se llamó al servicio de mapas");
        assertEquals("OCIOSO", llamar("GET", "/api/red/importar-osm", tokenAdmin, null).objeto().get("estado"));
    }

    @Test @Order(3) @DisplayName("Si OpenStreetMap está ocupado (429) se informa con claridad y la red actual no se toca")
    void ocupado() throws Exception {
        modo.set("429");
        Resp r = llamar("POST", "/api/red/importar-osm", tokenAdmin, null);
        assertEquals(202, r.estado());
        Map<String, Object> fin = esperarFin();
        assertEquals("ERROR", fin.get("estado"));
        assertTrue(fin.get("mensaje").toString().contains("429"), fin.toString());
        assertEquals(18, ((List<?>) llamar("GET", "/api/red", tokenAdmin, null).objeto().get("intersecciones")).size(), "la red de antes sigue activa");
        modo.set("vacio");
        llamar("POST", "/api/red/importar-osm", tokenAdmin, null);
        Map<String, Object> vacio = esperarFin();
        assertEquals("ERROR", vacio.get("estado"));
        assertTrue(vacio.get("mensaje").toString().contains("no contienen calles"), vacio.toString());
    }

    @Test @Order(4) @DisplayName("Mientras una importación está en curso, otra orden se rechaza")
    void enCurso() throws Exception {
        modo.set("lento");
        assertEquals(202, llamar("POST", "/api/red/importar-osm", tokenAdmin, null).estado());
        Resp segunda = llamar("POST", "/api/red/importar-osm", tokenAdmin, null);
        assertEquals(409, segunda.estado());
        assertTrue(segunda.objeto().get("error").toString().contains("en curso"));
        String durante = llamar("GET", "/api/red/importar-osm", tokenAdmin, null).objeto().get("estado").toString();
        assertTrue(durante.equals("DESCARGANDO") || durante.equals("PROCESANDO"), durante);
        assertEquals("LISTO", esperarFin().get("estado"));
        modo.set("ok");
    }

    @Test @Order(5) @DisplayName("Importación completa: el mapa descargado reemplaza la red, queda guardado y la simulación lo usa")
    void completa() throws Exception {
        modo.set("ok");
        assertEquals(202, llamar("POST", "/api/red/importar-osm", tokenAdmin, Json.obj("sur", 3.4420, "oeste", -76.5420, "norte", 3.4620, "este", -76.5220)).estado());
        Map<String, Object> fin = esperarFin();
        assertEquals("LISTO", fin.get("estado"), fin.toString());
        @SuppressWarnings("unchecked") Map<String, Object> res = (Map<String, Object>) fin.get("resultado");
        assertEquals(4.0, ((Number) res.get("intersecciones")).doubleValue());
        assertEquals(1.0, ((Number) res.get("semaforos")).doubleValue());
        assertEquals(1.0, ((Number) res.get("bases")).doubleValue());
        Map<String, Object> red = llamar("GET", "/api/red", tokenAdmin, null).objeto();
        assertEquals(4, ((List<?>) red.get("intersecciones")).size());
        assertNotNull(red.get("origen"), "la red importada conserva su ubicación real");
        assertEquals(Boolean.TRUE, llamar("GET", "/api/config", null, null).objeto().get("coordenadasReales"));
        assertEquals(4, repo.cargarRedActiva().orElseThrow().red().intersecciones().size(), "quedó guardada en la base de datos");
        Map<?, ?> kpi = (Map<?, ?>) llamar("GET", "/api/sim/estado", tokenAdmin, null).objeto().get("kpi");
        assertEquals(4.0, ((Number) kpi.get("intersecciones")).doubleValue(), "la simulación ya corre sobre el mapa nuevo");
        assertTrue(ultimaConsulta.get().contains("hospital") || ultimaConsulta.get().contains("out"), "se consultó el servicio de mapas");
        assertTrue(repo.contarAuditoria("red_importar_osm") >= 2, "queda en la auditoría");
    }
}
