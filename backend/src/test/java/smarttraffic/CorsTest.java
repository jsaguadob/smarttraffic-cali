package smarttraffic;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import smarttraffic.datos.RedVialJson;
import smarttraffic.datos.Repositorio;
import smarttraffic.red.RedCali;
import smarttraffic.web.GestorSimulacion;
import smarttraffic.web.Servidor;
import smarttraffic.web.ServicioRed;

/** La pantalla puede estar en otro sitio (Netlify) que el servidor: solo se aceptan los orígenes autorizados. */
class CorsTest {
    private static HttpResponse<String> pedir(int puerto, String metodo, String ruta, String origen) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + puerto + ruta)).method(metodo, HttpRequest.BodyPublishers.noBody());
        if (origen != null) b.header("Origin", origen);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test @DisplayName("CORS: orígenes exactos y con comodín (ramas de Netlify) pasan; los demás y el comodín total, no")
    void cors() throws Exception {
        System.setProperty("smarttraffic.pbkdf2", "1000");
        Repositorio repo = new Repositorio("jdbc:h2:mem:cors" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE", "sa", "");
        var red = RedCali.crear();
        GestorSimulacion gestor = new GestorSimulacion(RedVialJson.leer(RedVialJson.escribir(red, null)).red());
        Servidor.Opciones o = new Servidor.Opciones();
        o.puerto = 0;
        o.origenCors = "https://smarttraffic.netlify.app, https://*--smarttraffic.netlify.app, *";
        Servidor s = new Servidor(o, repo, gestor, new ServicioRed(repo, gestor, red, null));
        s.iniciar();
        try {
            int p = s.puerto();
            HttpResponse<String> ok = pedir(p, "GET", "/api/salud", "https://smarttraffic.netlify.app");
            assertEquals("https://smarttraffic.netlify.app", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
            assertEquals("Origin", ok.headers().firstValue("Vary").orElse(null));
            assertEquals("https://desarrollo--smarttraffic.netlify.app", pedir(p, "GET", "/api/salud", "https://desarrollo--smarttraffic.netlify.app").headers().firstValue("Access-Control-Allow-Origin").orElse(null), "la rama de desarrollo");
            assertEquals("https://deploy-preview-7--smarttraffic.netlify.app", pedir(p, "GET", "/api/salud", "https://deploy-preview-7--smarttraffic.netlify.app").headers().firstValue("Access-Control-Allow-Origin").orElse(null), "las vistas previas");
            for (String malo : new String[] {"https://malvado.example.com", "https://smarttraffic.netlify.app.malvado.com", "http://smarttraffic.netlify.app", "https://x--smarttraffic.netlify.app.evil.io", "null"}) {
                assertTrue(pedir(p, "GET", "/api/salud", malo).headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "no debe autorizar " + malo);
            }
            assertTrue(pedir(p, "GET", "/api/salud", null).headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "sin origen (mismo sitio) no hace falta");
            HttpResponse<String> pre = pedir(p, "OPTIONS", "/api/red", "https://smarttraffic.netlify.app");
            assertEquals(204, pre.statusCode());
            assertTrue(pre.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Authorization"));
            assertTrue(pedir(p, "OPTIONS", "/api/red", "https://malvado.example.com").headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        } finally {
            s.close();
            gestor.close();
        }
    }
}
