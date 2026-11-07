package smarttraffic.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import smarttraffic.datos.DatosAbiertos;
import smarttraffic.datos.PuntosCriticos;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.datos.Repositorio.Usuario;
import smarttraffic.sim.ComparadorEscenarios;
import smarttraffic.sim.ComparadorEscenarios.Accion;
import smarttraffic.sim.ComparadorEscenarios.Resultado;
import smarttraffic.util.Json;
import smarttraffic.web.Seguridad.Rol;

/** Servidor HTTP (solo JDK): API REST, transmisión en vivo (SSE) y archivos de la pantalla. */
public final class Servidor implements AutoCloseable {

    public static final String VERSION = "1.0.0";

    public static final class Opciones {
        public int puerto = 8080;
        public String dirEstatico = "../frontend/dist";
        public String secretoJwt = Seguridad.secretoAleatorio();
        public String origenCors = null;
        /** Modo de demostración: el ingreso ofrece entrar con un clic como cada rol (solo para presentaciones). */
        public boolean demo = false;
        /** Servicio de OpenStreetMap (API Overpass) del que el servidor descarga el mapa. */
        public String overpassUrl = "https://overpass-api.de/api/interpreter,https://overpass.kumi.systems/api/interpreter,https://overpass.private.coffee/api/interpreter";
        /** Una tesela cualquiera, solo para comprobar que el servidor llega al servicio de mapas. */
        public String teselaPrueba = "https://tile.openstreetmap.org/0/0/0.png";
        /** Buscador de lugares (Nominatim). */
        public String nominatimUrl = "https://nominatim.openstreetmap.org/search";
        public int overpassIntentos = 2;
        public long overpassEsperaMs = 4000;
    }

    /** Respuesta con estado distinto de 200. */
    record Respuesta(int estado, Object cuerpo) {}

    /** JSON ya serializado (se envía tal cual). */
    record Crudo(String json) {}

    static final class ErrorHttp extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int estado;

        ErrorHttp(int estado, String mensaje) {
            super(mensaje);
            this.estado = estado;
        }
    }

    public static final class Peticion {
        final String metodo;
        final String ruta;
        final String ip;
        final Map<String, String> params = new HashMap<>();
        final Map<String, String> consulta = new HashMap<>();
        String cuerpoTexto = "";
        Usuario usuario;
        private Map<String, Object> cuerpo;

        Peticion(String metodo, String ruta, String ip) {
            this.metodo = metodo;
            this.ruta = ruta;
            this.ip = ip;
        }

        Map<String, Object> cuerpo() {
            if (cuerpo == null) {
                try {
                    cuerpo = cuerpoTexto.isBlank() ? new HashMap<>() : Json.leerObjeto(cuerpoTexto);
                } catch (Json.ErrorJson e) {
                    throw new ErrorHttp(400, "El cuerpo no es un JSON válido: " + e.getMessage());
                }
            }
            return cuerpo;
        }
    }

    @FunctionalInterface
    interface Manejador {
        Object manejar(Peticion p) throws Exception;
    }

    private record Comprimido(long modificado, int original, byte[] bytes) {}

    private final Map<Path, Comprimido> comprimidos = new java.util.concurrent.ConcurrentHashMap<>();

    private record Ruta(String metodo, Pattern patron, List<String> nombres, Set<Rol> roles, int maxCuerpo, Manejador manejador) {}

    private static final Set<Rol> TODOS = EnumSet.allOf(Rol.class);
    private static final Set<Rol> OPERACION = EnumSet.of(Rol.ADMINISTRADOR, Rol.OPERADOR);
    private static final Set<Rol> ADMIN = EnumSet.of(Rol.ADMINISTRADOR);
    private static final int CUERPO_NORMAL = 256 * 1024;
    private static final int CUERPO_GRANDE = 64 * 1024 * 1024;

    private final Opciones opc;
    private final Repositorio repo;
    private final GestorSimulacion gestor;
    private final ServicioRed servicioRed;
    private final ImportacionOsm importacionOsm;
    private final Geocodificador geocodificador;
    private final HttpServer http;
    private final List<Ruta> rutas = new ArrayList<>();
    // 5 fallos por correo; 30 por dirección (varias personas pueden compartir una misma conexión)
    private final Seguridad.LimitadorIntentos limitadorCorreo = new Seguridad.LimitadorIntentos(5);
    private final Seguridad.LimitadorIntentos limitadorIp = new Seguridad.LimitadorIntentos(30);
    private final Semaphore comparaciones = new Semaphore(2);
    private final String claveFalsa = Seguridad.hashClave("clave-falsa-para-igualar-tiempos");
    private final List<Pattern> origenesPermitidos = new ArrayList<>();

    public Servidor(Opciones opc, Repositorio repo, GestorSimulacion gestor, ServicioRed servicioRed) throws IOException {
        this.opc = opc;
        this.repo = repo;
        this.gestor = gestor;
        this.servicioRed = servicioRed;
        this.geocodificador = new Geocodificador(opc.nominatimUrl);
        this.importacionOsm = new ImportacionOsm(servicioRed, opc.overpassUrl, opc.overpassIntentos, opc.overpassEsperaMs);
        for (String o : opc.origenCors == null ? new String[0] : opc.origenCors.split(",")) {
            String origen = o.trim();
            if (origen.isEmpty() || origen.equals("*")) continue; // un comodín total anularía la protección: no se admite
            StringBuilder rx = new StringBuilder("^");
            String[] partes = origen.split("\\*", -1);
            for (int i = 0; i < partes.length; i++) rx.append(i > 0 ? "[A-Za-z0-9.-]*" : "").append(Pattern.quote(partes[i]));
            origenesPermitidos.add(Pattern.compile(rx.append("$").toString()));
        }
        this.http = HttpServer.create(new InetSocketAddress(opc.puerto), 0);
        registrarRutas();
        http.createContext("/", this::atender);
        http.setExecutor(Executors.newCachedThreadPool());
    }

    public void iniciar() {
        http.start();
    }

    public int puerto() {
        return http.getAddress().getPort();
    }

    @Override
    public void close() {
        importacionOsm.cerrar();
        http.stop(0);
    }

    // ================================================================= rutas

    private void ruta(String metodo, String camino, Set<Rol> roles, Manejador m) {
        ruta(metodo, camino, roles, CUERPO_NORMAL, m);
    }

    private void ruta(String metodo, String camino, Set<Rol> roles, int maxCuerpo, Manejador m) {
        List<String> nombres = new ArrayList<>();
        Matcher mt = Pattern.compile("\\{(\\w+)}").matcher(camino);
        StringBuilder rx = new StringBuilder("^");
        int ultimo = 0;
        while (mt.find()) {
            rx.append(Pattern.quote(camino.substring(ultimo, mt.start()))).append("([^/]+)");
            nombres.add(mt.group(1));
            ultimo = mt.end();
        }
        rx.append(Pattern.quote(camino.substring(ultimo))).append("$");
        rutas.add(new Ruta(metodo, Pattern.compile(rx.toString()), nombres, roles, maxCuerpo, m));
    }

    private static String texto(Map<String, Object> m, String clave) {
        Object o = m.get(clave);
        if (!(o instanceof String s) || s.isBlank()) throw new IllegalArgumentException("Falta «" + clave + "»");
        return s.trim();
    }

    private static double numero(Map<String, Object> m, String clave, double min, double max, Double defecto) {
        Object o = m.get(clave);
        if (o == null && defecto != null) return defecto;
        if (!(o instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Falta el número «" + clave + "»");
        if (n.doubleValue() < min || n.doubleValue() > max) throw new IllegalArgumentException("«" + clave + "» debe estar entre " + min + " y " + max);
        return n.doubleValue();
    }

    private static Map<String, Object> mapaDe(Map<String, String> consulta) {
        Map<String, Object> m = new HashMap<>();
        for (var e : consulta.entrySet()) {
            try {
                m.put(e.getKey(), Double.valueOf(e.getValue()));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("«" + e.getKey() + "» debe ser un número.");
            }
        }
        return m;
    }

    private static int entero(String valor, int defecto, int min, int max) {
        if (valor == null || valor.isBlank()) return defecto;
        int n = Integer.parseInt(valor.trim());
        if (n < min || n > max) throw new IllegalArgumentException("El valor debe estar entre " + min + " y " + max);
        return n;
    }

    private void auditar(Peticion p, String accion, String detalle) {
        repo.auditar(p.usuario == null ? null : p.usuario.id(), accion, detalle);
    }

    private void registrarRutas() {
        // ---- públicas
        ruta("GET", "/api/salud", null, p -> Json.obj("ok", true, "version", VERSION));
        ruta("GET", "/api/config", null, p -> Json.obj("nombre", "SmartTraffic", "version", VERSION, "atribucion", "© colaboradores de OpenStreetMap (ODbL)", "coordenadasReales", servicioRed.proyeccion() != null, "demo", opc.demo));
        ruta("POST", "/api/auth/login", null, this::login);
        // ---- cualquier usuario con sesión
        ruta("GET", "/api/auth/yo", TODOS, p -> usuarioJson(p.usuario));
        ruta("GET", "/api/red", TODOS, p -> new Crudo(servicioRed.json()));
        ruta("GET", "/api/sim/estado", TODOS, p -> new Crudo(gestor.ultimaInstantanea()));
        ruta("GET", "/api/estadisticas", TODOS, p -> new Crudo(gestor.estadisticasJson()));
        ruta("GET", "/api/accidentes/criticos", TODOS, this::criticos);
        ruta("GET", "/api/historial", TODOS, p -> repo.historial(entero(p.consulta.get("limite"), 20, 1, 200)));
        ruta("GET", "/api/historial/{id}", TODOS, p -> repo.detalleSimulacion(Long.parseLong(p.params.get("id"))).orElseThrow(() -> new NoSuchElementException("No existe esa simulación")));
        ruta("POST", "/api/comparar", TODOS, this::comparar);
        ruta("GET", "/api/lugares", TODOS, this::lugares);
        ruta("POST", "/api/ruta", TODOS, this::ruta);
        // ---- operación (administrador y operador)
        ruta("POST", "/api/sim/control", OPERACION, p -> {
            String accion = texto(p.cuerpo(), "accion");
            switch (accion) {
                case "iniciar" -> gestor.iniciar();
                case "pausar" -> gestor.pausar();
                case "reiniciar" -> gestor.reiniciar(p.cuerpo().get("semilla") instanceof Number n ? n.longValue() : null);
                default -> throw new IllegalArgumentException("La acción debe ser iniciar, pausar o reiniciar");
            }
            if (p.cuerpo().get("velocidad") instanceof Number v) gestor.velocidad(v.doubleValue());
            auditar(p, "sim_" + accion, null);
            return Json.obj("ok", true);
        });
        ruta("POST", "/api/sim/config", OPERACION, p -> {
            Map<String, Object> c = p.cuerpo();
            gestor.configurar(c.get("modo") instanceof String s ? s : null, c.get("reruteo") instanceof Boolean b ? b : null, c.get("vehiculosPorMinuto") instanceof Number n ? n.doubleValue() : null);
            if (c.get("velocidad") instanceof Number v) gestor.velocidad(v.doubleValue());
            auditar(p, "sim_config", Json.escribir(c));
            return Json.obj("ok", true);
        });
        ruta("POST", "/api/sim/vehiculos", OPERACION, p -> Json.obj("agregados", gestor.agregarVehiculos((int) numero(p.cuerpo(), "cantidad", 1, 500, null))));
        ruta("POST", "/api/sim/incidentes", OPERACION, p -> {
            String id = gestor.crearIncidente(texto(p.cuerpo(), "via"), numero(p.cuerpo(), "fraccion", 0, 1, 0.5), (int) numero(p.cuerpo(), "gravedad", 1, 3, 2.0));
            auditar(p, "incidente_crear", id + " en " + p.cuerpo().get("via"));
            return new Respuesta(201, Json.obj("id", id));
        });
        ruta("DELETE", "/api/sim/incidentes/{id}", OPERACION, p -> {
            if (!gestor.resolverIncidente(p.params.get("id"))) throw new NoSuchElementException("No existe un incidente abierto con ese identificador");
            auditar(p, "incidente_resolver", p.params.get("id"));
            return Json.obj("ok", true);
        });
        ruta("POST", "/api/sim/emergencias", OPERACION, p -> {
            gestor.despachar(texto(p.cuerpo(), "tipo"), texto(p.cuerpo(), "destino"));
            auditar(p, "emergencia_despachar", p.cuerpo().get("tipo") + " → " + p.cuerpo().get("destino"));
            return new Respuesta(201, Json.obj("ok", true));
        });
        ruta("POST", "/api/sim/semaforos/sincronizar", OPERACION, p -> {
            List<String> ids = new ArrayList<>();
            if (!(p.cuerpo().get("nodos") instanceof List<?> l) || l.size() < 2) throw new IllegalArgumentException("Indica al menos dos intersecciones");
            for (Object o : l) ids.add(String.valueOf(o));
            gestor.sincronizar(ids);
            auditar(p, "semaforos_sincronizar", String.join(",", ids));
            return Json.obj("ok", true);
        });
        ruta("POST", "/api/sim/semaforos/{nodo}", OPERACION, p -> {
            gestor.forzarSemaforo(p.params.get("nodo"), (int) numero(p.cuerpo(), "grupo", -1, 1, null));
            auditar(p, "semaforo_manual", p.params.get("nodo") + " grupo " + p.cuerpo().get("grupo"));
            return Json.obj("ok", true);
        });
        // ---- administración
        ruta("POST", "/api/red/intersecciones", ADMIN, p -> { var r = servicioRed.crearInterseccion(p.cuerpo()); auditar(p, "red_interseccion_crear", r.id()); return new Respuesta(201, resultado(r)); });
        ruta("PUT", "/api/red/intersecciones/{id}", ADMIN, p -> { var r = servicioRed.actualizarInterseccion(p.params.get("id"), p.cuerpo()); auditar(p, "red_interseccion_editar", r.id()); return resultado(r); });
        ruta("DELETE", "/api/red/intersecciones/{id}", ADMIN, p -> { var r = servicioRed.eliminarInterseccion(p.params.get("id")); auditar(p, "red_interseccion_eliminar", r.id()); return resultado(r); });
        ruta("POST", "/api/red/vias", ADMIN, p -> { var r = servicioRed.crearVia(p.cuerpo()); auditar(p, "red_via_crear", r.id()); return new Respuesta(201, resultado(r)); });
        ruta("PUT", "/api/red/vias/{id}", ADMIN, p -> { var r = servicioRed.actualizarVia(p.params.get("id"), p.cuerpo()); auditar(p, "red_via_editar", r.id()); return resultado(r); });
        ruta("DELETE", "/api/red/vias/{id}", ADMIN, p -> { var r = servicioRed.eliminarVia(p.params.get("id")); auditar(p, "red_via_eliminar", r.id()); return resultado(r); });
        ruta("POST", "/api/red/importar", ADMIN, CUERPO_GRANDE, p -> { var r = servicioRed.reemplazar(p.cuerpoTexto); auditar(p, "red_importar", null); return resultado(r); });
        ruta("POST", "/api/red/importar-osm", ADMIN, p -> {
            double[] zona = ImportacionOsm.CENTRO_CALI;
            if (!p.cuerpo().isEmpty()) {
                Map<String, Object> c = p.cuerpo();
                zona = new double[] {numero(c, "sur", -90, 90, null), numero(c, "oeste", -180, 180, null), numero(c, "norte", -90, 90, null), numero(c, "este", -180, 180, null)};
            }
            if (!importacionOsm.iniciar(zona)) throw new IllegalStateException("Ya hay una importación en curso. Espera a que termine.");
            auditar(p, "red_importar_osm", java.util.Arrays.toString(zona));
            return new Respuesta(202, importacionOsm.estado());
        });
        ruta("GET", "/api/red/importar-osm", ADMIN, p -> importacionOsm.estado());
        ruta("GET", "/api/red/importar-osm/enlace", ADMIN, p -> {
            double[] zona = ImportacionOsm.validarZona(new double[] {numero(mapaDe(p.consulta), "sur", -90, 90, null), numero(mapaDe(p.consulta), "oeste", -180, 180, null), numero(mapaDe(p.consulta), "norte", -90, 90, null), numero(mapaDe(p.consulta), "este", -180, 180, null)});
            return Json.obj("url", smarttraffic.datos.ImportarCali.enlaceDescarga(importacionOsm.servidorPrincipal(), zona));
        });
        ruta("POST", "/api/red/importar-overpass", ADMIN, CUERPO_GRANDE, p -> {
            Map<String, Object> r = importacionOsm.importarTexto(p.cuerpoTexto);
            auditar(p, "red_importar_archivo_osm", null);
            return Json.obj("estado", "LISTO", "resultado", r);
        });
        ruta("GET", "/api/red/diagnostico", ADMIN, p -> {
            List<DiagnosticoRed.Prueba> pruebas = new ArrayList<>();
            for (String u : opc.overpassUrl.split(",")) if (!u.isBlank()) pruebas.add(new DiagnosticoRed.Prueba("Calles (Overpass) · " + java.net.URI.create(u.trim()).getHost(), u.trim().replace("/api/interpreter", "/api/status")));
            pruebas.add(new DiagnosticoRed.Prueba("Búsqueda de lugares (Nominatim)", opc.nominatimUrl.replace("/search", "/status.php")));
            pruebas.add(new DiagnosticoRed.Prueba("Teselas del mapa", opc.teselaPrueba));
            var r = DiagnosticoRed.probar(pruebas);
            return Json.obj("pruebas", r, "resumen", DiagnosticoRed.resumen(r));
        });
        ruta("POST", "/api/accidentes", ADMIN, CUERPO_GRANDE, p -> {
            var lista = DatosAbiertos.aAccidentes(DatosAbiertos.leerPuntos(texto(p.cuerpo(), "contenido")));
            repo.reemplazarAccidentes(lista);
            auditar(p, "accidentes_importar", lista.size() + " registros");
            return Json.obj("total", lista.size());
        });
        ruta("GET", "/api/usuarios", ADMIN, p -> {
            List<Object> l = new ArrayList<>();
            for (Usuario u : repo.usuarios()) l.add(usuarioJson(u));
            return l;
        });
        ruta("POST", "/api/usuarios", ADMIN, p -> {
            String clave = texto(p.cuerpo(), "clave");
            Seguridad.validarClave(clave);
            Rol rol = rol(texto(p.cuerpo(), "rol"));
            String correo = texto(p.cuerpo(), "correo").toLowerCase();
            if (!correo.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw new IllegalArgumentException("El correo no es válido");
            long id = repo.crearUsuario(texto(p.cuerpo(), "nombre"), correo, Seguridad.hashClave(clave), rol.name());
            auditar(p, "usuario_crear", correo + " (" + rol + ")");
            return new Respuesta(201, usuarioJson(repo.usuarioPorId(id).orElseThrow()));
        });
        ruta("PUT", "/api/usuarios/{id}", ADMIN, p -> {
            long id = Long.parseLong(p.params.get("id"));
            Usuario u = repo.usuarioPorId(id).orElseThrow(() -> new NoSuchElementException("No existe el usuario"));
            Map<String, Object> c = p.cuerpo();
            String nuevoRol = c.get("rol") instanceof String s ? rol(s).name() : null;
            Boolean activo = c.get("activo") instanceof Boolean b ? b : null;
            boolean dejaDeSerAdmin = u.rol().equals(Rol.ADMINISTRADOR.name()) && ((nuevoRol != null && !nuevoRol.equals(u.rol())) || Boolean.FALSE.equals(activo));
            if (dejaDeSerAdmin) {
                long otros = repo.usuarios().stream().filter(x -> x.id() != id && x.activo() && x.rol().equals(Rol.ADMINISTRADOR.name())).count();
                if (otros == 0) throw new IllegalStateException("Debe quedar al menos un administrador activo.");
            }
            String hash = null;
            if (c.get("clave") instanceof String clave) {
                Seguridad.validarClave(clave);
                hash = Seguridad.hashClave(clave);
            }
            repo.actualizarUsuario(id, c.get("nombre") instanceof String n && !n.isBlank() ? n.trim() : null, nuevoRol, activo, hash);
            auditar(p, "usuario_editar", String.valueOf(id));
            return usuarioJson(repo.usuarioPorId(id).orElseThrow());
        });
    }

    private static Rol rol(String s) {
        try {
            return Rol.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El rol debe ser ADMINISTRADOR, OPERADOR o ANALISTA");
        }
    }

    private static Map<String, Object> usuarioJson(Usuario u) {
        return Json.obj("id", u.id(), "nombre", u.nombre(), "correo", u.correo(), "rol", u.rol(), "activo", u.activo());
    }

    private static Map<String, Object> resultado(ServicioRed.Resultado r) {
        return Json.obj("simulable", r.simulable(), "problemas", r.problemas(), "id", r.id());
    }

    // ================================================================= manejadores

    private Object login(Peticion p) {
        String correo = texto(p.cuerpo(), "correo").toLowerCase();
        String clave = Json.texto(p.cuerpo(), "clave", "");
        if (!limitadorCorreo.permitido(correo) || !limitadorIp.permitido(p.ip)) throw new ErrorHttp(429, "Demasiados intentos. Espera unos minutos e inténtalo de nuevo.");
        Optional<Usuario> u = repo.usuarioPorCorreo(correo);
        boolean ok = Seguridad.verificarClave(clave, u.map(Usuario::claveHash).orElse(claveFalsa)) && u.isPresent() && u.get().activo();
        if (!ok) {
            limitadorCorreo.fallo(correo);
            limitadorIp.fallo(p.ip);
            repo.auditar(null, "login_fallido", correo);
            throw new ErrorHttp(401, "Correo o contraseña incorrectos.");
        }
        limitadorCorreo.exito(correo);
        Usuario usuario = u.get();
        if (Seguridad.necesitaActualizar(usuario.claveHash())) repo.actualizarUsuario(usuario.id(), null, null, null, Seguridad.hashClave(clave)); // pasa al formato más rápido
        repo.auditar(usuario.id(), "login", null);
        return Json.obj("token", Seguridad.crearToken(opc.secretoJwt, usuario.id(), usuario.nombre(), rol(usuario.rol()), 8 * 3600), "usuario", usuarioJson(usuario));
    }

    private Object criticos(Peticion p) {
        var proy = servicioRed.proyeccion();
        if (proy == null) throw new IllegalStateException("La red actual no tiene coordenadas reales. Importa el mapa de OpenStreetMap para ver los puntos críticos.");
        var accidentes = repo.accidentes();
        int top = entero(p.consulta.get("top"), 10, 1, 50);
        PuntosCriticos.Resultado r = PuntosCriticos.calcular(servicioRed.copia().red(), proy, accidentes, 60, top);
        List<Object> ranking = new ArrayList<>();
        for (PuntosCriticos.Critico c : r.ranking()) ranking.add(Json.obj("id", c.interseccionId(), "nombre", c.nombre(), "lat", c.lat(), "lon", c.lon(), "accidentes", c.accidentes(), "puntaje", c.puntaje()));
        return Json.obj("total", accidentes.size(), "asignados", r.asignados(), "fueraDeRed", r.fueraDeRed(), "ranking", ranking, "calor", r.calor());
    }

    private Object lugares(Peticion p) {
        String q = p.consulta.getOrDefault("q", "").trim();
        if (q.length() < 2) throw new IllegalArgumentException("Escribe al menos 2 letras para buscar.");
        if (q.length() > 120) throw new IllegalArgumentException("La búsqueda es demasiado larga.");
        List<Map<String, Object>> locales = servicioRed.buscarLocal(q, 8), externos = new ArrayList<>();
        String aviso = null;
        if ("1".equals(p.consulta.get("externo"))) {
            var proy = servicioRed.proyeccion();
            if (proy == null) {
                aviso = "La red actual es de demostración y no tiene ubicación real: solo se buscan calles de la red.";
            } else {
                try {
                    externos = geocodificador.buscar(q, proy, servicioRed.caja());
                    if (externos.isEmpty()) aviso = "OpenStreetMap no encontró ese lugar dentro de la zona del mapa cargado.";
                } catch (java.io.IOException e) {
                    aviso = e.getMessage();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    aviso = "La búsqueda se interrumpió.";
                }
            }
        }
        Map<String, Object> r = Json.obj("locales", locales, "externos", externos);
        if (aviso != null) r.put("aviso", aviso);
        return r;
    }

    private smarttraffic.sim.PlanificadorRutas.Punto punto(Map<String, Object> m, String nombre) {
        Object o = m.get(nombre);
        if (!(o instanceof Map<?, ?> d)) throw new IllegalArgumentException("Falta el " + nombre + " de la ruta.");
        @SuppressWarnings("unchecked") Map<String, Object> pt = (Map<String, Object>) d;
        if (pt.get("x") instanceof Number x && pt.get("y") instanceof Number y) return new smarttraffic.sim.PlanificadorRutas.Punto(x.doubleValue(), y.doubleValue());
        if (pt.get("lat") instanceof Number la && pt.get("lon") instanceof Number lo) {
            var proy = servicioRed.proyeccion();
            if (proy == null) throw new IllegalArgumentException("La red actual no tiene ubicación real: indica el punto con x e y.");
            return new smarttraffic.sim.PlanificadorRutas.Punto(proy.x(lo.doubleValue()), proy.y(la.doubleValue()));
        }
        if (pt.get("nodo") instanceof String id) {
            var n = servicioRed.copia().red().interseccion(id);
            if (n == null) throw new IllegalArgumentException("No existe la intersección " + id);
            return new smarttraffic.sim.PlanificadorRutas.Punto(n.x(), n.y());
        }
        throw new IllegalArgumentException("El " + nombre + " necesita x e y, lat y lon, o el identificador de una intersección.");
    }

    private Object ruta(Peticion p) {
        var origen = punto(p.cuerpo(), "origen");
        var destino = punto(p.cuerpo(), "destino");
        int alternativas = (int) numero(p.cuerpo(), "alternativas", 0, 3, 2.0);
        var opciones = gestor.conSimulacion(sim -> new Object[] {smarttraffic.sim.PlanificadorRutas.planificar(sim, origen, destino, alternativas), sim.config().modoSemaforos.name(), sim.tiempo()});
        @SuppressWarnings("unchecked") List<smarttraffic.sim.PlanificadorRutas.Opcion> lista = (List<smarttraffic.sim.PlanificadorRutas.Opcion>) opciones[0];
        List<Object> salida = new ArrayList<>();
        for (var o : lista) {
            double[] g = new double[o.geometria().length];
            for (int i = 0; i < g.length; i++) g[i] = Math.round(o.geometria()[i] * 10) / 10.0;
            List<Object> pasos = new ArrayList<>();
            for (var s : o.pasos()) pasos.add(Json.obj("giro", s.giro(), "texto", s.texto(), "calle", s.calle(), "distancia", Math.round(s.distancia()), "tiempo", Math.round(s.tiempo()), "x", Math.round(s.x() * 10) / 10.0, "y", Math.round(s.y() * 10) / 10.0));
            salida.add(Json.obj("etiqueta", o.etiqueta(), "distancia", Math.round(o.distancia()), "tiempoLibre", Math.round(o.tiempoLibre()), "tiempoActual", Math.round(o.tiempoActual()),
                    "retraso", Math.max(0, Math.round(o.tiempoActual() - o.tiempoLibre())), "geometria", g, "pasos", pasos, "vias", o.vias()));
        }
        return Json.obj("opciones", salida, "modoSemaforos", opciones[1], "tiempoSimulado", opciones[2], "aviso", "Los tiempos son estimaciones del modelo de tráfico, no mediciones reales.");
    }

    private Object comparar(Peticion p) {
        Map<String, Object> c = p.cuerpo();
        long semilla = (long) numero(c, "semilla", 0, 1e9, 1.0);
        double vpm = numero(c, "vehiculosPorMinuto", 1, 400, 80.0), duracion = numero(c, "duracion", 60, 3600, 600.0);
        List<Accion> acciones = new ArrayList<>();
        if (c.get("incidenteVia") instanceof String via && !via.isBlank()) acciones.add(new Accion(numero(c, "incidenteT", 0, duracion, 120.0), "incidente", via, (int) numero(c, "gravedad", 1, 3, 3.0), null));
        if (c.get("emergenciaNodo") instanceof String nodo && !nodo.isBlank()) acciones.add(new Accion(numero(c, "emergenciaT", 0, duracion, 200.0), "emergencia", null, 0, nodo));
        if (!comparaciones.tryAcquire()) throw new ErrorHttp(429, "Ya hay comparaciones en curso. Intenta en unos segundos.");
        try {
            Resultado[] r = ComparadorEscenarios.comparar(servicioRed.copia().red(), semilla, vpm, duracion, acciones);
            List<Object> salida = new ArrayList<>();
            for (Resultado x : r) {
                Map<String, Object> resumen = Json.obj("nombre", x.nombre(), "generados", x.generados(), "completados", x.completados(), "viajeMedio", x.viajeMedio(), "viajeMedioAjustado", x.viajeMedioAjustado(),
                        "esperaMedia", x.esperaMedia(), "velocidadMedia", x.velocidadMedia(), "viasCongestionadasMedia", x.viasCongestionadasMedia(), "respuestaMedia", x.respuestaMedia(),
                        "emergenciasAtendidas", x.emergenciasAtendidas(), "reruteos", x.reruteos());
                long id = repo.guardarSimulacion(p.usuario.id(), "comparacion", x.nombre().equals("SmartTraffic") ? "INTELIGENTE" : "TRADICIONAL", semilla, vpm, duracion, Json.escribir(resumen), x.serie());
                List<Object> serie = new ArrayList<>();
                for (var m : x.serie()) serie.add(Json.obj("t", m.t(), "velocidadKmh", m.velocidadMediaKmh(), "congestionadas", m.viasCongestionadas(), "activos", m.activos(), "viajeMedio", m.tiempoViajeMedio(), "esperaMedia", m.esperaMedia()));
                Map<String, Object> fila = new java.util.LinkedHashMap<>(resumen);
                fila.put("id", id);
                fila.put("serie", serie);
                salida.add(fila);
            }
            auditar(p, "comparar", "semilla " + semilla + ", " + vpm + " veh/min, " + duracion + " s");
            return Json.obj("semilla", semilla, "vehiculosPorMinuto", vpm, "duracion", duracion, "escenarios", salida);
        } finally {
            comparaciones.release();
        }
    }

    // ================================================================= atención de peticiones

    private void atender(HttpExchange ex) throws IOException {
        try {
            Headers h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "strict-origin-when-cross-origin"); // los servidores de mapas piden saber desde qué sitio se les llama
            h.set("X-Frame-Options", "DENY");
            String origenPeticion = ex.getRequestHeaders().getFirst("Origin");
            if (origenPeticion != null && origenPermitido(origenPeticion)) {
                h.set("Access-Control-Allow-Origin", origenPeticion);
                h.add("Vary", "Origin");
                h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
                h.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                h.set("Access-Control-Max-Age", "600");
            }
            String metodo = ex.getRequestMethod().toUpperCase();
            if (metodo.equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); return; }
            String camino = ex.getRequestURI().getPath();
            if (!camino.startsWith("/api/")) { servirEstatico(ex, camino); return; }
            Peticion p = new Peticion(metodo, camino, ex.getRemoteAddress().getAddress().getHostAddress());
            analizarConsulta(ex.getRequestURI().getRawQuery(), p.consulta);
            if (camino.equals("/api/sim/stream") && metodo.equals("GET")) { transmitir(ex, p); return; }
            h.set("Cache-Control", "no-store");
            Ruta ruta = null;
            boolean caminoConocido = false;
            for (Ruta r : rutas) {
                Matcher m = r.patron().matcher(camino);
                if (!m.matches()) continue;
                caminoConocido = true;
                if (!r.metodo().equals(metodo)) continue;
                for (int i = 0; i < r.nombres().size(); i++) p.params.put(r.nombres().get(i), URLDecoder.decode(m.group(i + 1), StandardCharsets.UTF_8));
                ruta = r;
                break;
            }
            Object resultado;
            try {
                if (ruta == null) throw new ErrorHttp(caminoConocido ? 405 : 404, caminoConocido ? "Método no permitido" : "No existe ese recurso");
                if (ruta.roles() != null) {
                    p.usuario = autenticar(ex.getRequestHeaders().getFirst("Authorization"));
                    if (!ruta.roles().contains(rol(p.usuario.rol()))) throw new ErrorHttp(403, "Tu rol no permite esta acción.");
                }
                if (metodo.equals("POST") || metodo.equals("PUT")) {
                    byte[] cuerpo = ex.getRequestBody().readNBytes(ruta.maxCuerpo() + 1);
                    if (cuerpo.length > ruta.maxCuerpo()) throw new ErrorHttp(413, "El contenido es demasiado grande.");
                    p.cuerpoTexto = new String(cuerpo, StandardCharsets.UTF_8);
                }
                resultado = ruta.manejador().manejar(p);
            } catch (ErrorHttp e) {
                resultado = new Respuesta(e.estado, Json.obj("error", e.getMessage()));
            } catch (IllegalArgumentException | Json.ErrorJson e) { // incluye NumberFormatException
                resultado = new Respuesta(400, Json.obj("error", e.getMessage()));
            } catch (NoSuchElementException e) {
                resultado = new Respuesta(404, Json.obj("error", e.getMessage()));
            } catch (IllegalStateException e) {
                resultado = new Respuesta(409, Json.obj("error", e.getMessage()));
            } catch (Exception e) {
                System.err.println("Error interno en " + metodo + " " + camino + ": " + e);
                resultado = new Respuesta(500, Json.obj("error", "Error interno del servidor."));
            }
            enviar(ex, resultado);
        } finally {
            ex.close();
        }
    }

    private boolean origenPermitido(String origen) {
        for (Pattern p : origenesPermitidos) if (p.matcher(origen).matches()) return true;
        return false;
    }

    private Usuario autenticar(String cabecera) {
        if (cabecera == null || !cabecera.startsWith("Bearer ")) throw new ErrorHttp(401, "Debes iniciar sesión.");
        return usuarioDelToken(cabecera.substring(7));
    }

    private Usuario usuarioDelToken(String token) {
        Map<String, Object> claims = Seguridad.verificarToken(opc.secretoJwt, token);
        if (claims == null) throw new ErrorHttp(401, "La sesión no es válida o expiró. Inicia sesión de nuevo.");
        Usuario u = repo.usuarioPorId(((Number) claims.get("sub")).longValue()).orElse(null);
        if (u == null || !u.activo()) throw new ErrorHttp(401, "El usuario ya no está activo.");
        return u;
    }

    private static void analizarConsulta(String q, Map<String, String> destino) {
        if (q == null) return;
        for (String par : q.split("&")) {
            int i = par.indexOf('=');
            if (i > 0) destino.put(URLDecoder.decode(par.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
        }
    }

    private static void enviar(HttpExchange ex, Object resultado) throws IOException {
        int estado = 200;
        Object cuerpo = resultado;
        if (resultado instanceof Respuesta r) { estado = r.estado(); cuerpo = r.cuerpo(); }
        byte[] datos = (cuerpo instanceof Crudo c ? c.json() : Json.escribir(cuerpo)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (datos.length > MIN_COMPRIMIR && aceptaGzip(ex)) {
            datos = comprimir(datos);
            ex.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().add("Vary", "Accept-Encoding");
        ex.sendResponseHeaders(estado, datos.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(datos);
        }
    }

    /** Por debajo de este tamaño comprimir no compensa. */
    private static final int MIN_COMPRIMIR = 1024;

    private static boolean aceptaGzip(HttpExchange ex) {
        String a = ex.getRequestHeaders().getFirst("Accept-Encoding");
        return a != null && a.toLowerCase().contains("gzip");
    }

    private static byte[] comprimir(byte[] datos) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(datos.length / 3 + 64);
        try (java.util.zip.GZIPOutputStream z = new java.util.zip.GZIPOutputStream(b)) {
            z.write(datos);
        }
        return b.toByteArray();
    }

    /** Transmisión en vivo: cada instantánea de la simulación llega como un evento SSE. */
    private void transmitir(HttpExchange ex, Peticion p) throws IOException {
        Usuario u;
        try {
            u = usuarioDelToken(p.consulta.getOrDefault("token", ""));
        } catch (ErrorHttp e) {
            enviar(ex, new Respuesta(e.estado, Json.obj("error", e.getMessage())));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        GestorSimulacion.Suscriptor s = gestor.suscribir();
        try (OutputStream o = ex.getResponseBody()) {
            while (true) {
                String dato = s.siguiente(15000);
                o.write((dato == null ? ": keep-alive\n\n" : "data: " + dato + "\n\n").getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        } catch (IOException | InterruptedException e) {
            // el navegador cerró la conexión
        } finally {
            gestor.cancelar(s);
        }
    }

    // ================================================================= archivos de la pantalla

    private static final Map<String, String> TIPOS = Map.ofEntries(Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"), Map.entry("svg", "image/svg+xml"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("map", "application/json"));

    private void servirEstatico(HttpExchange ex, String camino) throws IOException {
        Path base = Path.of(opc.dirEstatico).toAbsolutePath().normalize();
        String rel = camino.equals("/") ? "index.html" : camino.substring(1);
        Path archivo = base.resolve(rel).normalize();
        if (!archivo.startsWith(base)) { ex.sendResponseHeaders(403, -1); return; }
        if (!Files.isRegularFile(archivo)) archivo = base.resolve("index.html"); // la pantalla es una SPA: cualquier ruta abre la app
        if (!Files.isRegularFile(archivo)) {
            byte[] msg = "SmartTraffic: la pantalla no está compilada. Ejecuta ./construir-frontend.sh".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(msg); }
            return;
        }
        String nombre = archivo.getFileName().toString();
        String ext = nombre.contains(".") ? nombre.substring(nombre.lastIndexOf('.') + 1).toLowerCase() : "";
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", TIPOS.getOrDefault(ext, "application/octet-stream"));
        if (ext.equals("html")) {
            h.set("Cache-Control", "no-cache");
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'");
        } else {
            h.set("Cache-Control", camino.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        }
        byte[] datos = Files.readAllBytes(archivo);
        boolean texto = List.of("html", "js", "css", "json", "svg", "map", "txt").contains(ext);
        if (texto && datos.length > MIN_COMPRIMIR && aceptaGzip(ex)) {
            Comprimido c = comprimidos.get(archivo);
            long modificado = Files.getLastModifiedTime(archivo).toMillis();
            if (c == null || c.modificado() != modificado || c.original() != datos.length) { // los archivos de la pantalla casi nunca cambian: se comprimen una sola vez
                c = new Comprimido(modificado, datos.length, comprimir(datos));
                comprimidos.put(archivo, c);
            }
            datos = c.bytes();
            h.set("Content-Encoding", "gzip");
        }
        if (texto) h.add("Vary", "Accept-Encoding");
        ex.sendResponseHeaders(200, datos.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(datos); }
    }
}
