package smarttraffic.datos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.sim.Estadisticas;
import smarttraffic.util.Json;

/**
 * Persistencia con JDBC puro. Funciona con H2 (desarrollo y pruebas) y con PostgreSQL (producción)
 * porque el esquema usa solo SQL portable.
 */
public final class Repositorio {

    public record Usuario(long id, String nombre, String correo, String claveHash, String rol, boolean activo) {}

    private final String url;
    private final String usuarioDb;
    private final String claveDb;

    public Repositorio(String url, String usuarioDb, String claveDb) {
        this.url = url;
        this.usuarioDb = usuarioDb;
        this.claveDb = claveDb;
        crearEsquema();
    }

    // ------------------------------------------------------------------ reserva de conexiones
    // Abrir una conexión a una base remota (Neon) cuesta decenas o cientos de milisegundos: cifrado y autenticación.
    // Se reutilizan: al «cerrar» una conexión de la reserva, vuelve a ella en lugar de cerrarse.

    private static final int MAX_CONEXIONES = 8;
    private static final long REVISAR_TRAS_MS = 20_000; // una conexión inactiva más tiempo que esto se comprueba antes de usarla

    private record Libre(Connection conexion, long desde) {}

    // Se usa como pila: se reutiliza siempre la conexión que se acaba de devolver (la más «caliente»); las demás pueden cerrarse por inactividad.
    private final java.util.concurrent.LinkedBlockingDeque<Libre> libres = new java.util.concurrent.LinkedBlockingDeque<>(MAX_CONEXIONES);
    private final java.util.concurrent.atomic.AtomicInteger abiertas = new java.util.concurrent.atomic.AtomicInteger();

    /** Conexión de la reserva. Se usa con try-with-resources: al cerrarla vuelve a la reserva. */
    public Connection conexion() throws SQLException {
        Libre l;
        while ((l = libres.pollFirst()) != null) {
            if (sirve(l)) return envolver(l.conexion());
            descartar(l.conexion());
        }
        if (abiertas.incrementAndGet() <= MAX_CONEXIONES) {
            try {
                return envolver(DriverManager.getConnection(url, usuarioDb, claveDb));
            } catch (SQLException e) {
                abiertas.decrementAndGet();
                throw e;
            }
        }
        abiertas.decrementAndGet();
        try {
            l = libres.pollFirst(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Se interrumpió la espera de una conexión a la base de datos.");
        }
        if (l == null) throw new SQLException("La base de datos está ocupada: no hubo una conexión libre en 10 segundos.");
        if (sirve(l)) return envolver(l.conexion());
        descartar(l.conexion());
        return conexion();
    }

    private static boolean sirve(Libre l) {
        try {
            if (l.conexion().isClosed()) return false;
            return System.currentTimeMillis() - l.desde() < REVISAR_TRAS_MS || l.conexion().isValid(2);
        } catch (SQLException e) {
            return false;
        }
    }

    private void descartar(Connection c) {
        abiertas.decrementAndGet();
        try {
            c.close();
        } catch (SQLException ignorar) {
            // ya estaba cerrada
        }
    }

    private void devolver(Connection c) {
        try {
            if (c.isClosed()) { abiertas.decrementAndGet(); return; }
            if (!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); } // lo que quedó a medias no pasa a quien la use después
            if (!libres.offerFirst(new Libre(c, System.currentTimeMillis()))) descartar(c);
        } catch (SQLException e) {
            descartar(c);
        }
    }

    private Connection envolver(Connection real) {
        java.util.concurrent.atomic.AtomicBoolean devuelta = new java.util.concurrent.atomic.AtomicBoolean();
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, metodo, args) -> {
            if (metodo.getName().equals("close")) {
                if (devuelta.compareAndSet(false, true)) devolver(real);
                return null;
            }
            if (devuelta.get() && !metodo.getName().equals("isClosed")) throw new SQLException("La conexión ya se devolvió a la reserva.");
            if (metodo.getName().equals("isClosed") && devuelta.get()) return true;
            try {
                return metodo.invoke(real, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    // ------------------------------------------------------------------ usuarios recordados unos segundos
    // Cada petición comprueba que el usuario siga activo; así se evita una consulta a la base de datos en cada una.
    // Cualquier cambio de usuarios vacía la memoria, de modo que desactivar a alguien sigue siendo inmediato.

    private record Recordado(Usuario usuario, long vence) {}

    private static final long VIGENCIA_USUARIO_MS = 30_000;
    private final java.util.concurrent.ConcurrentHashMap<Long, Recordado> usuariosRecordados = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String[] ESQUEMA = {
        "CREATE TABLE IF NOT EXISTS usuarios (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, nombre VARCHAR(120) NOT NULL, correo VARCHAR(160) NOT NULL UNIQUE, "
            + "clave_hash VARCHAR(300) NOT NULL, rol VARCHAR(20) NOT NULL, activo BOOLEAN NOT NULL DEFAULT TRUE, creado TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS redes (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, nombre VARCHAR(200) NOT NULL, origen_lat DOUBLE PRECISION, origen_lon DOUBLE PRECISION, "
            + "activa BOOLEAN NOT NULL DEFAULT FALSE, creado TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS intersecciones (red_id BIGINT NOT NULL REFERENCES redes(id) ON DELETE CASCADE, id VARCHAR(40) NOT NULL, orden INT NOT NULL, nombre VARCHAR(250) NOT NULL, "
            + "x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, lat DOUBLE PRECISION, lon DOUBLE PRECISION, tipo VARCHAR(20) NOT NULL, semaforizada BOOLEAN NOT NULL, "
            + "servicios VARCHAR(80) NOT NULL DEFAULT '', PRIMARY KEY (red_id, id))",
        "CREATE TABLE IF NOT EXISTS vias (red_id BIGINT NOT NULL REFERENCES redes(id) ON DELETE CASCADE, id VARCHAR(80) NOT NULL, orden INT NOT NULL, desde VARCHAR(40) NOT NULL, hacia VARCHAR(40) NOT NULL, "
            + "nombre VARCHAR(250) NOT NULL, longitud DOUBLE PRECISION NOT NULL, velocidad DOUBLE PRECISION NOT NULL, clase VARCHAR(40) NOT NULL DEFAULT '', manual BOOLEAN NOT NULL, "
            + "puntos TEXT NOT NULL, PRIMARY KEY (red_id, id))",
        "CREATE TABLE IF NOT EXISTS accidentes (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, lat DOUBLE PRECISION NOT NULL, lon DOUBLE PRECISION NOT NULL, anio INT NOT NULL, gravedad INT NOT NULL)",
        "CREATE TABLE IF NOT EXISTS simulaciones (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, usuario_id BIGINT REFERENCES usuarios(id), tipo VARCHAR(30) NOT NULL, "
            + "modo VARCHAR(20) NOT NULL, semilla BIGINT NOT NULL, vehiculos_por_minuto DOUBLE PRECISION NOT NULL, duracion DOUBLE PRECISION NOT NULL, resumen TEXT NOT NULL, "
            + "creado TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)",
        "CREATE TABLE IF NOT EXISTS metricas (simulacion_id BIGINT NOT NULL REFERENCES simulaciones(id) ON DELETE CASCADE, t DOUBLE PRECISION NOT NULL, activos INT NOT NULL, en_espera INT NOT NULL, "
            + "velocidad_kmh DOUBLE PRECISION NOT NULL, congestionadas INT NOT NULL, completados INT NOT NULL, viaje_medio DOUBLE PRECISION NOT NULL, espera_media DOUBLE PRECISION NOT NULL)",
        "CREATE TABLE IF NOT EXISTS auditoria (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, usuario_id BIGINT, accion VARCHAR(60) NOT NULL, detalle VARCHAR(500), momento TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)"
    };

    private void crearEsquema() {
        try (Connection c = conexion(); Statement s = c.createStatement()) {
            for (String sql : ESQUEMA) s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo preparar la base de datos: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ usuarios

    private static Usuario usuario(ResultSet r) throws SQLException {
        return new Usuario(r.getLong("id"), r.getString("nombre"), r.getString("correo"), r.getString("clave_hash"), r.getString("rol"), r.getBoolean("activo"));
    }

    public long crearUsuario(String nombre, String correo, String claveHash, String rol) {
        usuariosRecordados.clear();
        try (Connection c = conexion(); PreparedStatement p = c.prepareStatement("INSERT INTO usuarios (nombre, correo, clave_hash, rol) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1, nombre);
            p.setString(2, correo.toLowerCase());
            p.setString(3, claveHash);
            p.setString(4, rol);
            p.executeUpdate();
            try (ResultSet k = p.getGeneratedKeys()) {
                k.next();
                return k.getLong(1);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && (e.getMessage().toLowerCase().contains("unique") || e.getMessage().toLowerCase().contains("duplicate"))) throw new IllegalArgumentException("Ya existe un usuario con ese correo.");
            throw new IllegalStateException(e);
        }
    }

    public Optional<Usuario> usuarioPorCorreo(String correo) {
        try (Connection c = conexion(); PreparedStatement p = c.prepareStatement("SELECT * FROM usuarios WHERE correo = ?")) {
            p.setString(1, correo.toLowerCase());
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(usuario(r)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<Usuario> usuarioPorId(long id) {
        Recordado rec = usuariosRecordados.get(id);
        if (rec != null && rec.vence() > System.currentTimeMillis()) return Optional.of(rec.usuario());
        try (Connection c = conexion(); PreparedStatement p = c.prepareStatement("SELECT * FROM usuarios WHERE id = ?")) {
            p.setLong(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                Usuario u = usuario(r);
                usuariosRecordados.put(id, new Recordado(u, System.currentTimeMillis() + VIGENCIA_USUARIO_MS));
                return Optional.of(u);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Usuario> usuarios() {
        List<Usuario> lista = new ArrayList<>();
        try (Connection c = conexion(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM usuarios ORDER BY id")) {
            while (r.next()) lista.add(usuario(r));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return lista;
    }

    public void actualizarUsuario(long id, String nombre, String rol, Boolean activo, String claveHash) {
        usuariosRecordados.clear();
        try (Connection c = conexion()) {
            if (nombre != null) ejecutar(c, "UPDATE usuarios SET nombre = ? WHERE id = ?", nombre, id);
            if (rol != null) ejecutar(c, "UPDATE usuarios SET rol = ? WHERE id = ?", rol, id);
            if (activo != null) ejecutar(c, "UPDATE usuarios SET activo = ? WHERE id = ?", activo, id);
            if (claveHash != null) ejecutar(c, "UPDATE usuarios SET clave_hash = ? WHERE id = ?", claveHash, id);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ejecutar(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) p.setObject(i + 1, params[i]);
            p.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ red vial

    /** Guarda la red como la activa y elimina las anteriores. Devuelve el id de la red. */
    public long guardarRedActiva(RedVial red, ProyeccionLocal proy) {
        try (Connection c = conexion()) {
            c.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement p = c.prepareStatement("INSERT INTO redes (nombre, origen_lat, origen_lon, activa) VALUES (?,?,?,TRUE)", Statement.RETURN_GENERATED_KEYS)) {
                    p.setString(1, red.nombre());
                    if (proy == null) { p.setNull(2, java.sql.Types.DOUBLE); p.setNull(3, java.sql.Types.DOUBLE); } else { p.setDouble(2, proy.lat0()); p.setDouble(3, proy.lon0()); }
                    p.executeUpdate();
                    try (ResultSet k = p.getGeneratedKeys()) {
                        k.next();
                        id = k.getLong(1);
                    }
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO intersecciones (red_id, id, orden, nombre, x, y, lat, lon, tipo, semaforizada, servicios) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    int orden = 0;
                    for (Interseccion i : red.intersecciones()) {
                        p.setLong(1, id); p.setString(2, i.id()); p.setInt(3, orden++); p.setString(4, i.nombre()); p.setDouble(5, i.x()); p.setDouble(6, i.y());
                        if (Double.isNaN(i.latitud())) { p.setNull(7, java.sql.Types.DOUBLE); p.setNull(8, java.sql.Types.DOUBLE); } else { p.setDouble(7, i.latitud()); p.setDouble(8, i.longitud()); }
                        p.setString(9, i.tipo().name()); p.setBoolean(10, i.semaforizada()); p.setString(11, String.join(",", i.servicios()));
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                try (PreparedStatement p = c.prepareStatement("INSERT INTO vias (red_id, id, orden, desde, hacia, nombre, longitud, velocidad, clase, manual, puntos) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    int orden = 0;
                    for (Via v : red.vias()) {
                        p.setLong(1, id); p.setString(2, v.id()); p.setInt(3, orden++); p.setString(4, v.desde()); p.setString(5, v.hacia()); p.setString(6, v.nombre());
                        p.setDouble(7, v.longitud()); p.setDouble(8, v.velocidadMaxKmh()); p.setString(9, v.clase()); p.setBoolean(10, v.longitudManual()); p.setString(11, Json.escribir(v.puntos()));
                        p.addBatch();
                    }
                    p.executeBatch();
                }
                ejecutar(c, "UPDATE redes SET activa = FALSE WHERE id <> ?", id);
                ejecutar(c, "DELETE FROM redes WHERE id <> ?", id);
                c.commit();
                return id;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo guardar la red: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public Optional<RedVialJson.Cargada> cargarRedActiva() {
        try (Connection c = conexion()) {
            long id;
            String nombre;
            ProyeccionLocal proy = null;
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM redes WHERE activa = TRUE ORDER BY id DESC")) {
                if (!r.next()) return Optional.empty();
                id = r.getLong("id");
                nombre = r.getString("nombre");
                double lat = r.getDouble("origen_lat");
                if (!r.wasNull()) proy = new ProyeccionLocal(lat, r.getDouble("origen_lon"));
            }
            RedVial red = new RedVial(nombre);
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM intersecciones WHERE red_id = ? ORDER BY orden")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        Interseccion i = new Interseccion(r.getString("id"), r.getString("nombre"), r.getDouble("x"), r.getDouble("y"), TipoNodo.valueOf(r.getString("tipo")));
                        double lat = r.getDouble("lat");
                        if (!r.wasNull()) i.setGeo(lat, r.getDouble("lon"));
                        i.setSemaforizada(r.getBoolean("semaforizada"));
                        String sv = r.getString("servicios");
                        if (sv != null && !sv.isBlank()) for (String x : sv.split(",")) i.servicios().add(x);
                        red.agregarInterseccion(i);
                    }
                }
            }
            try (PreparedStatement p = c.prepareStatement("SELECT * FROM vias WHERE red_id = ? ORDER BY orden")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        List<Object> pts = (List<Object>) Json.leer(r.getString("puntos"));
                        double[] puntos = new double[pts.size()];
                        for (int k = 0; k < puntos.length; k++) puntos[k] = ((Number) pts.get(k)).doubleValue();
                        boolean manual = r.getBoolean("manual");
                        Via v = red.agregarVia(r.getString("id"), r.getString("desde"), r.getString("hacia"), r.getString("nombre"), manual ? r.getDouble("longitud") : null, r.getDouble("velocidad"), puntos);
                        v.setClase(r.getString("clase"));
                    }
                }
            }
            return Optional.of(new RedVialJson.Cargada(red, proy));
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la red: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ accidentes (datos abiertos)

    public void reemplazarAccidentes(List<DatosAbiertos.Accidente> lista) {
        try (Connection c = conexion()) {
            c.setAutoCommit(false);
            ejecutar(c, "DELETE FROM accidentes");
            try (PreparedStatement p = c.prepareStatement("INSERT INTO accidentes (lat, lon, anio, gravedad) VALUES (?,?,?,?)")) {
                for (DatosAbiertos.Accidente a : lista) { p.setDouble(1, a.lat()); p.setDouble(2, a.lon()); p.setInt(3, a.anio()); p.setInt(4, a.gravedad()); p.addBatch(); }
                p.executeBatch();
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<DatosAbiertos.Accidente> accidentes() {
        List<DatosAbiertos.Accidente> lista = new ArrayList<>();
        try (Connection c = conexion(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT lat, lon, anio, gravedad FROM accidentes ORDER BY id")) {
            while (r.next()) lista.add(new DatosAbiertos.Accidente(r.getDouble(1), r.getDouble(2), r.getInt(3), r.getInt(4)));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return lista;
    }

    // ------------------------------------------------------------------ historial de simulaciones

    public long guardarSimulacion(long usuarioId, String tipo, String modo, long semilla, double vehiculosPorMinuto, double duracion, String resumenJson, List<Estadisticas.Muestra> serie) {
        try (Connection c = conexion()) {
            c.setAutoCommit(false);
            long id;
            try (PreparedStatement p = c.prepareStatement("INSERT INTO simulaciones (usuario_id, tipo, modo, semilla, vehiculos_por_minuto, duracion, resumen) VALUES (?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                p.setLong(1, usuarioId); p.setString(2, tipo); p.setString(3, modo); p.setLong(4, semilla); p.setDouble(5, vehiculosPorMinuto); p.setDouble(6, duracion); p.setString(7, resumenJson);
                p.executeUpdate();
                try (ResultSet k = p.getGeneratedKeys()) {
                    k.next();
                    id = k.getLong(1);
                }
            }
            try (PreparedStatement p = c.prepareStatement("INSERT INTO metricas (simulacion_id, t, activos, en_espera, velocidad_kmh, congestionadas, completados, viaje_medio, espera_media) VALUES (?,?,?,?,?,?,?,?,?)")) {
                for (Estadisticas.Muestra m : serie) {
                    p.setLong(1, id); p.setDouble(2, m.t()); p.setInt(3, m.activos()); p.setInt(4, m.enEspera()); p.setDouble(5, m.velocidadMediaKmh()); p.setInt(6, m.viasCongestionadas());
                    p.setInt(7, m.completados()); p.setDouble(8, m.tiempoViajeMedio()); p.setDouble(9, m.esperaMedia());
                    p.addBatch();
                }
                p.executeBatch();
            }
            c.commit();
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<Map<String, Object>> historial(int limite) {
        List<Map<String, Object>> lista = new ArrayList<>();
        try (Connection c = conexion(); PreparedStatement p = c.prepareStatement("SELECT s.id, s.tipo, s.modo, s.semilla, s.vehiculos_por_minuto, s.duracion, s.creado, u.nombre FROM simulaciones s LEFT JOIN usuarios u ON u.id = s.usuario_id ORDER BY s.id DESC LIMIT ?")) {
            p.setInt(1, limite);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getLong(1)); m.put("tipo", r.getString(2)); m.put("modo", r.getString(3)); m.put("semilla", r.getLong(4)); m.put("vehiculosPorMinuto", r.getDouble(5));
                    m.put("duracion", r.getDouble(6)); m.put("creado", r.getTimestamp(7).toInstant().toString()); m.put("usuario", r.getString(8));
                    lista.add(m);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return lista;
    }

    public Optional<Map<String, Object>> detalleSimulacion(long id) {
        try (Connection c = conexion()) {
            Map<String, Object> m = new LinkedHashMap<>();
            try (PreparedStatement p = c.prepareStatement("SELECT tipo, modo, semilla, vehiculos_por_minuto, duracion, resumen, creado FROM simulaciones WHERE id = ?")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    m.put("id", id); m.put("tipo", r.getString(1)); m.put("modo", r.getString(2)); m.put("semilla", r.getLong(3)); m.put("vehiculosPorMinuto", r.getDouble(4)); m.put("duracion", r.getDouble(5));
                    m.put("resumen", Json.leer(r.getString(6)));
                    Timestamp t = r.getTimestamp(7);
                    m.put("creado", t.toInstant().toString());
                }
            }
            List<Object> serie = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement("SELECT t, activos, en_espera, velocidad_kmh, congestionadas, completados, viaje_medio, espera_media FROM metricas WHERE simulacion_id = ? ORDER BY t")) {
                p.setLong(1, id);
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) serie.add(Json.obj("t", r.getDouble(1), "activos", r.getInt(2), "enEspera", r.getInt(3), "velocidadKmh", r.getDouble(4), "congestionadas", r.getInt(5), "completados", r.getInt(6), "viajeMedio", r.getDouble(7), "esperaMedia", r.getDouble(8)));
                }
            }
            m.put("serie", serie);
            return Optional.of(m);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public void auditar(Long usuarioId, String accion, String detalle) {
        try (Connection c = conexion()) {
            ejecutar(c, "INSERT INTO auditoria (usuario_id, accion, detalle) VALUES (?,?,?)", usuarioId, accion, detalle == null ? null : detalle.substring(0, Math.min(500, detalle.length())));
        } catch (SQLException e) {
            // la auditoría nunca debe tumbar una operación
        }
    }

    public int contarAuditoria(String accion) {
        try (Connection c = conexion(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM auditoria WHERE accion = ?")) {
            p.setString(1, accion);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
