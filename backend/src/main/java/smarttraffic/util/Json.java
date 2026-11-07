package smarttraffic.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector y escritor de JSON mínimo (sin dependencias externas).
 * Los objetos se representan con {@code Map<String,Object>}, las listas con {@code List<Object>}
 * y los números con {@code Double}.
 */
public final class Json {
    private Json() {}

    // ---------------------------------------------------------------- escritura

    public static String escribir(Object valor) {
        StringBuilder sb = new StringBuilder(256);
        escribir(sb, valor);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static void escribir(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            cadena(sb, s);
        } else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(Math.round(d * 1000.0) / 1000.0);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primero = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) m).entrySet()) {
                if (!primero) sb.append(',');
                primero = false;
                cadena(sb, e.getKey());
                sb.append(':');
                escribir(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean primero = true;
            for (Object o : it) {
                if (!primero) sb.append(',');
                primero = false;
                escribir(sb, o);
            }
            sb.append(']');
        } else if (v instanceof double[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); escribir(sb, a[i]); }
            sb.append(']');
        } else if (v instanceof int[] a) {
            sb.append('[');
            for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
            sb.append(']');
        } else {
            cadena(sb, v.toString());
        }
    }

    public static void cadena(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Atajo para armar objetos: {@code Json.obj("a", 1, "b", "x")}. */
    public static Map<String, Object> obj(Object... paresClaveValor) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < paresClaveValor.length; i += 2) m.put((String) paresClaveValor[i], paresClaveValor[i + 1]);
        return m;
    }

    // ---------------------------------------------------------------- lectura

    public static final class ErrorJson extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ErrorJson(String mensaje) {
            super(mensaje);
        }
    }

    public static Object leer(String texto) {
        Lector l = new Lector(texto);
        l.espacios();
        Object valor = l.valor();
        l.espacios();
        if (l.pos != texto.length()) throw new ErrorJson("Texto sobrante en la posición " + l.pos);
        return valor;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> leerObjeto(String texto) {
        Object o = leer(texto);
        if (!(o instanceof Map)) throw new ErrorJson("Se esperaba un objeto JSON");
        return (Map<String, Object>) o;
    }

    private static final class Lector {
        final String s;
        int pos;

        Lector(String s) {
            this.s = s;
        }

        void espacios() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char ver() {
            if (pos >= s.length()) throw new ErrorJson("Fin inesperado del texto");
            return s.charAt(pos);
        }

        void esperar(char c) {
            if (ver() != c) throw new ErrorJson("Se esperaba '" + c + "' en la posición " + pos);
            pos++;
        }

        Object valor() {
            char c = ver();
            if (c == '{') return objeto();
            if (c == '[') return lista();
            if (c == '"') return texto();
            if (s.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (s.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            if (s.startsWith("null", pos)) { pos += 4; return null; }
            return numero();
        }

        Map<String, Object> objeto() {
            Map<String, Object> m = new LinkedHashMap<>();
            esperar('{');
            espacios();
            if (ver() == '}') { pos++; return m; }
            while (true) {
                espacios();
                String clave = texto();
                espacios();
                esperar(':');
                espacios();
                m.put(clave, valor());
                espacios();
                if (ver() == ',') { pos++; continue; }
                esperar('}');
                return m;
            }
        }

        List<Object> lista() {
            List<Object> l = new ArrayList<>();
            esperar('[');
            espacios();
            if (ver() == ']') { pos++; return l; }
            while (true) {
                espacios();
                l.add(valor());
                espacios();
                if (ver() == ',') { pos++; continue; }
                esperar(']');
                return l;
            }
        }

        String texto() {
            esperar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = ver();
                pos++;
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = ver();
                pos++;
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (pos + 4 > s.length()) throw new ErrorJson("Escape \\u incompleto");
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> sb.append(e);
                }
            }
        }

        Double numero() {
            int ini = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            if (ini == pos) throw new ErrorJson("Valor inválido en la posición " + pos);
            try {
                return Double.valueOf(s.substring(ini, pos));
            } catch (NumberFormatException ex) {
                throw new ErrorJson("Número inválido: " + s.substring(ini, pos));
            }
        }
    }

    // ---------------------------------------------------------------- ayudas de acceso

    public static String texto(Map<String, Object> m, String clave, String defecto) {
        Object o = m.get(clave);
        return o instanceof String s ? s : defecto;
    }

    public static double numero(Map<String, Object> m, String clave, double defecto) {
        Object o = m.get(clave);
        return o instanceof Number n ? n.doubleValue() : defecto;
    }

    public static boolean booleano(Map<String, Object> m, String clave, boolean defecto) {
        Object o = m.get(clave);
        return o instanceof Boolean b ? b : defecto;
    }
}
