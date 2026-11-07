package smarttraffic.datos;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Interpreta la dirección de la base de datos. Acepta el formato de JDBC y también el que entregan servicios
 * como Neon, Render o Railway ({@code postgresql://usuario:clave@host/base?sslmode=require}), y lo convierte
 * a lo que necesita el controlador de Java. Nunca escribe la contraseña en mensajes ni en el registro.
 */
public final class ConexionBd {
    private ConexionBd() {}

    public record Datos(String jdbcUrl, String usuario, String clave) {
        /** Descripción segura para el registro: sin usuario ni contraseña. */
        public String descripcion() {
            String sinParametros = jdbcUrl.contains("?") ? jdbcUrl.substring(0, jdbcUrl.indexOf('?')) : jdbcUrl;
            return sinParametros.replaceAll("//[^/@]*@", "//");
        }
    }

    private static final Set<String> MODOS_SSL = Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");

    /**
     * @param usuario usuario indicado aparte (tiene prioridad sobre el de la dirección); puede ser null
     * @param clave   contraseña indicada aparte (tiene prioridad sobre la de la dirección); puede ser null
     */
    public static Datos interpretar(String url, String usuario, String clave) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("Falta la dirección de la base de datos.");
        String u = url.trim();
        if (u.startsWith("jdbc:")) return new Datos(u, usuario, clave);
        if (!u.startsWith("postgres://") && !u.startsWith("postgresql://")) {
            throw new IllegalArgumentException("La dirección de la base de datos debe empezar por jdbc:, postgresql:// o postgres://");
        }
        URI uri;
        try {
            uri = new URI(u.replaceFirst("^postgres://", "postgresql://"));
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("La dirección de la base de datos no es válida. Si la contraseña tiene caracteres especiales (@ / : ?) deben ir codificados con %.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("La dirección de la base de datos no indica el servidor.");
        int puerto = uri.getPort() > 0 ? uri.getPort() : 5432;
        String bd = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (bd.isEmpty()) throw new IllegalArgumentException("La dirección de la base de datos no indica el nombre de la base.");

        String user = usuario, pass = clave;
        String info = uri.getRawUserInfo();
        if (info != null) {
            int i = info.indexOf(':');
            String uDir = decodificar(i < 0 ? info : info.substring(0, i)), pDir = i < 0 ? null : decodificar(info.substring(i + 1));
            if (user == null || user.isBlank()) user = uDir;
            if (pass == null || pass.isBlank()) pass = pDir;
        }

        // De los parámetros solo se conserva sslmode: el controlador JDBC rechaza o ignora los demás (p. ej. channel_binding).
        String ssl = null;
        if (uri.getRawQuery() != null) {
            for (String par : uri.getRawQuery().split("&")) {
                int i = par.indexOf('=');
                if (i > 0 && par.substring(0, i).equals("sslmode")) {
                    String v = par.substring(i + 1);
                    if (!MODOS_SSL.contains(v)) throw new IllegalArgumentException("sslmode «" + v + "» no es válido.");
                    ssl = v;
                }
            }
        }
        boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
        if (ssl == null && !local) ssl = "require"; // fuera de tu computador, siempre cifrado
        StringBuilder j = new StringBuilder("jdbc:postgresql://").append(host).append(':').append(puerto).append('/').append(bd).append("?connectTimeout=10");
        if (ssl != null) j.append("&sslmode=").append(ssl);
        return new Datos(j.toString(), user, pass);
    }

    private static String decodificar(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); // el «+» de una contraseña es un «+», no un espacio
    }
}
