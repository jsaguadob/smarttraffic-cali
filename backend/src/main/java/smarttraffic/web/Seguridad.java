package smarttraffic.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import smarttraffic.util.Json;

/** Contraseñas (PBKDF2-HMAC-SHA256 con sal), sesiones (JWT HS256) y límite de intentos de ingreso. Solo usa el JDK. */
public final class Seguridad {
    private Seguridad() {}

    public enum Rol { ADMINISTRADOR, OPERADOR, ANALISTA }

    private static final SecureRandom ALEATORIO = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();

    // ------------------------------------------------------------ contraseñas

    /**
     * Iteraciones de PBKDF2-HMAC-SHA512. OWASP recomienda 210.000 con SHA-512 (equivale a 600.000 con SHA-256) y calcula
     * unas 2,5 veces más rápido en el servidor. Las pruebas la bajan con -Dsmarttraffic.pbkdf2.
     */
    static int iteraciones() {
        return Integer.getInteger("smarttraffic.pbkdf2", 210_000);
    }

    public static void validarClave(String clave) {
        if (clave == null || clave.length() < 10 || !clave.matches(".*[A-Za-z].*") || !clave.matches(".*[0-9].*")) {
            throw new IllegalArgumentException("La contraseña debe tener al menos 10 caracteres, con letras y números.");
        }
    }

    public static String hashClave(String clave) {
        byte[] sal = new byte[16];
        ALEATORIO.nextBytes(sal);
        int it = iteraciones();
        return "pbkdf2s512$" + it + "$" + B64.encodeToString(sal) + "$" + B64.encodeToString(pbkdf2(clave, sal, it, "PBKDF2WithHmacSHA512"));
    }

    /** ¿Está guardada con el formato antiguo (SHA-256) o con menos iteraciones que las actuales? Si sí, se vuelve a guardar al ingresar. */
    public static boolean necesitaActualizar(String guardado) {
        try {
            String[] p = guardado.split("\\$");
            return !p[0].equals("pbkdf2s512") || Integer.parseInt(p[1]) < iteraciones();
        } catch (RuntimeException e) {
            return true;
        }
    }

    public static boolean verificarClave(String clave, String guardado) {
        try {
            String[] p = guardado.split("\\$");
            if (p.length != 4 || !(p[0].equals("pbkdf2") || p[0].equals("pbkdf2s512"))) return false;
            byte[] esperado = D64.decode(p[3]);
            String alg = p[0].equals("pbkdf2s512") ? "PBKDF2WithHmacSHA512" : "PBKDF2WithHmacSHA256"; // las contraseñas guardadas antes siguen valiendo
            return MessageDigest.isEqual(esperado, pbkdf2(clave, D64.decode(p[2]), Integer.parseInt(p[1]), alg));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String clave, byte[] sal, int iteraciones, String algoritmo) {
        try {
            return SecretKeyFactory.getInstance(algoritmo).generateSecret(new PBEKeySpec(clave.toCharArray(), sal, iteraciones, 256)).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ sesiones (JWT)

    public static String crearToken(String secreto, long usuarioId, String nombre, Rol rol, long duracionSegundos) {
        Map<String, Object> carga = new LinkedHashMap<>();
        carga.put("sub", usuarioId);
        carga.put("nom", nombre);
        carga.put("rol", rol.name());
        carga.put("exp", System.currentTimeMillis() / 1000 + duracionSegundos);
        String cabecera = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String cuerpo = B64.encodeToString(Json.escribir(carga).getBytes(StandardCharsets.UTF_8));
        return cabecera + "." + cuerpo + "." + B64.encodeToString(firmar(secreto, cabecera + "." + cuerpo));
    }

    /** Devuelve las reclamaciones del token, o null si la firma, el algoritmo o la vigencia no son válidos. */
    public static Map<String, Object> verificarToken(String secreto, String token) {
        try {
            if (token == null) return null;
            String[] p = token.split("\\.");
            if (p.length != 3) return null;
            Map<String, Object> cab = Json.leerObjeto(new String(D64.decode(p[0]), StandardCharsets.UTF_8));
            if (!"HS256".equals(cab.get("alg"))) return null; // rechaza «none» y cualquier otro algoritmo
            if (!MessageDigest.isEqual(D64.decode(p[2]), firmar(secreto, p[0] + "." + p[1]))) return null;
            Map<String, Object> carga = Json.leerObjeto(new String(D64.decode(p[1]), StandardCharsets.UTF_8));
            if (!(carga.get("exp") instanceof Number exp) || exp.longValue() < System.currentTimeMillis() / 1000) return null;
            return carga;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] firmar(String secreto, String datos) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(datos.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ límite de intentos

    /** Bloquea una clave (correo o dirección) tras {@code maximo} fallos seguidos durante 10 minutos. */
    public static final class LimitadorIntentos {
        private static final long VENTANA_MS = 10 * 60 * 1000;
        private final int maximo;
        private final Map<String, long[]> intentos = new java.util.concurrent.ConcurrentHashMap<>(); // {fallos, desde}

        public LimitadorIntentos(int maximo) {
            this.maximo = maximo;
        }

        public boolean permitido(String clave) {
            long[] e = intentos.get(clave);
            if (e == null) return true;
            if (System.currentTimeMillis() - e[1] > VENTANA_MS) { intentos.remove(clave); return true; }
            return e[0] < maximo;
        }

        public void fallo(String clave) {
            intentos.merge(clave, new long[] {1, System.currentTimeMillis()}, (a, b) -> {
                if (System.currentTimeMillis() - a[1] > VENTANA_MS) return b;
                return new long[] {a[0] + 1, a[1]};
            });
        }

        public void exito(String clave) {
            intentos.remove(clave);
        }
    }

    public static String secretoAleatorio() {
        byte[] b = new byte[48];
        ALEATORIO.nextBytes(b);
        return B64.encodeToString(b);
    }
}
