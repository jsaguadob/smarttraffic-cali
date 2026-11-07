package smarttraffic.datos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.util.Json;

/** Formato JSON propio de SmartTraffic para guardar y enviar a la pantalla una red vial completa. */
public final class RedVialJson {
    private RedVialJson() {}

    public record Cargada(RedVial red, ProyeccionLocal proyeccion) {}

    public static String escribir(RedVial red, ProyeccionLocal proy) {
        List<Object> nodos = new ArrayList<>();
        for (Interseccion i : red.intersecciones()) {
            nodos.add(Json.obj("id", i.id(), "nombre", i.nombre(), "x", i.x(), "y", i.y(), "lat", Double.isNaN(i.latitud()) ? null : i.latitud(), "lon", Double.isNaN(i.longitud()) ? null : i.longitud(),
                    "tipo", i.tipo().name(), "semaforizada", i.semaforizada(), "servicios", new ArrayList<>(i.servicios())));
        }
        List<Object> vias = new ArrayList<>();
        for (Via v : red.vias()) {
            vias.add(Json.obj("id", v.id(), "desde", v.desde(), "hacia", v.hacia(), "nombre", v.nombre(), "longitud", v.longitud(), "velocidad", v.velocidadMaxKmh(),
                    "clase", v.clase(), "manual", v.longitudManual(), "puntos", v.puntos()));
        }
        return Json.escribir(Json.obj("nombre", red.nombre(), "origen", proy == null ? null : Json.obj("lat", proy.lat0(), "lon", proy.lon0()), "intersecciones", nodos, "vias", vias));
    }

    @SuppressWarnings("unchecked")
    public static Cargada leer(String json) {
        Map<String, Object> raiz = Json.leerObjeto(json);
        RedVial red = new RedVial(Json.texto(raiz, "nombre", "Red vial"));
        ProyeccionLocal proy = null;
        if (raiz.get("origen") instanceof Map<?, ?> o) proy = new ProyeccionLocal(Json.numero((Map<String, Object>) o, "lat", 0), Json.numero((Map<String, Object>) o, "lon", 0));
        for (Object o : (List<Object>) raiz.getOrDefault("intersecciones", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            Interseccion i = new Interseccion(Json.texto(m, "id", ""), Json.texto(m, "nombre", ""), Json.numero(m, "x", 0), Json.numero(m, "y", 0), TipoNodo.valueOf(Json.texto(m, "tipo", "INTERIOR")));
            if (m.get("lat") instanceof Number la && m.get("lon") instanceof Number lo) i.setGeo(la.doubleValue(), lo.doubleValue());
            i.setSemaforizada(Json.booleano(m, "semaforizada", true));
            for (Object s : (List<Object>) m.getOrDefault("servicios", List.of())) i.servicios().add(String.valueOf(s));
            red.agregarInterseccion(i);
        }
        for (Object o : (List<Object>) raiz.getOrDefault("vias", List.of())) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<Object> pts = (List<Object>) m.get("puntos");
            double[] puntos = new double[pts.size()];
            for (int k = 0; k < puntos.length; k++) puntos[k] = ((Number) pts.get(k)).doubleValue();
            boolean manual = Json.booleano(m, "manual", false);
            Via v = red.agregarVia(Json.texto(m, "id", ""), Json.texto(m, "desde", ""), Json.texto(m, "hacia", ""), Json.texto(m, "nombre", ""),
                    manual ? Json.numero(m, "longitud", 1) : null, Json.numero(m, "velocidad", 40), puntos);
            v.setClase(Json.texto(m, "clase", ""));
        }
        return new Cargada(red, proy);
    }
}
