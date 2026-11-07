package smarttraffic.web;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import smarttraffic.datos.ImportadorOsm;
import smarttraffic.datos.ImportarCali;
import smarttraffic.datos.RedVialJson;

/**
 * Descarga el mapa de una zona de OpenStreetMap desde el propio servidor y lo deja como la red activa.
 * Corre en segundo plano (puede tardar más de un minuto) y expone su avance para que la pantalla lo muestre.
 * Datos © colaboradores de OpenStreetMap (ODbL).
 */
public final class ImportacionOsm {
    public enum Estado { OCIOSO, DESCARGANDO, PROCESANDO, LISTO, ERROR }

    /** Zona por defecto: el centro de Cali (≈ 2,2 km de lado), alrededor de la Plaza de Caycedo. */
    public static final double[] CENTRO_CALI = {3.4420, -76.5420, 3.4620, -76.5220};
    /** Límite de tamaño para no agotar la memoria del servidor (≈ 4,4 km de lado). */
    static final double LADO_MAXIMO_GRADOS = 0.04;

    private final ServicioRed servicioRed;
    private final List<String> servidores;
    private final int intentos;
    private final long esperaMs;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "importacion-osm");
        t.setDaemon(true);
        return t;
    });
    private volatile Estado estado = Estado.OCIOSO;
    private volatile String mensaje = "";
    private volatile Map<String, Object> resultado;
    private volatile long inicio;

    public ImportacionOsm(ServicioRed servicioRed, String overpass, int intentos, long esperaMs) {
        this.servicioRed = servicioRed;
        this.servidores = Arrays.stream(overpass.split(",")).map(String::trim).filter((x) -> !x.isEmpty()).toList();
        this.intentos = intentos;
        this.esperaMs = esperaMs;
    }

    static double[] validarZona(double[] z) {
        if (z.length != 4 || z[0] >= z[2] || z[1] >= z[3]) throw new IllegalArgumentException("La zona debe indicarse como sur, oeste, norte, este (sur < norte y oeste < este).");
        for (double v : z) if (!Double.isFinite(v)) throw new IllegalArgumentException("La zona tiene valores que no son números.");
        if (z[0] < -90 || z[2] > 90 || z[1] < -180 || z[3] > 180) throw new IllegalArgumentException("Las coordenadas de la zona están fuera de rango.");
        if (z[2] - z[0] > LADO_MAXIMO_GRADOS || z[3] - z[1] > LADO_MAXIMO_GRADOS) {
            throw new IllegalArgumentException("La zona es demasiado grande (máximo ≈ 4 km de lado): el servidor podría quedarse sin memoria. Elige una zona menor.");
        }
        return z;
    }

    /** Inicia la importación. Devuelve false si ya hay una en curso. */
    public synchronized boolean iniciar(double[] zona) {
        validarZona(zona);
        if (estado == Estado.DESCARGANDO || estado == Estado.PROCESANDO) return false;
        estado = Estado.DESCARGANDO;
        mensaje = "Descargando las calles de OpenStreetMap…";
        resultado = null;
        inicio = System.currentTimeMillis();
        hilo.submit(() -> ejecutar(zona));
        return true;
    }

    private void ejecutar(double[] z) {
        try {
            String calles = ImportarCali.descargar(servidores, ImportadorOsm.consultaCalles(z[0], z[1], z[2], z[3]), intentos, esperaMs);
            mensaje = "Descargando hospitales, bomberos y policía…";
            String servicios = ImportarCali.descargar(servidores, ImportadorOsm.consultaServicios(z[0], z[1], z[2], z[3]), intentos, esperaMs);
            estado = Estado.PROCESANDO;
            mensaje = "Convirtiendo el mapa en una red de calles…";
            ImportadorOsm.Resultado r = ImportadorOsm.importar(calles, servicios, new ImportadorOsm.Opciones());
            mensaje = "Guardando la red y reiniciando la simulación…";
            servicioRed.reemplazar(RedVialJson.escribir(r.red(), r.proyeccion()));
            Map<String, Object> res = resumen(r);
            resultado = res;
            mensaje = "Listo: " + r.red().intersecciones().size() + " intersecciones y " + r.red().vias().size() + " tramos de calle.";
            estado = Estado.LISTO;
        } catch (Exception e) {
            resultado = null;
            mensaje = e.getMessage() == null ? "No se pudo importar el mapa." : e.getMessage();
            estado = Estado.ERROR;
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Primer servidor de la lista (el que se usa para armar el enlace de descarga manual). */
    public String servidorPrincipal() {
        return servidores.get(0);
    }

    /**
     * Importa un mapa que la persona descargó con su navegador (la respuesta de la API Overpass) y subió a la aplicación.
     * Sirve cuando el servidor no tiene salida a internet. Los hospitales y estaciones no vienen en ese archivo.
     */
    public synchronized Map<String, Object> importarTexto(String calles) {
        if (estado == Estado.DESCARGANDO || estado == Estado.PROCESANDO) throw new IllegalStateException("Ya hay una importación en curso. Espera a que termine.");
        inicio = System.currentTimeMillis();
        estado = Estado.PROCESANDO;
        mensaje = "Convirtiendo el archivo en una red de calles…";
        try {
            ImportadorOsm.Resultado r = ImportadorOsm.importar(calles, null, new ImportadorOsm.Opciones());
            servicioRed.reemplazar(RedVialJson.escribir(r.red(), r.proyeccion()));
            resultado = resumen(r);
            mensaje = "Listo: " + r.red().intersecciones().size() + " intersecciones y " + r.red().vias().size() + " tramos de calle.";
            estado = Estado.LISTO;
            return resultado;
        } catch (RuntimeException e) {
            resultado = null;
            mensaje = e.getMessage() == null ? "No se pudo importar el archivo." : e.getMessage();
            estado = Estado.ERROR;
            throw e;
        }
    }

    private static Map<String, Object> resumen(ImportadorOsm.Resultado r) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("intersecciones", r.red().intersecciones().size());
        res.put("vias", r.red().vias().size());
        res.put("semaforos", r.semaforos());
        res.put("entradas", r.red().entradasSalidas().size());
        res.put("bases", r.red().bases().size());
        res.put("descartadas", r.descartadasPorConexion());
        return res;
    }

    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("estado", estado.name());
        m.put("mensaje", mensaje);
        m.put("segundos", estado == Estado.OCIOSO ? 0 : Math.round((System.currentTimeMillis() - inicio) / 1000.0));
        if (resultado != null) m.put("resultado", resultado);
        return m;
    }

    public void cerrar() {
        hilo.shutdownNow();
    }
}
