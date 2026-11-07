package smarttraffic.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import smarttraffic.red.Via;
import smarttraffic.datos.ProyeccionLocal;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.util.Json;

/**
 * Red vial editable por el administrador. Cada cambio se valida, se guarda en la base de datos y, si
 * la red resultante se puede simular, se carga en la simulación en vivo.
 */
public final class ServicioRed {
    private final Repositorio repo;
    private final GestorSimulacion gestor;
    private RedVial red;
    private ProyeccionLocal proy;

    public ServicioRed(Repositorio repo, GestorSimulacion gestor, RedVial red, ProyeccionLocal proy) {
        this.repo = repo;
        this.gestor = gestor;
        this.red = red;
        this.proy = proy;
    }

    public synchronized String json() {
        return RedVialJson.escribir(red, proy);
    }

    public synchronized RedVialJson.Cargada copia() {
        return RedVialJson.leer(RedVialJson.escribir(red, proy));
    }

    private static String normalizar(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9 ]", " ").trim().replaceAll("\\s+", " ");
    }

    /** Busca calles, intersecciones y bases de emergencia por nombre (sin tildes ni mayúsculas). Todas las palabras deben aparecer. */
    public synchronized List<Map<String, Object>> buscarLocal(String consulta, int max) {
        String q = normalizar(consulta);
        if (q.isEmpty()) return List.of();
        String[] palabras = q.split(" ");
        record Hallazgo(int puntaje, Map<String, Object> dato) {}
        List<Hallazgo> hallazgos = new ArrayList<>();
        java.util.Map<String, Via> porCalle = new java.util.LinkedHashMap<>();
        for (Via v : red.vias()) {
            if (v.nombre().isBlank() || v.nombre().equals(v.id())) continue;
            Via previa = porCalle.get(v.nombre());
            if (previa == null || v.longitud() > previa.longitud()) porCalle.put(v.nombre(), v);
        }
        for (Via v : porCalle.values()) {
            String n = normalizar(v.nombre());
            if (!contiene(n, palabras)) continue;
            double[] p = v.puntoEn(v.longitudGeometria() / 2);
            hallazgos.add(new Hallazgo(puntaje(n, q, 0), dato("calle", v.nombre(), "", p[0], p[1])));
        }
        for (Interseccion i : red.intersecciones()) {
            String n = normalizar(i.nombre());
            if (!contiene(n, palabras)) continue;
            hallazgos.add(new Hallazgo(puntaje(n, q, i.servicios().isEmpty() ? 1 : 0), dato(i.servicios().isEmpty() ? "interseccion" : "servicio", i.nombre(), "", i.x(), i.y())));
        }
        hallazgos.sort((a, b) -> a.puntaje() != b.puntaje() ? Integer.compare(a.puntaje(), b.puntaje()) : ((String) a.dato().get("nombre")).compareTo((String) b.dato().get("nombre")));
        List<Map<String, Object>> salida = new ArrayList<>();
        for (Hallazgo h : hallazgos) {
            if (salida.size() >= max) break;
            salida.add(h.dato());
        }
        return salida;
    }

    private static boolean contiene(String nombre, String[] palabras) {
        for (String p : palabras) if (!nombre.contains(p)) return false;
        return true;
    }

    /** Menor es mejor: primero los que empiezan igual que lo escrito, luego los nombres más cortos. */
    private static int puntaje(String nombre, String q, int tipo) {
        return (nombre.startsWith(q) ? 0 : nombre.contains(" " + q) ? 1 : 2) * 1000 + tipo * 300 + Math.min(299, nombre.length());
    }

    private Map<String, Object> dato(String tipo, String nombre, String detalle, double x, double y) {
        Map<String, Object> m = Json.obj("tipo", tipo, "nombre", nombre, "detalle", detalle, "x", x, "y", y);
        if (proy != null) { m.put("lat", proy.lat(y)); m.put("lon", proy.lon(x)); }
        return m;
    }

    /** Límites de la red en metros locales: {minX, minY, maxX, maxY}. */
    public synchronized double[] caja() {
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (Interseccion i : red.intersecciones()) { a = Math.min(a, i.x()); b = Math.min(b, i.y()); c = Math.max(c, i.x()); d = Math.max(d, i.y()); }
        return new double[] {a, b, c, d};
    }

    public synchronized ProyeccionLocal proyeccion() {
        return proy;
    }

    /** Resultado de una edición: si quedó simulable y, si no, qué falta. */
    public record Resultado(boolean simulable, List<String> problemas, String id) {}

    private Resultado aplicar(String id) {
        List<String> problemas = red.validarParaSimulacion();
        repo.guardarRedActiva(red, proy);
        if (problemas.isEmpty()) gestor.reemplazarRed(RedVialJson.leer(RedVialJson.escribir(red, proy)).red());
        return new Resultado(problemas.isEmpty(), problemas, id);
    }

    private static double numero(Map<String, Object> m, String clave, Double defecto) {
        Object o = m.get(clave);
        if (o instanceof Number n && Double.isFinite(n.doubleValue())) return n.doubleValue();
        if (defecto != null) return defecto;
        throw new IllegalArgumentException("Falta el número «" + clave + "»");
    }

    private static String texto(Map<String, Object> m, String clave, String defecto, int max) {
        String s = Json.texto(m, clave, defecto);
        if (s != null && s.length() > max) throw new IllegalArgumentException("«" + clave + "» es demasiado largo (máximo " + max + " caracteres)");
        return s;
    }

    // ------------------------------------------------------------------ intersecciones

    @SuppressWarnings("unchecked")
    public synchronized Resultado crearInterseccion(Map<String, Object> d) {
        String id = "U" + UUID.randomUUID().toString().substring(0, 8);
        TipoNodo tipo = tipo(d, TipoNodo.INTERIOR);
        Interseccion i = new Interseccion(id, texto(d, "nombre", "Intersección nueva", 200), numero(d, "x", null), numero(d, "y", null), tipo);
        i.setSemaforizada(Json.booleano(d, "semaforizada", true));
        if (d.get("servicios") instanceof List<?> l) for (Object s : l) if (List.of("hospital", "bomberos", "policia").contains(String.valueOf(s))) i.servicios().add(String.valueOf(s));
        if (proy != null) i.setGeo(proy.lat(i.y()), proy.lon(i.x()));
        red.agregarInterseccion(i);
        return aplicar(id);
    }

    public synchronized Resultado actualizarInterseccion(String id, Map<String, Object> d) {
        Interseccion i = red.interseccion(id);
        if (i == null) throw new java.util.NoSuchElementException("No existe la intersección " + id);
        if (d.containsKey("nombre")) i.setNombre(texto(d, "nombre", i.nombre(), 200));
        if (d.containsKey("tipo")) i.setTipo(tipo(d, i.tipo()));
        if (d.containsKey("semaforizada")) i.setSemaforizada(Json.booleano(d, "semaforizada", i.semaforizada()));
        if (d.get("servicios") instanceof List<?> l) {
            i.servicios().clear();
            for (Object s : l) if (List.of("hospital", "bomberos", "policia").contains(String.valueOf(s))) i.servicios().add(String.valueOf(s));
        }
        if (d.containsKey("x") || d.containsKey("y")) {
            double x = numero(d, "x", i.x()), y = numero(d, "y", i.y());
            red.moverInterseccion(id, x, y);
            if (proy != null) i.setGeo(proy.lat(y), proy.lon(x));
        }
        return aplicar(id);
    }

    public synchronized Resultado eliminarInterseccion(String id) {
        if (!red.eliminarInterseccion(id)) throw new java.util.NoSuchElementException("No existe la intersección " + id);
        return aplicar(id);
    }

    private static TipoNodo tipo(Map<String, Object> d, TipoNodo defecto) {
        String t = Json.texto(d, "tipo", null);
        if (t == null) return defecto;
        try {
            return TipoNodo.valueOf(t);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("El tipo debe ser INTERIOR, ENTRADA_SALIDA o BASE_EMERGENCIAS");
        }
    }

    // ------------------------------------------------------------------ vías

    /** Crea una calle: una vía de un solo sentido, o dos si {@code doble} es verdadero. */
    public synchronized Resultado crearVia(Map<String, Object> d) {
        String desde = Json.texto(d, "desde", ""), hacia = Json.texto(d, "hacia", "");
        String base = "C" + UUID.randomUUID().toString().substring(0, 8);
        double vel = numero(d, "velocidad", 40.0);
        String nombre = texto(d, "nombre", "Calle nueva", 200);
        Double largo = d.get("longitud") instanceof Number n ? n.doubleValue() : null;
        red.agregarVia(base + (Json.booleano(d, "doble", true) ? ":AB" : ""), desde, hacia, nombre, largo, vel);
        if (Json.booleano(d, "doble", true)) red.agregarVia(base + ":BA", hacia, desde, nombre, largo, vel);
        return aplicar(base);
    }

    public synchronized Resultado actualizarVia(String id, Map<String, Object> d) {
        Via v = red.via(id);
        if (v == null) throw new java.util.NoSuchElementException("No existe la vía " + id);
        if (d.containsKey("nombre")) v.setNombre(texto(d, "nombre", v.nombre(), 200));
        if (d.containsKey("velocidad")) v.setVelocidadMaxKmh(numero(d, "velocidad", null));
        if (d.containsKey("longitud")) {
            if (d.get("longitud") == null) v.setLongitud(v.longitudGeometria(), false); else v.setLongitud(numero(d, "longitud", null), true);
        }
        return aplicar(id);
    }

    public synchronized Resultado eliminarVia(String id) {
        if (!red.eliminarVia(id)) throw new java.util.NoSuchElementException("No existe la vía " + id);
        return aplicar(id);
    }

    /** Reemplaza toda la red por una red en formato JSON de SmartTraffic (p. ej. generada por el importador de OpenStreetMap). */
    public synchronized Resultado reemplazar(String json) {
        RedVialJson.Cargada c = RedVialJson.leer(json);
        List<String> problemas = new ArrayList<>(c.red().validarParaSimulacion());
        if (!problemas.isEmpty()) throw new IllegalArgumentException("La red no se puede simular: " + problemas.get(0));
        this.red = c.red();
        this.proy = c.proyeccion();
        return aplicar(null);
    }
}
