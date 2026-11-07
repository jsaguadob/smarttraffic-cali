package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedCali;
import smarttraffic.red.RedVial;
import smarttraffic.util.Json;
import smarttraffic.web.GestorSimulacion;
import smarttraffic.web.Seguridad;
import smarttraffic.web.Servidor;
import smarttraffic.web.ServicioRed;

/** Pruebas de punta a punta: servidor real, base de datos H2 en memoria y peticiones HTTP reales. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiTest {
    static Repositorio repo;
    static GestorSimulacion gestor;
    static ServicioRed servicio;
    static Servidor servidor;
    static String base, secreto = Seguridad.secretoAleatorio();
    static Path estaticos;
    static final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static String tokenAdmin, tokenOperador, tokenAnalista;
    static final String CLAVE = "Prueba2026ok";

    record Resp(int estado, String texto, java.net.http.HttpHeaders cabeceras) {
        Map<String, Object> objeto() { return Json.leerObjeto(texto); }
        @SuppressWarnings("unchecked") List<Object> lista() { return (List<Object>) Json.leer(texto); }
    }

    @BeforeAll
    static void iniciar() throws Exception {
        System.setProperty("smarttraffic.pbkdf2", "1000");
        // Por defecto H2 en memoria. Con SMARTTRAFFIC_TEST_DB_URL (+ _USER y _PASSWORD) se prueba contra PostgreSQL real.
        String urlPg = System.getenv("SMARTTRAFFIC_TEST_DB_URL");
        repo = urlPg != null
                ? new Repositorio(urlPg, System.getenv("SMARTTRAFFIC_TEST_DB_USER"), System.getenv("SMARTTRAFFIC_TEST_DB_PASSWORD"))
                : new Repositorio("jdbc:h2:mem:api" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        repo.crearUsuario("Admin", "admin@t.test", Seguridad.hashClave(CLAVE), "ADMINISTRADOR");
        repo.crearUsuario("Operador", "operador@t.test", Seguridad.hashClave(CLAVE), "OPERADOR");
        repo.crearUsuario("Analista", "analista@t.test", Seguridad.hashClave(CLAVE), "ANALISTA");
        RedVial red = RedCali.crear();
        repo.guardarRedActiva(red, null);
        gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, null)).red());
        servicio = new ServicioRed(repo, gestor, red, null);
        estaticos = Files.createTempDirectory("st");
        Files.writeString(estaticos.resolve("index.html"), "<!doctype html><title>SmartTraffic</title>");
        Files.createDirectories(estaticos.resolve("assets"));
        Files.writeString(estaticos.resolve("assets/app.js"), "console.log('ok')");
        Servidor.Opciones o = new Servidor.Opciones();
        o.puerto = 0;
        o.secretoJwt = secreto;
        o.dirEstatico = estaticos.toString();
        servidor = new Servidor(o, repo, gestor, servicio);
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
    }

    static String login(String correo) throws Exception {
        Resp r = llamar("POST", "/api/auth/login", null, Json.obj("correo", correo, "clave", CLAVE));
        assertEquals(200, r.estado(), r.texto());
        return (String) r.objeto().get("token");
    }

    static Resp llamar(String metodo, String ruta, String token, Object cuerpo) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta)).timeout(Duration.ofSeconds(30));
        if (token != null) b.header("Authorization", "Bearer " + token);
        String texto = cuerpo == null ? "" : cuerpo instanceof String s ? s : Json.escribir(cuerpo);
        if (cuerpo != null) b.header("Content-Type", "application/json");
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(texto));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body(), r.headers());
    }

    static void esperar(java.util.function.BooleanSupplier cond, int ms, String mensaje) throws Exception {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) { if (cond.getAsBoolean()) return; Thread.sleep(50); }
        fail(mensaje);
    }

    static double tiempoSim() throws Exception {
        return ((Number) llamar("GET", "/api/sim/estado", tokenAdmin, null).objeto().get("t")).doubleValue();
    }

    // ================================================================= sesiones y seguridad

    @Test @Order(1) @DisplayName("Rutas públicas, y las demás exigen una sesión válida")
    void sesion() throws Exception {
        assertEquals(200, llamar("GET", "/api/salud", null, null).estado());
        assertTrue((Boolean) llamar("GET", "/api/config", null, null).objeto().containsKey("atribucion"));
        assertEquals(401, llamar("GET", "/api/red", null, null).estado());
        assertEquals(401, llamar("GET", "/api/red", "basura", null).estado());
        // token firmado con otro secreto, token «alg: none» y token vencido
        String ajeno = Seguridad.crearToken("otro-secreto-distinto-de-32-caracteres!!", 1, "x", Seguridad.Rol.ADMINISTRADOR, 3600);
        assertEquals(401, llamar("GET", "/api/red", ajeno, null).estado());
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        String sinFirma = b.encodeToString("{\"alg\":\"none\"}".getBytes()) + "." + b.encodeToString(("{\"sub\":1,\"rol\":\"ADMINISTRADOR\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes()) + ".";
        assertEquals(401, llamar("GET", "/api/red", sinFirma, null).estado());
        // el ataque de verdad: encabezado «alg: none» pero con una firma HS256 correcta
        String cabNone = b.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes()), carga = b.encodeToString(("{\"sub\":1,\"rol\":\"ADMINISTRADOR\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes());
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String firmado = cabNone + "." + carga + "." + b.encodeToString(mac.doFinal((cabNone + "." + carga).getBytes(StandardCharsets.UTF_8)));
        assertEquals(401, llamar("GET", "/api/red", firmado, null).estado(), "el algoritmo declarado debe ser HS256");
        assertEquals(401, llamar("GET", "/api/red", Seguridad.crearToken(secreto, 1, "x", Seguridad.Rol.ADMINISTRADOR, -10), null).estado(), "vencido");
        String[] partes = tokenAdmin.split("\\.");
        String manipulado = partes[0] + "." + b.encodeToString(("{\"sub\":1,\"rol\":\"ADMINISTRADOR\",\"exp\":" + (System.currentTimeMillis() / 1000 + 9999) + "}").getBytes()) + "." + partes[2];
        assertEquals(401, llamar("GET", "/api/red", manipulado, null).estado(), "contenido alterado");
        assertEquals("admin@t.test", llamar("GET", "/api/auth/yo", tokenAdmin, null).objeto().get("correo"));
    }

    @Test @Order(2) @DisplayName("Ingreso: mensaje genérico, sin revelar si el correo existe, y bloqueo tras 5 fallos")
    void ingreso() throws Exception {
        Resp mal = llamar("POST", "/api/auth/login", null, Json.obj("correo", "admin@t.test", "clave", "incorrecta"));
        Resp noExiste = llamar("POST", "/api/auth/login", null, Json.obj("correo", "nadie@t.test", "clave", "incorrecta"));
        assertEquals(401, mal.estado()); assertEquals(401, noExiste.estado());
        assertEquals(mal.objeto().get("error"), noExiste.objeto().get("error"), "mismo mensaje en ambos casos");
        assertFalse(mal.texto().contains("hash"), "no se filtra nada de la contraseña");
        for (int i = 0; i < 6; i++) llamar("POST", "/api/auth/login", null, Json.obj("correo", "victima@t.test", "clave", "x" + i));
        assertEquals(429, llamar("POST", "/api/auth/login", null, Json.obj("correo", "victima@t.test", "clave", "otra")).estado());
        assertEquals(400, llamar("POST", "/api/auth/login", null, "{no es json").estado());
        assertEquals(413, llamar("POST", "/api/auth/login", null, "{\"x\":\"" + "a".repeat(300_000) + "\"}").estado());
        assertTrue(repo.contarAuditoria("login_fallido") >= 7, "los intentos fallidos quedan en la auditoría");
    }

    @Test @Order(3) @DisplayName("Cabeceras de seguridad, 404, 405 y archivos de la pantalla sin escapar de su carpeta")
    void cabecerasYEstaticos() throws Exception {
        Resp r = llamar("GET", "/api/salud", null, null);
        assertEquals("nosniff", r.cabeceras().firstValue("X-Content-Type-Options").orElse(""));
        assertEquals("DENY", r.cabeceras().firstValue("X-Frame-Options").orElse(""));
        assertEquals("no-store", r.cabeceras().firstValue("Cache-Control").orElse(""));
        assertEquals(404, llamar("GET", "/api/no-existe", tokenAdmin, null).estado());
        assertEquals(405, llamar("DELETE", "/api/red", tokenAdmin, null).estado());
        Resp index = llamar("GET", "/", null, null);
        assertEquals(200, index.estado()); assertTrue(index.texto().contains("SmartTraffic"));
        assertTrue(index.cabeceras().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertTrue(llamar("GET", "/mapa/algo", null, null).texto().contains("SmartTraffic"), "una ruta de la pantalla abre la app");
        Resp js = llamar("GET", "/assets/app.js", null, null);
        assertEquals("text/javascript; charset=utf-8", js.cabeceras().firstValue("Content-Type").orElse(""));
        assertTrue(js.cabeceras().firstValue("Cache-Control").orElse("").contains("immutable"));
        Resp fuga = llamar("GET", "/%2e%2e/%2e%2e/%2e%2e/etc/passwd", null, null);
        assertFalse(fuga.texto().contains("root:"), "no debe entregar archivos fuera de la carpeta");
    }

    // ================================================================= permisos por rol

    @Test @Order(4) @DisplayName("Cada rol solo puede lo suyo: analista mira y compara, operador opera, administrador edita")
    void roles() throws Exception {
        Object vehiculos = Json.obj("cantidad", 5);
        assertEquals(403, llamar("POST", "/api/sim/vehiculos", tokenAnalista, vehiculos).estado());
        assertEquals(200, llamar("POST", "/api/sim/vehiculos", tokenOperador, vehiculos).estado());
        assertEquals(200, llamar("POST", "/api/sim/vehiculos", tokenAdmin, vehiculos).estado());
        Object interseccion = Json.obj("nombre", "Nueva", "x", 100.0, "y", 100.0);
        assertEquals(403, llamar("POST", "/api/red/intersecciones", tokenOperador, interseccion).estado());
        assertEquals(403, llamar("POST", "/api/red/intersecciones", tokenAnalista, interseccion).estado());
        assertEquals(403, llamar("GET", "/api/usuarios", tokenOperador, null).estado());
        assertEquals(403, llamar("POST", "/api/accidentes", tokenOperador, Json.obj("contenido", "x")).estado());
        assertEquals(200, llamar("GET", "/api/estadisticas", tokenAnalista, null).estado());
        assertEquals(200, llamar("GET", "/api/red", tokenAnalista, null).estado());
        assertEquals(403, llamar("POST", "/api/sim/control", tokenAnalista, Json.obj("accion", "iniciar")).estado());
        assertEquals(200, llamar("GET", "/api/usuarios", tokenAdmin, null).estado());
        assertFalse(llamar("GET", "/api/usuarios", tokenAdmin, null).texto().contains("clave_hash"), "la lista de usuarios no incluye contraseñas");
        assertFalse(llamar("GET", "/api/usuarios", tokenAdmin, null).texto().contains("pbkdf2"));
    }

    // ================================================================= simulación en vivo

    @Test @Order(5) @DisplayName("El operador inicia la simulación en vivo, cambia el modo, agrega tráfico, y el reloj avanza")
    void controlSimulacion() throws Exception {
        assertEquals(200, llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "reiniciar", "semilla", 11)).estado());
        double t0 = tiempoSim();
        assertEquals(200, llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "iniciar", "velocidad", 8)).estado());
        esperar(() -> { try { return tiempoSim() > t0 + 5; } catch (Exception e) { return false; } }, 6000, "el tiempo simulado debe avanzar");
        Map<String, Object> estado = llamar("GET", "/api/sim/estado", tokenAnalista, null).objeto();
        assertEquals(Boolean.TRUE, estado.get("corriendo")); assertEquals(8.0, ((Number) estado.get("velocidad")).doubleValue());
        assertEquals(200, llamar("POST", "/api/sim/config", tokenOperador, Json.obj("modo", "FIJO", "reruteo", false, "vehiculosPorMinuto", 120)).estado());
        estado = llamar("GET", "/api/sim/estado", tokenOperador, null).objeto();
        assertEquals("FIJO", estado.get("modo")); assertEquals(Boolean.FALSE, estado.get("reruteo")); assertEquals(120.0, ((Number) estado.get("vpm")).doubleValue());
        assertEquals(400, llamar("POST", "/api/sim/config", tokenOperador, Json.obj("modo", "RARO")).estado());
        assertEquals(400, llamar("POST", "/api/sim/config", tokenOperador, Json.obj("vehiculosPorMinuto", 99999)).estado());
        assertEquals(400, llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "volar")).estado());
        assertEquals(400, llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "iniciar", "velocidad", 500)).estado());
        assertEquals(200, llamar("POST", "/api/sim/vehiculos", tokenOperador, Json.obj("cantidad", 40)).estado());
        assertEquals(400, llamar("POST", "/api/sim/vehiculos", tokenOperador, Json.obj("cantidad", 0)).estado());
        esperar(() -> { try { return ((Number) ((Map<?, ?>) llamar("GET", "/api/sim/estado", tokenAdmin, null).objeto().get("kpi")).get("activos")).intValue() > 5; } catch (Exception e) { return false; } }, 6000, "debe haber vehículos circulando");
        assertEquals(200, llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "pausar")).estado());
        double tp = tiempoSim();
        Thread.sleep(500);
        assertEquals(tp, tiempoSim(), 1e-9, "en pausa el reloj no avanza");
        assertEquals(200, llamar("POST", "/api/sim/config", tokenOperador, Json.obj("modo", "INTELIGENTE", "reruteo", true, "vehiculosPorMinuto", 40)).estado());
    }

    @Test @Order(6) @DisplayName("Incidentes, emergencias y semáforos desde la API, con validaciones")
    void incidentes() throws Exception {
        llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "reiniciar", "semilla", 5));
        Resp c = llamar("POST", "/api/sim/incidentes", tokenOperador, Json.obj("via", "H12E", "fraccion", 0.5, "gravedad", 3));
        assertEquals(201, c.estado(), c.texto());
        String id = (String) c.objeto().get("id");
        assertTrue(id.startsWith("INC-"));
        assertEquals(409, llamar("POST", "/api/sim/incidentes", tokenOperador, Json.obj("via", "H12E", "gravedad", 1)).estado(), "no se duplica en la misma vía");
        assertEquals(400, llamar("POST", "/api/sim/incidentes", tokenOperador, Json.obj("via", "H12E", "gravedad", 7)).estado());
        assertEquals(400, llamar("POST", "/api/sim/incidentes", tokenOperador, Json.obj("via", "NO-EXISTE", "gravedad", 2)).estado());
        assertEquals(400, llamar("POST", "/api/sim/incidentes", tokenOperador, Json.obj("gravedad", 2)).estado(), "falta la vía");
        List<?> abiertos = (List<?>) llamar("GET", "/api/sim/estado", tokenAnalista, null).objeto().get("i");
        assertEquals(1, abiertos.size());
        assertEquals(403, llamar("DELETE", "/api/sim/incidentes/" + id, tokenAnalista, null).estado());
        assertEquals(200, llamar("DELETE", "/api/sim/incidentes/" + id, tokenOperador, null).estado());
        assertEquals(404, llamar("DELETE", "/api/sim/incidentes/" + id, tokenOperador, null).estado(), "ya estaba resuelto");
        assertEquals(201, llamar("POST", "/api/sim/emergencias", tokenOperador, Json.obj("tipo", "AMBULANCIA", "destino", "N33")).estado());
        assertEquals(400, llamar("POST", "/api/sim/emergencias", tokenOperador, Json.obj("tipo", "TANQUE", "destino", "N33")).estado());
        assertEquals(400, llamar("POST", "/api/sim/emergencias", tokenOperador, Json.obj("tipo", "AMBULANCIA", "destino", "NO")).estado());
        assertEquals(200, llamar("POST", "/api/sim/semaforos/N11", tokenOperador, Json.obj("grupo", 1)).estado());
        assertEquals(400, llamar("POST", "/api/sim/semaforos/HOSP", tokenOperador, Json.obj("grupo", 0)).estado(), "sin semáforo");
        assertEquals(400, llamar("POST", "/api/sim/semaforos/N11", tokenOperador, Json.obj("grupo", 9)).estado());
        assertEquals(200, llamar("POST", "/api/sim/semaforos/N11", tokenOperador, Json.obj("grupo", -1)).estado());
        assertEquals(200, llamar("POST", "/api/sim/semaforos/sincronizar", tokenOperador, Json.obj("nodos", List.of("N11", "N12", "N13"))).estado());
        assertEquals(400, llamar("POST", "/api/sim/semaforos/sincronizar", tokenOperador, Json.obj("nodos", List.of("N11"))).estado());
        assertTrue(repo.contarAuditoria("incidente_crear") >= 1, "las acciones de los operadores quedan auditadas");
    }

    @Test @Order(7) @DisplayName("La transmisión en vivo (SSE) entrega instantáneas continuas y exige sesión")
    void transmision() throws Exception {
        llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "iniciar", "velocidad", 4));
        HttpResponse<java.io.InputStream> sinToken = http.send(HttpRequest.newBuilder(URI.create(base + "/api/sim/stream")).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(401, sinToken.statusCode());
        HttpResponse<java.io.InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/sim/stream?token=" + tokenAnalista)).build(), HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        double ultimo = -1;
        int eventos = 0;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String linea;
            while (eventos < 4 && (linea = br.readLine()) != null) {
                if (!linea.startsWith("data: ")) continue;
                Map<String, Object> e = Json.leerObjeto(linea.substring(6));
                double t = ((Number) e.get("t")).doubleValue();
                assertTrue(t >= ultimo); ultimo = t; eventos++;
                assertTrue(e.containsKey("v") && e.containsKey("s") && e.containsKey("kpi"));
            }
        }
        assertEquals(4, eventos);
        llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "pausar"));
    }

    // ================================================================= red vial

    @Test @Order(8) @DisplayName("El administrador edita la red: una intersección suelta no es simulable hasta conectarla; todo queda guardado")
    void editarRed() throws Exception {
        Resp r = llamar("POST", "/api/red/intersecciones", tokenAdmin, Json.obj("nombre", "Glorieta nueva", "x", 400.0, "y", 300.0, "semaforizada", true));
        assertEquals(201, r.estado(), r.texto());
        String nodo = (String) r.objeto().get("id");
        assertEquals(Boolean.FALSE, r.objeto().get("simulable"), "una intersección sin calles rompe la conexión");
        assertFalse(((List<?>) r.objeto().get("problemas")).isEmpty());
        Resp v = llamar("POST", "/api/red/vias", tokenAdmin, Json.obj("desde", nodo, "hacia", "N11", "nombre", "Calle de enlace", "velocidad", 30, "doble", true));
        assertEquals(201, v.estado(), v.texto());
        assertEquals(Boolean.TRUE, v.objeto().get("simulable"));
        Map<String, Object> red = llamar("GET", "/api/red", tokenAnalista, null).objeto();
        assertEquals(19, ((List<?>) red.get("intersecciones")).size()); assertEquals(42, ((List<?>) red.get("vias")).size());
        assertEquals(19, repo.cargarRedActiva().orElseThrow().red().intersecciones().size(), "se guardó en la base de datos");
        String idVia = (String) v.objeto().get("id") + ":AB";
        assertEquals(200, llamar("PUT", "/api/red/vias/" + idVia, tokenAdmin, Json.obj("velocidad", 60, "nombre", "Enlace rápido")).estado());
        assertEquals(60.0, repo.cargarRedActiva().orElseThrow().red().via(idVia).velocidadMaxKmh(), 1e-9);
        assertEquals(400, llamar("PUT", "/api/red/vias/" + idVia, tokenAdmin, Json.obj("velocidad", 500)).estado());
        assertEquals(200, llamar("PUT", "/api/red/intersecciones/" + nodo, tokenAdmin, Json.obj("x", 450.0, "y", 320.0, "nombre", "Glorieta movida")).estado());
        Interseccion movida = repo.cargarRedActiva().orElseThrow().red().interseccion(nodo);
        assertEquals(450.0, movida.x(), 1e-9);
        assertEquals(404, llamar("PUT", "/api/red/intersecciones/NO-EXISTE", tokenAdmin, Json.obj("nombre", "x")).estado());
        assertEquals(400, llamar("POST", "/api/red/vias", tokenAdmin, Json.obj("desde", nodo, "hacia", nodo)).estado(), "no se permiten bucles");
        assertEquals(400, llamar("POST", "/api/red/intersecciones", tokenAdmin, Json.obj("nombre", "sin posición")).estado());
        Resp quitar = llamar("DELETE", "/api/red/intersecciones/" + nodo, tokenAdmin, null);
        assertEquals(200, quitar.estado());
        assertEquals(Boolean.TRUE, quitar.objeto().get("simulable"));
        assertEquals(18, ((List<?>) llamar("GET", "/api/red", tokenAdmin, null).objeto().get("intersecciones")).size());
        // la simulación en vivo sigue funcionando con la red editada
        assertEquals(200, llamar("POST", "/api/sim/control", tokenOperador, Json.obj("accion", "reiniciar")).estado());
    }

    @Test @Order(9) @DisplayName("Importar una red completa: la válida reemplaza a la actual y la que no se puede simular se rechaza")
    void importarRed() throws Exception {
        RedVial otra = RedCali.crear();
        otra.setNombre("Red importada de prueba");
        Resp ok = llamar("POST", "/api/red/importar", tokenAdmin, RedVialJson.escribir(otra, new ProyeccionLocal(3.4516, -76.5320)));
        assertEquals(200, ok.estado(), ok.texto());
        assertEquals("Red importada de prueba", llamar("GET", "/api/red", tokenAdmin, null).objeto().get("nombre"));
        assertEquals(Boolean.TRUE, llamar("GET", "/api/config", null, null).objeto().get("coordenadasReales"));
        RedVial rota = new RedVial("rota");
        rota.agregarInterseccion(new Interseccion("A", "A", 0, 0, smarttraffic.red.TipoNodo.ENTRADA_SALIDA));
        rota.agregarInterseccion(new Interseccion("B", "B", 100, 0, smarttraffic.red.TipoNodo.ENTRADA_SALIDA));
        rota.agregarVia("AB", "A", "B", "solo ida", null, 40);
        assertEquals(400, llamar("POST", "/api/red/importar", tokenAdmin, RedVialJson.escribir(rota, null)).estado());
        assertEquals("Red importada de prueba", llamar("GET", "/api/red", tokenAdmin, null).objeto().get("nombre"), "la red anterior se conserva");
        assertEquals(400, llamar("POST", "/api/red/importar", tokenAdmin, "{esto no es json").estado());
        assertEquals(403, llamar("POST", "/api/red/importar", tokenOperador, RedVialJson.escribir(otra, null)).estado());
    }

    @Test @Order(10) @DisplayName("Puntos críticos: con datos abiertos de accidentes se ordena el ranking de intersecciones")
    void puntosCriticos() throws Exception {
        ProyeccionLocal p = new ProyeccionLocal(3.4516, -76.5320);
        Interseccion n11 = servicio.copia().red().interseccion("N11"), n22 = servicio.copia().red().interseccion("N22");
        StringBuilder csv = new StringBuilder("fecha;gravedad;latitud;longitud\n");
        for (int i = 0; i < 3; i++) csv.append("2024-01-0").append(i + 1).append(";con muertos;").append(String.valueOf(p.lat(n11.y() + i)).replace('.', ',')).append(';').append(String.valueOf(p.lon(n11.x())).replace('.', ',')).append('\n');
        csv.append("2023-05-05;con heridos;").append(String.valueOf(p.lat(n22.y())).replace('.', ',')).append(';').append(String.valueOf(p.lon(n22.x())).replace('.', ',')).append('\n');
        csv.append("2023-05-06;solo daños;4,5;-75,0\n");
        assertEquals(403, llamar("POST", "/api/accidentes", tokenOperador, Json.obj("contenido", csv.toString())).estado());
        assertEquals(400, llamar("POST", "/api/accidentes", tokenAdmin, Json.obj("contenido", "a,b\n1,2\n")).estado());
        Resp ok = llamar("POST", "/api/accidentes", tokenAdmin, Json.obj("contenido", csv.toString()));
        assertEquals(200, ok.estado(), ok.texto());
        assertEquals(5.0, ((Number) ok.objeto().get("total")).doubleValue());
        Map<String, Object> c = llamar("GET", "/api/accidentes/criticos?top=5", tokenAnalista, null).objeto();
        assertEquals(4.0, ((Number) c.get("asignados")).doubleValue()); assertEquals(1.0, ((Number) c.get("fueraDeRed")).doubleValue());
        List<?> ranking = (List<?>) c.get("ranking");
        assertEquals("N11", ((Map<?, ?>) ranking.get(0)).get("id")); assertEquals(30.0, ((Number) ((Map<?, ?>) ranking.get(0)).get("puntaje")).doubleValue());
        assertEquals("N22", ((Map<?, ?>) ranking.get(1)).get("id"));
        assertEquals(400, llamar("GET", "/api/accidentes/criticos?top=0", tokenAnalista, null).estado());
    }

    // ================================================================= usuarios

    @Test @Order(11) @DisplayName("Usuarios: contraseñas fuertes, correos únicos, desactivar corta la sesión, y siempre queda un administrador")
    void usuarios() throws Exception {
        Map<String, Object> nuevo = Json.obj("nombre", "Nuevo Operador", "correo", "nuevo@t.test", "clave", "corta1", "rol", "OPERADOR");
        assertEquals(400, llamar("POST", "/api/usuarios", tokenAdmin, nuevo).estado(), "contraseña débil");
        nuevo.put("clave", "SinNumerosAqui");
        assertEquals(400, llamar("POST", "/api/usuarios", tokenAdmin, nuevo).estado());
        nuevo.put("clave", "BuenaClave2026");
        nuevo.put("rol", "SUPERMAN");
        assertEquals(400, llamar("POST", "/api/usuarios", tokenAdmin, nuevo).estado());
        nuevo.put("rol", "OPERADOR");
        nuevo.put("correo", "no-es-correo");
        assertEquals(400, llamar("POST", "/api/usuarios", tokenAdmin, nuevo).estado());
        nuevo.put("correo", "nuevo@t.test");
        Resp creado = llamar("POST", "/api/usuarios", tokenAdmin, nuevo);
        assertEquals(201, creado.estado(), creado.texto());
        assertEquals(400, llamar("POST", "/api/usuarios", tokenAdmin, nuevo).estado(), "correo repetido");
        long id = ((Number) creado.objeto().get("id")).longValue();
        Resp ingreso = llamar("POST", "/api/auth/login", null, Json.obj("correo", "nuevo@t.test", "clave", "BuenaClave2026"));
        assertEquals(200, ingreso.estado());
        String tokenNuevo = (String) ingreso.objeto().get("token");
        assertEquals(200, llamar("GET", "/api/red", tokenNuevo, null).estado());
        assertEquals(200, llamar("PUT", "/api/usuarios/" + id, tokenAdmin, Json.obj("activo", false)).estado());
        assertEquals(401, llamar("GET", "/api/red", tokenNuevo, null).estado(), "al desactivarlo su sesión deja de valer de inmediato");
        assertEquals(401, llamar("POST", "/api/auth/login", null, Json.obj("correo", "nuevo@t.test", "clave", "BuenaClave2026")).estado());
        assertEquals(200, llamar("PUT", "/api/usuarios/" + id, tokenAdmin, Json.obj("activo", true, "rol", "ANALISTA", "clave", "OtraClave2026")).estado());
        assertEquals(200, llamar("POST", "/api/auth/login", null, Json.obj("correo", "nuevo@t.test", "clave", "OtraClave2026")).estado());
        long idAdmin = ((Number) llamar("GET", "/api/auth/yo", tokenAdmin, null).objeto().get("id")).longValue();
        assertEquals(409, llamar("PUT", "/api/usuarios/" + idAdmin, tokenAdmin, Json.obj("rol", "ANALISTA")).estado(), "no se puede dejar el sistema sin administrador");
        assertEquals(409, llamar("PUT", "/api/usuarios/" + idAdmin, tokenAdmin, Json.obj("activo", false)).estado());
        assertEquals(404, llamar("PUT", "/api/usuarios/99999", tokenAdmin, Json.obj("rol", "ANALISTA")).estado());
    }

    // ================================================================= comparación e historial

    @Test @Order(12) @DisplayName("Comparar tradicional contra SmartTraffic: mismos datos de entrada, resultados guardados en el historial")
    void comparar() throws Exception {
        assertEquals(400, llamar("POST", "/api/comparar", tokenAnalista, Json.obj("duracion", 5)).estado());
        assertEquals(400, llamar("POST", "/api/comparar", tokenAnalista, Json.obj("vehiculosPorMinuto", 9999)).estado());
        Resp r = llamar("POST", "/api/comparar", tokenAnalista, Json.obj("semilla", 3, "vehiculosPorMinuto", 90, "duracion", 300, "incidenteVia", "H12E", "incidenteT", 100, "emergenciaNodo", "N33", "emergenciaT", 150));
        assertEquals(200, r.estado(), r.texto());
        List<?> esc = (List<?>) r.objeto().get("escenarios");
        assertEquals(2, esc.size());
        Map<?, ?> trad = (Map<?, ?>) esc.get(0), smart = (Map<?, ?>) esc.get(1);
        assertEquals("Tráfico tradicional", trad.get("nombre")); assertEquals("SmartTraffic", smart.get("nombre"));
        assertEquals(trad.get("generados"), smart.get("generados"), "misma demanda en ambos");
        assertTrue(((List<?>) smart.get("serie")).size() >= 50);
        assertTrue(((Number) smart.get("viajeMedioAjustado")).doubleValue() < ((Number) trad.get("viajeMedioAjustado")).doubleValue());
        List<Object> historial = llamar("GET", "/api/historial?limite=5", tokenAnalista, null).lista();
        assertTrue(historial.size() >= 2);
        long id = ((Number) ((Map<?, ?>) esc.get(1)).get("id")).longValue();
        Map<String, Object> detalle = llamar("GET", "/api/historial/" + id, tokenAnalista, null).objeto();
        assertEquals("INTELIGENTE", detalle.get("modo")); assertTrue(((List<?>) detalle.get("serie")).size() >= 50);
        assertEquals(404, llamar("GET", "/api/historial/999999", tokenAnalista, null).estado());
        assertEquals(400, llamar("GET", "/api/historial/abc", tokenAnalista, null).estado());
        assertEquals(400, llamar("GET", "/api/historial?limite=0", tokenAnalista, null).estado());
    }
}
