package smarttraffic.datos;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import smarttraffic.red.Interseccion;

/**
 * Descarga el mapa de una zona de Cali de OpenStreetMap (API Overpass), lo convierte en la red vial
 * de SmartTraffic y la guarda en un archivo JSON que el servidor carga al iniciar.
 *
 * <pre>
 * java -cp ... smarttraffic.datos.ImportarCali [--bbox sur,oeste,norte,este] [--salida archivo.json]
 *      [--calles overpass.json] [--servicios servicios.json] [--semaforos archivo.csv|geojson] [--overpass URL]
 * </pre>
 *
 * Datos © colaboradores de OpenStreetMap (ODbL). La API pública de Overpass es de uso compartido: no hagas
 * consultas repetidas; descarga una vez y reutiliza el archivo con {@code --calles}.
 */
public final class ImportarCali {
    private ImportarCali() {}

    /** Centro de Cali, aproximadamente (sur, oeste, norte, este). Es solo un valor por defecto: se puede cambiar con --bbox. */
    static final double[] BBOX_CENTRO = {3.4340, -76.5480, 3.4700, -76.5120};

    public static void main(String[] args) {
        System.exit(ejecutar(args, System.out));
    }

    public static int ejecutar(String[] args, PrintStream out) {
        try {
            double[] bbox = BBOX_CENTRO;
            String salida = "backend/data/red-cali.json", callesArchivo = null, serviciosArchivo = null, semaforosArchivo = null, overpass = "https://overpass-api.de/api/interpreter";
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (i + 1 >= args.length) throw new IllegalArgumentException("Falta el valor de " + a);
                String v = args[++i];
                switch (a) {
                    case "--bbox" -> bbox = leerBbox(v);
                    case "--salida" -> salida = v;
                    case "--calles" -> callesArchivo = v;
                    case "--servicios" -> serviciosArchivo = v;
                    case "--semaforos" -> semaforosArchivo = v;
                    case "--overpass" -> overpass = v;
                    default -> throw new IllegalArgumentException("Opción desconocida: " + a);
                }
            }
            String calles, servicios;
            if (callesArchivo != null) {
                calles = Files.readString(Path.of(callesArchivo));
            } else {
                out.println("Descargando las calles de OpenStreetMap (puede tardar un par de minutos)...");
                calles = descargar(overpass, ImportadorOsm.consultaCalles(bbox[0], bbox[1], bbox[2], bbox[3]), 3, 5000);
            }
            if (serviciosArchivo != null) {
                servicios = Files.readString(Path.of(serviciosArchivo));
            } else if (callesArchivo == null) {
                out.println("Descargando hospitales, bomberos y policía...");
                servicios = descargar(overpass, ImportadorOsm.consultaServicios(bbox[0], bbox[1], bbox[2], bbox[3]), 3, 5000);
            } else {
                servicios = null;
            }
            ImportadorOsm.Opciones opc = new ImportadorOsm.Opciones();
            ImportadorOsm.Resultado r = ImportadorOsm.importar(calles, servicios, opc);
            if (semaforosArchivo != null) {
                List<DatosAbiertos.Punto> puntos = DatosAbiertos.leerPuntos(Files.readString(Path.of(semaforosArchivo)));
                int n = ImportadorOsm.marcarSemaforos(r.red(), r.proyeccion(), puntos, 35);
                out.println("Semáforos de los datos abiertos asignados a intersecciones: " + n + " de " + puntos.size());
            }
            int semaforos = 0;
            for (Interseccion i : r.red().intersecciones()) if (i.semaforizada()) semaforos++;
            Path destino = Path.of(salida);
            if (destino.getParent() != null) Files.createDirectories(destino.getParent());
            Files.writeString(destino, RedVialJson.escribir(r.red(), r.proyeccion()), StandardCharsets.UTF_8);
            out.printf("Listo: %d intersecciones, %d vías, %d con semáforo, %d entradas/salidas, %d bases de emergencia.%n", r.red().intersecciones().size(), r.red().vias().size(), semaforos, r.red().entradasSalidas().size(), r.red().bases().size());
            out.println("Red guardada en " + destino + "  (© colaboradores de OpenStreetMap, ODbL)");
            return 0;
        } catch (IllegalArgumentException | IOException | InterruptedException e) {
            out.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static double[] leerBbox(String s) {
        String[] p = s.split(",");
        if (p.length != 4) throw new IllegalArgumentException("--bbox necesita cuatro números: sur,oeste,norte,este");
        double[] b = new double[4];
        for (int i = 0; i < 4; i++) {
            try {
                b[i] = Double.parseDouble(p[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("--bbox: «" + p[i] + "» no es un número");
            }
        }
        if (b[0] >= b[2] || b[1] >= b[3]) throw new IllegalArgumentException("--bbox debe ir sur,oeste,norte,este (sur < norte y oeste < este)");
        if ((b[2] - b[0]) > 0.2 || (b[3] - b[1]) > 0.2) throw new IllegalArgumentException("La zona es demasiado grande (más de ~20 km de lado); usa una zona menor para no sobrecargar el servicio público de mapas");
        return b;
    }

    /** Consulta la API Overpass (POST) en un solo servidor. */
    public static String descargar(String url, String consulta, int intentos, long esperaBaseMs) throws IOException, InterruptedException {
        return descargar(List.of(url), consulta, intentos, esperaBaseMs);
    }

    /**
     * Consulta la API Overpass probando cada servidor de la lista, en orden, hasta que uno responda.
     * En cada servidor reintenta {@code intentos} veces esperando {@code esperaBaseMs} × número de intento.
     * Si todos fallan, el error dice qué pasó en cada uno.
     */
    public static String descargar(List<String> urls, String consulta, int intentos, long esperaBaseMs) throws IOException, InterruptedException {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        List<String> fallos = new ArrayList<>();
        for (String url : urls) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(240))
                    .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "SmartTraffic/1.0 (proyecto academico de estructuras de datos)")
                    .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8))).build();
            String ultimo = "sin respuesta";
            for (int intento = 1; intento <= intentos; intento++) {
                try {
                    HttpResponse<String> r = cliente.send(req, HttpResponse.BodyHandlers.ofString());
                    if (r.statusCode() == 200) return r.body();
                    ultimo = "respondió " + r.statusCode() + (r.statusCode() == 429 || r.statusCode() == 504 ? " (está ocupado)" : "");
                } catch (IOException e) {
                    ultimo = refinar(e, url);
                    System.err.println("Descarga del mapa fallida en " + servidor(url) + ": " + e);
                }
                if (intento < intentos) Thread.sleep(esperaBaseMs * intento);
            }
            fallos.add(servidor(url) + " → " + ultimo);
        }
        throw new IOException("No se pudo descargar el mapa. " + String.join(" · ", fallos));
    }

    /**
     * Java suele informar «conexión fallida» sin decir por qué. Si no hay una causa clara, se comprueba directamente si el
     * nombre del servidor se puede resolver: es la causa más frecuente en un servidor sin salida a internet.
     */
    static String refinar(IOException e, String url) {
        String d = describir(e);
        if (e instanceof java.net.ConnectException) {
            try {
                java.net.InetAddress.getAllByName(servidor(url));
            } catch (java.net.UnknownHostException u) {
                return describir(u);
            }
        }
        return d;
    }

    static String servidor(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** Explica en español un error de conexión (los de Java muchas veces no traen texto). */
    public static String describir(IOException e) {
        if (e instanceof java.net.http.HttpConnectTimeoutException) return "no respondió a tiempo al conectar";
        if (e instanceof java.net.http.HttpTimeoutException) return "tardó demasiado en responder";
        // Java suele esconder la causa verdadera dentro del error: se revisa toda la cadena de causas
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.UnknownHostException) return "no se encontró la dirección en el DNS (el servidor no logra resolver nombres de internet)";
            if (t instanceof java.net.NoRouteToHostException) return "no hay ruta de red hasta el servidor (¿el servidor tiene salida a internet?)";
            if (t instanceof java.net.SocketTimeoutException) return "tardó demasiado en responder";
        }
        if (e instanceof java.net.ConnectException) return "la conexión fue rechazada o bloqueada (¿el servidor tiene salida a internet?)";
        if (e instanceof javax.net.ssl.SSLException) return "falló la conexión segura (" + (e.getMessage() == null ? "certificado" : e.getMessage()) + ")";
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** Dirección que, abierta en el navegador, descarga el mismo archivo que baja el servidor. */
    public static String enlaceDescarga(String overpass, double[] z) {
        return overpass + "?data=" + URLEncoder.encode(ImportadorOsm.consultaCalles(z[0], z[1], z[2], z[3]), StandardCharsets.UTF_8);
    }
}
