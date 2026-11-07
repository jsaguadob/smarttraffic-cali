package smarttraffic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import smarttraffic.datos.ConexionBd;
import smarttraffic.datos.ProyeccionLocal;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.red.RedCali;
import smarttraffic.red.RedVial;
import smarttraffic.web.GestorSimulacion;
import smarttraffic.web.Seguridad;
import smarttraffic.web.Servidor;
import smarttraffic.web.ServicioRed;

/**
 * Punto de entrada. Configuración por variables de entorno:
 * SMARTTRAFFIC_PORT, SMARTTRAFFIC_DB_URL / _DB_USER / _DB_PASSWORD, SMARTTRAFFIC_JWT_SECRET, SMARTTRAFFIC_STATIC,
 * SMARTTRAFFIC_CORS, SMARTTRAFFIC_RED (archivo JSON con la red de Cali importada), SMARTTRAFFIC_DEMO=1,
 * SMARTTRAFFIC_ADMIN_CORREO y SMARTTRAFFIC_ADMIN_CLAVE.
 */
public final class Main {
    private Main() {}

    private static String env(String clave, String defecto) {
        String v = System.getenv(clave);
        return v == null || v.isBlank() ? defecto : v;
    }

    public static void main(String[] args) throws Exception {
        // La dirección puede ser de JDBC o la «línea» que entrega Neon, Render o Railway (postgresql://usuario:clave@host/base).
        String direccion = env("SMARTTRAFFIC_DB_URL", env("DATABASE_URL", "jdbc:h2:file:./data/smarttraffic;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        ConexionBd.Datos bd = ConexionBd.interpretar(direccion, System.getenv("SMARTTRAFFIC_DB_USER"), System.getenv("SMARTTRAFFIC_DB_PASSWORD"));
        boolean h2 = bd.jdbcUrl().startsWith("jdbc:h2:");
        System.out.println("Conectando a la base de datos: " + bd.descripcion()); // sin usuario ni contraseña
        Repositorio repo = new Repositorio(bd.jdbcUrl(), bd.usuario() == null && h2 ? "sa" : bd.usuario(), bd.clave() == null && h2 ? "" : bd.clave());
        crearUsuariosIniciales(repo);

        Optional<RedVialJson.Cargada> activa = repo.cargarRedActiva();
        RedVial red;
        ProyeccionLocal proy = null;
        if (activa.isPresent()) {
            red = activa.get().red();
            proy = activa.get().proyeccion();
        } else if (System.getenv("SMARTTRAFFIC_RED") != null && Files.exists(Path.of(System.getenv("SMARTTRAFFIC_RED")))) {
            RedVialJson.Cargada c = RedVialJson.leer(Files.readString(Path.of(System.getenv("SMARTTRAFFIC_RED"))));
            red = c.red();
            proy = c.proyeccion();
            repo.guardarRedActiva(red, proy);
            System.out.println("Red importada de " + System.getenv("SMARTTRAFFIC_RED") + ": " + red.intersecciones().size() + " intersecciones, " + red.vias().size() + " vías.");
        } else {
            red = RedCali.crear();
            repo.guardarRedActiva(red, null);
            System.out.println("Usando la red de demostración (ficticia). Para el mapa real de Cali ejecuta ./importar-cali.sh");
        }

        Servidor.Opciones opc = new Servidor.Opciones();
        opc.puerto = Integer.parseInt(env("SMARTTRAFFIC_PORT", env("PORT", "8080")));
        opc.dirEstatico = env("SMARTTRAFFIC_STATIC", "../frontend/dist");
        opc.origenCors = System.getenv("SMARTTRAFFIC_CORS");
        opc.demo = "1".equals(System.getenv("SMARTTRAFFIC_DEMO"));
        if (System.getenv("SMARTTRAFFIC_OVERPASS") != null) opc.overpassUrl = System.getenv("SMARTTRAFFIC_OVERPASS");
        String secreto = System.getenv("SMARTTRAFFIC_JWT_SECRET");
        if (secreto != null && secreto.length() >= 32) opc.secretoJwt = secreto;
        else System.out.println("Aviso: SMARTTRAFFIC_JWT_SECRET no está definido (o es corto): las sesiones se cerrarán al reiniciar el servidor.");

        GestorSimulacion gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, proy)).red());
        ServicioRed servicioRed = new ServicioRed(repo, gestor, red, proy);
        Servidor servidor = new Servidor(opc, repo, gestor, servicioRed);
        servidor.iniciar();
        System.out.println("SmartTraffic " + Servidor.VERSION + " en http://localhost:" + servidor.puerto());
    }

    private static void crearUsuariosIniciales(Repositorio repo) {
        if (!repo.usuarios().isEmpty()) return;
        if ("1".equals(System.getenv("SMARTTRAFFIC_DEMO"))) {
            String clave = "SmartTraffic2026";
            repo.crearUsuario("Administración", "admin@smarttraffic.test", Seguridad.hashClave(clave), "ADMINISTRADOR");
            repo.crearUsuario("Operador de movilidad", "operador@smarttraffic.test", Seguridad.hashClave(clave), "OPERADOR");
            repo.crearUsuario("Analista", "analista@smarttraffic.test", Seguridad.hashClave(clave), "ANALISTA");
            System.out.println("MODO DEMO: usuarios admin@, operador@ y analista@smarttraffic.test con contraseña " + clave + " (no usar en producción).");
            return;
        }
        String correo = env("SMARTTRAFFIC_ADMIN_CORREO", "admin@smarttraffic.local");
        String clave = System.getenv("SMARTTRAFFIC_ADMIN_CLAVE");
        boolean generada = clave == null;
        if (generada) clave = Seguridad.secretoAleatorio().substring(0, 16) + "a1";
        Seguridad.validarClave(clave);
        repo.crearUsuario("Administración", correo, Seguridad.hashClave(clave), "ADMINISTRADOR");
        System.out.println("Administrador creado: " + correo + (generada ? " con la contraseña generada: " + clave + "  (anótala: no se vuelve a mostrar)" : ""));
    }
}
