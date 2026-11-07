package smarttraffic.web;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import smarttraffic.datos.ProyeccionLocal;
import smarttraffic.util.Json;

/**
 * Busca lugares por nombre (un restaurante, un barrio, una dirección) en OpenStreetMap con Nominatim.
 *
 * <p>Cumple la política de uso del servicio público: se identifica con un User-Agent, hace como máximo una consulta por
 * segundo en todo el servidor, guarda las respuestas en caché y solo busca cuando el usuario lo pide (no mientras escribe).
 * Datos © colaboradores de OpenStreetMap (ODbL).
 */
public final class Geocodificador {
    static final String USER_AGENT = "SmartTraffic/1.0 (proyecto academico de estructuras de datos; https://github.com/jsaguadob/smarttraffic-cali)";
    private static final long INTERVALO_MS = 1100;
    private static final long VIGENCIA_CACHE_MS = 10 * 60 * 1000;

    private record Guardado(long momento, List<Map<String, Object>> resultados) {}

    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private long ultimaConsulta;
    private final Map<String, Guardado> cache = new LinkedHashMap<>(64, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Guardado> e) {
            return size() > 200;
        }
    };

    public Geocodificador(String url) {
        this.url = url;
    }

    /**
     * @param caja límites de la red en metros locales {minX, minY, maxX, maxY}; solo se devuelven lugares dentro (con un margen)
     */
    public List<Map<String, Object>> buscar(String consulta, ProyeccionLocal proy, double[] caja) throws IOException, InterruptedException {
        double margen = 250;
        var nw = new double[] {proy.lat(caja[1] - margen), proy.lon(caja[0] - margen)}; // norte, oeste
        var se = new double[] {proy.lat(caja[3] + margen), proy.lon(caja[2] + margen)}; // sur, este
        String clave = consulta.trim().toLowerCase() + "|" + Math.round(nw[0] * 1e4) + "," + Math.round(nw[1] * 1e4) + "," + Math.round(se[0] * 1e4) + "," + Math.round(se[1] * 1e4);
        synchronized (this) {
            Guardado g = cache.get(clave);
            if (g != null && System.currentTimeMillis() - g.momento() < VIGENCIA_CACHE_MS) return g.resultados();
            long espera = ultimaConsulta + INTERVALO_MS - System.currentTimeMillis();
            if (espera > 0) Thread.sleep(espera); // política de Nominatim: máximo una consulta por segundo
            ultimaConsulta = System.currentTimeMillis();
        }
        String direccion = url + "?q=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8) + "&format=jsonv2&limit=8&accept-language=es&countrycodes=co&bounded=1"
                + "&viewbox=" + nw[1] + "," + nw[0] + "," + se[1] + "," + se[0];
        HttpRequest req = HttpRequest.newBuilder(URI.create(direccion)).timeout(Duration.ofSeconds(10)).header("User-Agent", USER_AGENT).header("Accept-Language", "es").GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("El buscador de lugares respondió " + r.statusCode() + (r.statusCode() == 429 ? " (demasiadas consultas; espera un momento)" : ""));
        List<Map<String, Object>> salida = new ArrayList<>();
        Object cuerpo = Json.leer(r.body());
        if (cuerpo instanceof List<?> lista) {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> m)) continue;
                try {
                    double lat = Double.parseDouble(String.valueOf(m.get("lat"))), lon = Double.parseDouble(String.valueOf(m.get("lon")));
                    double x = proy.x(lon), y = proy.y(lat);
                    if (x < caja[0] - margen || x > caja[2] + margen || y < caja[1] - margen || y > caja[3] + margen) continue; // fuera del mapa cargado
                    String completo = String.valueOf(m.get("display_name"));
                    int coma = completo.indexOf(',');
                    String nombre = m.get("name") instanceof String s && !s.isBlank() ? s : coma > 0 ? completo.substring(0, coma) : completo;
                    String detalle = coma > 0 ? completo.substring(coma + 1).trim() : "";
                    salida.add(Json.obj("tipo", "lugar", "nombre", nombre, "detalle", detalle.length() > 90 ? detalle.substring(0, 90) + "…" : detalle, "x", x, "y", y, "lat", lat, "lon", lon));
                } catch (NumberFormatException ignorar) {
                    // resultado sin coordenadas válidas
                }
            }
        }
        synchronized (this) {
            cache.put(clave, new Guardado(System.currentTimeMillis(), salida));
        }
        return salida;
    }
}
