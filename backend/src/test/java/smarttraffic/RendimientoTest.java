package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
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

/** Lo que hace que la aplicación responda rápido: reutilizar conexiones, recordar usuarios, comprimir y no gastar sin necesidad. */
class RendimientoTest {
    static Repositorio repo;
    static Servidor servidor;
    static GestorSimulacion gestor;
    static String base, token;
    static Path estaticos;
    static final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void iniciar() throws Exception {
        System.setProperty("smarttraffic.pbkdf2", "1000");
        repo = new Repositorio("jdbc:h2:mem:rend" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        repo.crearUsuario("Admin", "admin@t.test", Seguridad.hashClave("Prueba2026ok"), "ADMINISTRADOR");
        var red = RedCali.crear();
        repo.guardarRedActiva(red, null);
        gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, null)).red());
        estaticos = Files.createTempDirectory("rend");
        Files.writeString(estaticos.resolve("index.html"), "<!doctype html><title>x</title>" + "<p>relleno</p>".repeat(200));
        Files.writeString(estaticos.resolve("pequeno.js"), "1");
        Servidor.Opciones o = new Servidor.Opciones();
        o.puerto = 0;
        o.dirEstatico = estaticos.toString();
        servidor = new Servidor(o, repo, gestor, new ServicioRed(repo, gestor, red, null));
        servidor.iniciar();
        base = "http://localhost:" + servidor.puerto();
        token = (String) Json.leerObjeto(enviar("POST", "/api/auth/login", null, Json.escribir(Json.obj("correo", "admin@t.test", "clave", "Prueba2026ok")), false).cuerpo()).get("token");
    }

    @AfterAll
    static void cerrar() {
        servidor.close();
        gestor.close();
    }

    record R(int estado, byte[] bytes, java.net.http.HttpHeaders cabeceras) {
        String cuerpo() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    static R enviar(String metodo, String ruta, String tok, String cuerpo, boolean gzip) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + ruta));
        if (tok != null) b.header("Authorization", "Bearer " + tok);
        if (gzip) b.header("Accept-Encoding", "gzip");
        if (cuerpo != null) b.header("Content-Type", "application/json");
        b.method(metodo, cuerpo == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(cuerpo));
        HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new R(r.statusCode(), r.body(), r.headers());
    }

    static String descomprimir(byte[] b) throws Exception {
        return new String(new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test @DisplayName("Las respuestas grandes se comprimen si el navegador lo admite; las pequeñas y las de quien no lo pide, no")
    void compresion() throws Exception {
        R plano = enviar("GET", "/api/red", token, null, false), gz = enviar("GET", "/api/red", token, null, true);
        assertNull(plano.cabeceras().firstValue("Content-Encoding").orElse(null));
        assertEquals("gzip", gz.cabeceras().firstValue("Content-Encoding").orElse(null));
        assertEquals("Accept-Encoding", gz.cabeceras().firstValue("Vary").orElse(null));
        assertEquals(plano.cuerpo(), descomprimir(gz.bytes()), "al descomprimir es exactamente lo mismo");
        assertTrue(gz.bytes().length < plano.bytes().length / 3, "pesa menos de la tercera parte: " + gz.bytes().length + " frente a " + plano.bytes().length);
        assertNull(enviar("GET", "/api/salud", null, null, true).cabeceras().firstValue("Content-Encoding").orElse(null), "una respuesta chica no se comprime");
        // archivos de la pantalla
        R html = enviar("GET", "/", null, null, true);
        assertEquals("gzip", html.cabeceras().firstValue("Content-Encoding").orElse(null));
        assertTrue(descomprimir(html.bytes()).contains("relleno"));
        assertEquals(html.bytes().length, enviar("GET", "/", null, null, true).bytes().length, "la segunda vez sale del archivo ya comprimido");
        assertNull(enviar("GET", "/pequeno.js", null, null, true).cabeceras().firstValue("Content-Encoding").orElse(null));
        assertNull(enviar("GET", "/", null, null, false).cabeceras().firstValue("Content-Encoding").orElse(null));
    }

    @Test @DisplayName("La reserva de conexiones reutiliza las mismas, no pasa de su máximo y deja limpia cada conexión al devolverla")
    void reserva() throws Exception {
        List<Integer> identidades = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            try (Connection c = repo.conexion()) {
                identidades.add(System.identityHashCode(c.unwrap(Connection.class)));
                c.createStatement().execute("select 1");
            }
        }
        assertEquals(1, identidades.stream().distinct().count(), "20 usos seguidos: una sola conexión real");
        // concurrencia: nunca hay más de 8 abiertas, y todas las tareas terminan
        java.util.concurrent.ExecutorService hilos = java.util.concurrent.Executors.newFixedThreadPool(24);
        List<java.util.concurrent.Future<Integer>> futuros = new ArrayList<>();
        java.util.Set<Integer> usadas = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 200; i++) futuros.add(hilos.submit(() -> {
            try (Connection c = repo.conexion()) {
                usadas.add(System.identityHashCode(c.unwrap(Connection.class)));
                try (var r = c.createStatement().executeQuery("select count(*) from usuarios")) { r.next(); return r.getInt(1); }
            }
        }));
        int esperados = repo.usuarios().size();
        for (var f : futuros) assertEquals(esperados, f.get(30, java.util.concurrent.TimeUnit.SECONDS));
        hilos.shutdown();
        assertTrue(usadas.size() <= 8, "como máximo 8 conexiones reales: " + usadas.size());
        // una transacción que se abandona no contamina a la siguiente persona que usa esa conexión
        try (Connection c = repo.conexion()) {
            c.setAutoCommit(false);
            c.createStatement().execute("insert into auditoria (accion) values ('abandonada')");
        }
        try (Connection c = repo.conexion()) {
            assertTrue(c.getAutoCommit(), "vuelve en modo normal");
            try (var r = c.createStatement().executeQuery("select count(*) from auditoria where accion = 'abandonada'")) { r.next(); assertEquals(0, r.getInt(1), "lo que quedó a medias se deshizo"); }
        }
        // cerrar dos veces no devuelve dos veces
        Connection c = repo.conexion();
        c.close();
        c.close();
        assertTrue(c.isClosed());
        assertThrows(java.sql.SQLException.class, () -> c.createStatement());
    }

    @Test @DisplayName("Los usuarios se recuerdan unos segundos, pero desactivar o cambiar a alguien surte efecto al instante")
    void usuariosRecordados() throws Exception {
        long id = repo.crearUsuario("Ana", "ana@t.test", Seguridad.hashClave("Prueba2026ok"), "ANALISTA");
        String tokenAna = (String) Json.leerObjeto(enviar("POST", "/api/auth/login", null, Json.escribir(Json.obj("correo", "ana@t.test", "clave", "Prueba2026ok")), false).cuerpo()).get("token");
        assertEquals(200, enviar("GET", "/api/auth/yo", tokenAna, null, false).estado());
        var a = repo.usuarioPorId(id).orElseThrow();
        assertSame(a, repo.usuarioPorId(id).orElseThrow(), "la segunda consulta no va a la base de datos: devuelve el mismo objeto");
        repo.actualizarUsuario(id, null, null, false, null);
        assertEquals(401, enviar("GET", "/api/auth/yo", tokenAna, null, false).estado(), "desactivada: su sesión deja de valer en ese mismo instante");
        repo.actualizarUsuario(id, null, "OPERADOR", true, null);
        assertEquals("OPERADOR", repo.usuarioPorId(id).orElseThrow().rol(), "el cambio de rol se ve de inmediato");
    }

    @Test @DisplayName("Las contraseñas nuevas usan el formato más rápido, las antiguas siguen valiendo y se actualizan al ingresar")
    void contrasenas() throws Exception {
        String nueva = Seguridad.hashClave("Prueba2026ok");
        assertTrue(nueva.startsWith("pbkdf2s512$"), nueva);
        assertTrue(Seguridad.verificarClave("Prueba2026ok", nueva)); assertFalse(Seguridad.verificarClave("otra clave", nueva));
        assertFalse(Seguridad.necesitaActualizar(nueva));
        // una contraseña guardada con el formato antiguo (SHA-256) sigue funcionando
        byte[] sal = new byte[16];
        byte[] h = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new javax.crypto.spec.PBEKeySpec("Prueba2026ok".toCharArray(), sal, 1000, 256)).getEncoded();
        String vieja = "pbkdf2$1000$" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sal) + "$" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        assertTrue(Seguridad.verificarClave("Prueba2026ok", vieja)); assertTrue(Seguridad.necesitaActualizar(vieja));
        long id = repo.crearUsuario("Antigua", "antigua@t.test", vieja, "ANALISTA");
        assertEquals(200, enviar("POST", "/api/auth/login", null, Json.escribir(Json.obj("correo", "antigua@t.test", "clave", "Prueba2026ok")), false).estado());
        String guardada = repo.usuarioPorId(id).orElseThrow().claveHash();
        assertTrue(guardada.startsWith("pbkdf2s512$"), "al ingresar se guardó con el formato nuevo: " + guardada);
        assertEquals(200, enviar("POST", "/api/auth/login", null, Json.escribir(Json.obj("correo", "antigua@t.test", "clave", "Prueba2026ok")), false).estado(), "y sigue entrando");
        assertEquals(401, enviar("POST", "/api/auth/login", null, Json.escribir(Json.obj("correo", "antigua@t.test", "clave", "incorrecta")), false).estado());
    }

    @Test @DisplayName("Sin nadie mirando no se arma la instantánea; con alguien, sí; y el estado siempre es el de ahora")
    void sinSuscriptores() throws Exception {
        gestor.iniciar();
        Thread.sleep(600);
        double t1 = Json.leerObjeto(gestor.ultimaInstantanea()).get("t") instanceof Number n ? n.doubleValue() : -1;
        Thread.sleep(600);
        double t2 = ((Number) Json.leerObjeto(gestor.ultimaInstantanea()).get("t")).doubleValue();
        assertTrue(t2 > t1, "aunque nadie esté conectado, consultar el estado da el tiempo actual: " + t1 + " → " + t2);
        var s = gestor.suscribir();
        String primero = s.siguiente(2000);
        assertNotNull(primero, "quien se conecta recibe el estado de inmediato");
        String segundo = s.siguiente(2000), tercero = s.siguiente(2000);
        assertNotNull(segundo); assertNotNull(tercero);
        gestor.cancelar(s);
        gestor.pausar();
    }
}
