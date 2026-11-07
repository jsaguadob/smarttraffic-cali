package smarttraffic.web;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import smarttraffic.datos.ImportarCali;

/** Comprueba, desde el propio servidor, si puede llegar a los servicios de OpenStreetMap (útil cuando una importación falla). */
public final class DiagnosticoRed {
    private DiagnosticoRed() {}

    public record Prueba(String nombre, String url) {}

    public static List<Map<String, Object>> probar(List<Prueba> pruebas) {
        ExecutorService hilos = Executors.newFixedThreadPool(Math.max(1, Math.min(8, pruebas.size())));
        try {
            List<CompletableFuture<Map<String, Object>>> futuros = new ArrayList<>();
            for (Prueba p : pruebas) futuros.add(CompletableFuture.supplyAsync(() -> una(p), hilos));
            List<Map<String, Object>> salida = new ArrayList<>();
            for (int i = 0; i < futuros.size(); i++) {
                try {
                    salida.add(futuros.get(i).get(15, TimeUnit.SECONDS));
                } catch (Exception e) {
                    salida.add(resultado(pruebas.get(i), false, 0, 0, List.of(), "no terminó a tiempo"));
                }
            }
            return salida;
        } finally {
            hilos.shutdownNow();
        }
    }

    private static Map<String, Object> una(Prueba p) {
        long t0 = System.currentTimeMillis();
        List<String> direcciones = new ArrayList<>();
        try {
            URI uri = URI.create(p.url());
            for (InetAddress a : InetAddress.getAllByName(uri.getHost())) direcciones.add(a.getHostAddress());
            HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("User-Agent", Geocodificador.USER_AGENT).GET().build();
            HttpResponse<Void> r = cliente.send(req, HttpResponse.BodyHandlers.discarding());
            boolean ok = r.statusCode() < 400;
            return resultado(p, ok, r.statusCode(), System.currentTimeMillis() - t0, direcciones, ok ? null : "respondió " + r.statusCode());
        } catch (java.io.IOException e) {
            return resultado(p, false, 0, System.currentTimeMillis() - t0, direcciones, ImportarCali.describir(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return resultado(p, false, 0, System.currentTimeMillis() - t0, direcciones, "se interrumpió");
        } catch (RuntimeException e) {
            return resultado(p, false, 0, System.currentTimeMillis() - t0, direcciones, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Map<String, Object> resultado(Prueba p, boolean ok, int http, long ms, List<String> direcciones, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nombre", p.nombre());
        m.put("url", p.url());
        m.put("ok", ok);
        m.put("http", http);
        m.put("ms", ms);
        m.put("direcciones", direcciones);
        if (error != null) m.put("error", error);
        return m;
    }

    /** Interpretación en español para quien administra el servidor. */
    public static String resumen(List<Map<String, Object>> r) {
        long ok = r.stream().filter(x -> Boolean.TRUE.equals(x.get("ok"))).count();
        if (ok == r.size()) return "El servidor llega a todos los servicios. Si una importación falla, suele ser porque el servicio público estaba ocupado: reintenta en unos minutos.";
        if (ok == 0) return "El servidor no logra llegar a ningún servicio de internet. Puede que el sitio donde está alojado bloquee las conexiones salientes. Usa la alternativa: descarga el archivo del mapa con tu navegador y súbelo.";
        return "El servidor llega a algunos servicios y a otros no. Mira cuáles fallan abajo; la importación prueba los servidores de calles en orden.";
    }
}
