package smarttraffic.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
