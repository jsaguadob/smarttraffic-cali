package smarttraffic.datos;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import smarttraffic.util.Json;

/**
 * Lectura de conjuntos de datos abiertos con ubicación (por ejemplo los del portal datos.cali.gov.co):
 * archivos GeoJSON con puntos o tablas CSV con columnas de latitud y longitud.
 *
 * <p>Los nombres de columna de cada conjunto pueden variar; aquí se reconocen los habituales
 * (latitud, lat, longitud, lon, lng, x, y...). Si un conjunto usa otros nombres hay que renombrar
 * la columna en el archivo antes de importarlo.
 */
public final class DatosAbiertos {
    private DatosAbiertos() {}

    public record Punto(double lat, double lon, Map<String, String> propiedades) {}

    /** Accidente de tránsito ubicado en el mapa. Gravedad: 1 solo daños, 2 heridos, 3 muertos. */
    public record Accidente(double lat, double lon, int anio, int gravedad) {}

    private static final Set<String> NOMBRES_LAT = Set.of("latitud", "lat", "latitude", "coordenadalatitud", "y");
    private static final Set<String> NOMBRES_LON = Set.of("longitud", "lon", "lng", "long", "longitude", "coordenadalongitud", "x");

    public static List<Punto> leerPuntos(String contenido) {
        String c = contenido.stripLeading();
        if (c.startsWith("\uFEFF")) c = c.substring(1).stripLeading();
        return c.startsWith("{") ? leerGeoJson(c) : leerCsv(c);
    }

    @SuppressWarnings("unchecked")
    private static List<Punto> leerGeoJson(String texto) {
        List<Punto> salida = new ArrayList<>();
        Map<String, Object> raiz = Json.leerObjeto(texto);
        List<Object> features = "FeatureCollection".equals(raiz.get("type")) ? (List<Object>) raiz.getOrDefault("features", List.of()) : List.of(raiz);
        for (Object f : features) {
            Map<String, Object> feat = (Map<String, Object>) f;
            Object g = feat.get("geometry");
            if (!(g instanceof Map<?, ?> geo) || !"Point".equals(geo.get("type"))) continue;
            List<Object> xy = (List<Object>) geo.get("coordinates");
            if (xy == null || xy.size() < 2 || !(xy.get(0) instanceof Number) || !(xy.get(1) instanceof Number)) continue;
            double lon = ((Number) xy.get(0)).doubleValue(), lat = ((Number) xy.get(1)).doubleValue();
            if (!coordenadaValida(lat, lon)) continue;
            Map<String, String> props = new LinkedHashMap<>();
            Object p = feat.get("properties");
            if (p instanceof Map<?, ?> m) for (Map.Entry<?, ?> e : m.entrySet()) props.put(normalizar(String.valueOf(e.getKey())), e.getValue() == null ? "" : String.valueOf(e.getValue()));
            salida.add(new Punto(lat, lon, props));
        }
        return salida;
    }

    private static List<Punto> leerCsv(String texto) {
        List<String[]> filas = new ArrayList<>();
        String[] lineas = texto.split("\\r?\\n");
        if (lineas.length < 2) return List.of();
        char sep = detectarSeparador(lineas[0]);
        for (String l : lineas) if (!l.isBlank()) filas.add(dividir(l, sep));
        String[] cabecera = filas.get(0);
        int iLat = -1, iLon = -1;
        String[] nombres = new String[cabecera.length];
        for (int i = 0; i < cabecera.length; i++) {
            nombres[i] = normalizar(cabecera[i]);
            if (iLat < 0 && NOMBRES_LAT.contains(nombres[i])) iLat = i;
            if (iLon < 0 && NOMBRES_LON.contains(nombres[i])) iLon = i;
        }
        if (iLat < 0 || iLon < 0) throw new IllegalArgumentException("No se encontraron las columnas de latitud y longitud. Columnas: " + String.join(", ", cabecera));
        List<Punto> salida = new ArrayList<>();
        for (int r = 1; r < filas.size(); r++) {
            String[] f = filas.get(r);
            if (f.length <= Math.max(iLat, iLon)) continue;
            double lat, lon;
            try {
                lat = Double.parseDouble(f[iLat].trim().replace(',', '.'));
                lon = Double.parseDouble(f[iLon].trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!coordenadaValida(lat, lon)) continue;
            Map<String, String> props = new LinkedHashMap<>();
            for (int i = 0; i < Math.min(f.length, nombres.length); i++) props.put(nombres[i], f[i].trim());
            salida.add(new Punto(lat, lon, props));
        }
        return salida;
    }

    private static boolean coordenadaValida(double lat, double lon) {
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180 && !(lat == 0 && lon == 0);
    }

    private static char detectarSeparador(String cabecera) {
        int comas = 0, puntoycoma = 0, tabs = 0;
        for (char c : cabecera.toCharArray()) { if (c == ',') comas++; else if (c == ';') puntoycoma++; else if (c == '\t') tabs++; }
        return tabs > comas && tabs > puntoycoma ? '\t' : puntoycoma > comas ? ';' : ',';
    }

    private static String[] dividir(String linea, char sep) {
        List<String> campos = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char c = linea.charAt(i);
            if (c == '"') {
                if (comillas && i + 1 < linea.length() && linea.charAt(i + 1) == '"') { sb.append('"'); i++; } else comillas = !comillas;
            } else if (c == sep && !comillas) {
                campos.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        campos.add(sb.toString());
        return campos.toArray(new String[0]);
    }

    /** Minúsculas, sin tildes ni símbolos: «Año del Hecho» → «aniodelhecho». */
    static String normalizar(String s) {
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        return n.replaceAll("[^a-z0-9]", "");
    }

    /** Convierte puntos con propiedades en accidentes, deduciendo año y gravedad de las columnas habituales. */
    public static List<Accidente> aAccidentes(List<Punto> puntos) {
        List<Accidente> salida = new ArrayList<>();
        for (Punto p : puntos) {
            int anio = 0, gravedad = 1;
            for (Map.Entry<String, String> e : p.propiedades().entrySet()) {
                String k = e.getKey(), v = e.getValue().toLowerCase();
                if (anio == 0 && (k.contains("anio") || k.equals("ano") || k.contains("year") || k.contains("fecha"))) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(19|20)\\d{2}").matcher(v);
                    if (m.find()) anio = Integer.parseInt(m.group());
                }
                if (k.contains("gravedad") || k.contains("severidad") || k.contains("clase") || k.contains("tipo") || k.contains("resultado")) {
                    if (v.contains("muert") || v.contains("fatal") || v.contains("mortal") || v.contains("fallec")) gravedad = Math.max(gravedad, 3);
                    else if (v.contains("herid") || v.contains("lesion")) gravedad = Math.max(gravedad, 2);
                }
            }
            salida.add(new Accidente(p.lat(), p.lon(), anio, gravedad));
        }
        return salida;
    }
}
