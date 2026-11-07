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
                calles = descargar(overpass, ImportadorOsm.consultaCalles(bbox[0], bbox[1], bbox[2], bbox[3]));
            }
            if (serviciosArchivo != null) {
                servicios = Files.readString(Path.of(serviciosArchivo));
            } else if (callesArchivo == null) {
                out.println("Descargando hospitales, bomberos y policía...");
                servicios = descargar(overpass, ImportadorOsm.consultaServicios(bbox[0], bbox[1], bbox[2], bbox[3]));
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

    private static String descargar(String url, String consulta) throws IOException, InterruptedException {
        HttpClient cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(240))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "SmartTraffic/1.0 (proyecto academico de estructuras de datos)")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(consulta, StandardCharsets.UTF_8))).build();
        IOException ultimo = null;
        for (int intento = 1; intento <= 3; intento++) {
            try {
                HttpResponse<String> r = cliente.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return r.body();
                ultimo = new IOException("El servicio de mapas respondió " + r.statusCode() + (r.statusCode() == 429 || r.statusCode() == 504 ? " (está ocupado; espera unos minutos)" : ""));
            } catch (IOException e) {
                ultimo = new IOException("No se pudo conectar con el servicio de mapas (" + e.getMessage() + "). Revisa tu conexión a internet.");
            }
            Thread.sleep(5000L * intento);
        }
        throw ultimo;
    }
}
