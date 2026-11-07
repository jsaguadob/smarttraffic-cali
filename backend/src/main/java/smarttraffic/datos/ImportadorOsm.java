package smarttraffic.datos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import smarttraffic.estructuras.ListaEnlazada;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.Interseccion;
import smarttraffic.red.RedVial;
import smarttraffic.red.TipoNodo;
import smarttraffic.red.Via;
import smarttraffic.util.Json;

/**
 * Convierte datos de OpenStreetMap (formato JSON de la API Overpass) en una {@link RedVial}.
 *
 * <p>Cada intersección real (donde se cruzan dos calles, termina una o hay un semáforo) pasa a ser
 * un nodo del grafo y cada tramo entre intersecciones una o dos vías, con su geometría curva, sentido
 * único, velocidad máxima y nombre. Solo se conserva la parte de la red en la que se puede llegar de
 * cualquier punto a cualquier otro respetando los sentidos.
 *
 * <p>Datos © colaboradores de OpenStreetMap, licencia ODbL.
 */
public final class ImportadorOsm {
    private ImportadorOsm() {}

    private static final Set<String> CALLES = Set.of("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
            "living_street", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link");

    public static final class Opciones {
        public String nombreRed = "Cali (OpenStreetMap)";
        public int maxEntradas = 20;
        public double separacionEntradas = 180;
        public double radioServicios = 400;
    }

    public record Resultado(RedVial red, ProyeccionLocal proyeccion, int tramosIgnorados, int semaforos, int bases, int descartadasPorConexion) {}

    private record NodoOsm(double lat, double lon, boolean semaforo) {}

    private record Tramo(long wayId, int indice, String nombre, String clase, double velocidad, int sentido, double[] latlon) {}

    // ------------------------------------------------------------------ importación

    @SuppressWarnings("unchecked")
    public static Resultado importar(String overpassJson, String serviciosJson, Opciones opc) {
        Map<String, Object> raiz = Json.leerObjeto(overpassJson);
        List<Object> elementos = (List<Object>) raiz.getOrDefault("elements", List.of());

        TablaHash<Long, NodoOsm> nodos = new TablaHash<>();
        List<Map<String, Object>> vias = new ArrayList<>();
        for (Object o : elementos) {
            Map<String, Object> e = (Map<String, Object>) o;
            String tipo = Json.texto(e, "type", "");
            if (tipo.equals("node") && e.get("lat") != null) {
                Map<String, Object> tags = etiquetas(e);
                nodos.poner(((Number) e.get("id")).longValue(), new NodoOsm(Json.numero(e, "lat", 0), Json.numero(e, "lon", 0), "traffic_signals".equals(tags.get("highway"))));
            } else if (tipo.equals("way")) {
                vias.add(e);
            }
        }

        // 1) calles por las que pueden circular vehículos
        List<Map<String, Object>> validas = new ArrayList<>();
        int ignoradas = 0;
        for (Map<String, Object> w : vias) {
            if (esTransitable(w)) validas.add(w); else ignoradas++;
        }

        // 2) cuántas calles pasan por cada nodo: donde son 2 o más, (o termina una, o hay semáforo) hay una intersección
        TablaHash<Long, Integer> usos = new TablaHash<>();
        for (Map<String, Object> w : validas) {
            for (Object n : (List<Object>) w.get("nodes")) usos.poner(((Number) n).longValue(), usos.obtenerODefecto(((Number) n).longValue(), 0) + 1);
        }

        // 3) proyección centrada en la zona
        double sumLat = 0, sumLon = 0;
        int cuenta = 0;
        for (Map<String, Object> w : validas) {
            for (Object n : (List<Object>) w.get("nodes")) {
                NodoOsm no = nodos.obtener(((Number) n).longValue());
                if (no != null) { sumLat += no.lat(); sumLon += no.lon(); cuenta++; }
            }
        }
        if (cuenta == 0) throw new IllegalArgumentException("Los datos no contienen calles transitables");
        ProyeccionLocal proy = new ProyeccionLocal(sumLat / cuenta, sumLon / cuenta);

        // 4) intersecciones y tramos
        RedVial red = new RedVial(opc.nombreRed);
        TablaHash<Long, Set<String>> nombresEnNodo = new TablaHash<>();
        List<Tramo> tramos = new ArrayList<>();
        for (Map<String, Object> w : validas) {
            long wayId = ((Number) w.get("id")).longValue();
            Map<String, Object> tags = etiquetas(w);
            List<Object> ids = (List<Object>) w.get("nodes");
            String nombre = Json.texto(tags, "name", "");
            String clase = Json.texto(tags, "highway", "");
            double vel = velocidad(tags, clase);
            int sentido = sentido(tags, clase);
            List<Double> acumulado = new ArrayList<>();
            long inicio = -1;
            int idx = 0;
            for (int k = 0; k < ids.size(); k++) {
                long id = ((Number) ids.get(k)).longValue();
                NodoOsm no = nodos.obtener(id);
                if (no == null) { acumulado.clear(); inicio = -1; continue; } // nodo fuera de los datos descargados
                boolean esVertice = k == 0 || k == ids.size() - 1 || usos.obtenerODefecto(id, 0) >= 2 || no.semaforo();
                if (inicio < 0) {
                    inicio = id;
                    acumulado.clear();
                    acumulado.add(id * 1.0);
                    acumulado.add(no.lat());
                    acumulado.add(no.lon());
                    registrarNombre(nombresEnNodo, id, nombre);
                    continue;
                }
                acumulado.add(id * 1.0);
                acumulado.add(no.lat());
                acumulado.add(no.lon());
                if (esVertice) {
                    registrarNombre(nombresEnNodo, id, nombre);
                    double[] arr = new double[acumulado.size()];
                    for (int j = 0; j < arr.length; j++) arr[j] = acumulado.get(j);
                    if (inicio != id) tramos.add(new Tramo(wayId, idx++, nombre, clase, vel, sentido, arr));
                    inicio = id;
                    acumulado.clear();
                    acumulado.add(id * 1.0);
                    acumulado.add(no.lat());
                    acumulado.add(no.lon());
                }
            }
        }

        // 5) crear nodos y vías
        TablaHash<Long, Boolean> creados = new TablaHash<>();
        int semaforos = 0;
        for (Tramo t : tramos) {
            for (int extremo = 0; extremo < 2; extremo++) {
                int k = extremo == 0 ? 0 : t.latlon().length - 3;
                long id = (long) t.latlon()[k];
                if (creados.contiene(id)) continue;
                creados.poner(id, true);
                NodoOsm no = nodos.obtener(id);
                Interseccion in = new Interseccion("N" + id, nombreInterseccion(nombresEnNodo.obtener(id), id), proy.x(no.lon()), proy.y(no.lat()), TipoNodo.INTERIOR);
                in.setGeo(no.lat(), no.lon());
                in.setSemaforizada(no.semaforo());
                if (no.semaforo()) semaforos++;
                red.agregarInterseccion(in);
            }
        }
        for (Tramo t : tramos) {
            int n = t.latlon().length / 3;
            double[] directo = new double[n * 2], inverso = new double[n * 2];
            for (int i = 0; i < n; i++) {
                double x = proy.x(t.latlon()[i * 3 + 2]), y = proy.y(t.latlon()[i * 3 + 1]);
                directo[i * 2] = x;
                directo[i * 2 + 1] = y;
                inverso[(n - 1 - i) * 2] = x;
                inverso[(n - 1 - i) * 2 + 1] = y;
            }
            String a = "N" + (long) t.latlon()[0], b = "N" + (long) t.latlon()[(n - 1) * 3];
            String base = "W" + t.wayId() + "." + t.indice();
            String nombre = t.nombre().isBlank() ? etiquetaClase(t.clase()) : t.nombre();
            if (t.sentido() >= 0) crearVia(red, base + "F", a, b, nombre, t, directo);
            if (t.sentido() <= 0) crearVia(red, base + "R", b, a, nombre, t, inverso);
        }

        // 6) quedarse con la parte conectada
        int descartadas = conservarComponentePrincipal(red);
        if (red.intersecciones().size() < 2) throw new IllegalArgumentException("La red importada quedó vacía");

        // 7) entradas y salidas en el borde, servicios de emergencia
        marcarEntradas(red, opc);
        int bases = serviciosJson == null ? 0 : asignarServicios(red, proy, serviciosJson, opc.radioServicios);
        int semaforosFinal = 0;
        for (Interseccion i : red.intersecciones()) if (i.semaforizada()) semaforosFinal++;
        return new Resultado(red, proy, ignoradas, semaforosFinal, bases, descartadas);
    }

    /** Etiquetas de un elemento; Overpass las omite cuando no hay, pero un valor nulo también se tolera. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> etiquetas(Map<String, Object> elemento) {
        return elemento.get("tags") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static void crearVia(RedVial red, String id, String desde, String hasta, String nombre, Tramo t, double[] puntos) {
        double largo = 0;
        for (int i = 2; i < puntos.length; i += 2) largo += Math.hypot(puntos[i] - puntos[i - 2], puntos[i + 1] - puntos[i - 1]);
        if (largo < 0.5 || desde.equals(hasta)) return;
        Via v = red.agregarVia(id, desde, hasta, nombre, null, t.velocidad(), puntos);
        v.setClase(t.clase());
    }

    // ------------------------------------------------------------------ reglas de OpenStreetMap

    @SuppressWarnings("unchecked")
    private static boolean esTransitable(Map<String, Object> w) {
        Map<String, Object> tags = etiquetas(w);
        if (!CALLES.contains(Json.texto(tags, "highway", ""))) return false;
        if ("yes".equals(tags.get("area"))) return false;
        String acceso = Json.texto(tags, "access", "");
        String motor = Json.texto(tags, "motor_vehicle", "");
        if (motor.equals("no") || motor.equals("private")) return false;
        if ((acceso.equals("no") || acceso.equals("private")) && !motor.equals("yes") && !motor.equals("designated")) return false;
        return w.get("nodes") instanceof List<?> l && l.size() >= 2;
    }

    /** 1 = solo en el sentido del dibujo, -1 = solo al contrario, 0 = doble sentido. */
    private static int sentido(Map<String, Object> tags, String clase) {
        String o = Json.texto(tags, "oneway", "");
        if (o.equals("yes") || o.equals("true") || o.equals("1")) return 1;
        if (o.equals("-1") || o.equals("reverse")) return -1;
        if (o.equals("no") || o.equals("false") || o.equals("0")) return 0;
        if (Json.texto(tags, "junction", "").equals("roundabout")) return 1;
        if (clase.startsWith("motorway")) return 1;
        return 0;
    }

    private static double velocidad(Map<String, Object> tags, String clase) {
        String m = Json.texto(tags, "maxspeed", "").trim().toLowerCase();
        if (!m.isEmpty()) {
            try {
                boolean millas = m.contains("mph");
                double v = Double.parseDouble(m.replaceAll("[^0-9.]", ""));
                if (millas) v *= 1.609344;
                if (v >= Via.VELOCIDAD_MIN_KMH) return Math.min(v, Via.VELOCIDAD_MAX_KMH);
            } catch (NumberFormatException ignorar) {
                // valores como «walk» o «signals»: se usa la velocidad típica de la clase
            }
        }
        return switch (clase.replace("_link", "")) {
            case "motorway" -> 80;
            case "trunk" -> 70;
            case "primary", "secondary" -> 50;
            case "tertiary", "unclassified" -> 40;
            case "residential" -> 30;
            default -> 20;
        };
    }

    private static String etiquetaClase(String clase) {
        return switch (clase.replace("_link", "")) {
            case "motorway" -> "Autopista sin nombre";
            case "trunk", "primary" -> "Vía principal sin nombre";
            case "secondary" -> "Vía secundaria sin nombre";
            case "tertiary" -> "Vía terciaria sin nombre";
            default -> "Calle sin nombre";
        };
    }

    private static void registrarNombre(TablaHash<Long, Set<String>> mapa, long nodo, String nombre) {
        if (nombre.isBlank()) return;
        Set<String> s = mapa.obtener(nodo);
        if (s == null) {
            s = new java.util.LinkedHashSet<>();
            mapa.poner(nodo, s);
        }
        s.add(nombre);
    }

    private static String nombreInterseccion(Set<String> nombres, long id) {
        if (nombres == null || nombres.isEmpty()) return "Intersección " + id;
        List<String> l = new ArrayList<>(nombres);
        return l.size() == 1 ? l.get(0) : l.get(0) + " con " + l.get(1);
    }

    // ------------------------------------------------------------------ conectividad

    /** Deja solo la mayor componente fuertemente conexa (Kosaraju iterativo). Devuelve cuántas intersecciones se descartaron. */
    private static int conservarComponentePrincipal(RedVial red) {
        List<Interseccion> nodos = red.intersecciones();
        int n = nodos.size();
        TablaHash<String, Integer> indice = new TablaHash<>();
        for (int i = 0; i < n; i++) indice.poner(nodos.get(i).id(), i);
        List<Via> vias = red.vias();
        int[] cs = new int[n + 1], cr = new int[n + 1];
        for (Via v : vias) { cs[indice.obtener(v.desde()) + 1]++; cr[indice.obtener(v.hacia()) + 1]++; }
        for (int i = 0; i < n; i++) { cs[i + 1] += cs[i]; cr[i + 1] += cr[i]; }
        int[] adj = new int[vias.size()], radj = new int[vias.size()], ps = Arrays.copyOf(cs, n), pr = Arrays.copyOf(cr, n);
        for (Via v : vias) {
            int a = indice.obtener(v.desde()), b = indice.obtener(v.hacia());
            adj[ps[a]++] = b;
            radj[pr[b]++] = a;
        }
        int[] orden = new int[n];
        int no = 0;
        boolean[] visto = new boolean[n];
        int[] pila = new int[n], cursor = new int[n];
        for (int s = 0; s < n; s++) {
            if (visto[s]) continue;
            int sp = 0;
            pila[sp++] = s;
            visto[s] = true;
            cursor[s] = cs[s];
            while (sp > 0) {
                int u = pila[sp - 1];
                if (cursor[u] < cs[u + 1]) {
                    int w = adj[cursor[u]++];
                    if (!visto[w]) { visto[w] = true; cursor[w] = cs[w]; pila[sp++] = w; }
                } else {
                    orden[no++] = u;
                    sp--;
                }
            }
        }
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int ncomp = 0;
        List<Integer> tamanos = new ArrayList<>();
        for (int k = n - 1; k >= 0; k--) {
            int s = orden[k];
            if (comp[s] >= 0) continue;
            int sp = 0, tam = 0;
            pila[sp++] = s;
            comp[s] = ncomp;
            while (sp > 0) {
                int u = pila[--sp];
                tam++;
                for (int e = cr[u]; e < cr[u + 1]; e++) {
                    int w = radj[e];
                    if (comp[w] < 0) { comp[w] = ncomp; pila[sp++] = w; }
                }
            }
            tamanos.add(tam);
            ncomp++;
        }
        int mejor = 0;
        for (int c = 1; c < ncomp; c++) if (tamanos.get(c) > tamanos.get(mejor)) mejor = c;
        int descartadas = 0;
        for (int i = 0; i < n; i++) {
            if (comp[i] != mejor) { red.eliminarInterseccion(nodos.get(i).id()); descartadas++; }
        }
        return descartadas;
    }

    // ------------------------------------------------------------------ entradas, salidas y servicios

    private static void marcarEntradas(RedVial red, Opciones opc) {
        List<Interseccion> nodos = red.intersecciones();
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Interseccion i : nodos) { minX = Math.min(minX, i.x()); maxX = Math.max(maxX, i.x()); minY = Math.min(minY, i.y()); maxY = Math.max(maxY, i.y()); }
        final double fx0 = minX, fx1 = maxX, fy0 = minY, fy1 = maxY;
        for (double banda : new double[] {0.12, 0.25, 0.5}) {
            double ancho = Math.min(fx1 - fx0, fy1 - fy0) * banda;
            List<Interseccion> candidatos = new ArrayList<>();
            for (Interseccion i : nodos) {
                double borde = Math.min(Math.min(i.x() - fx0, fx1 - i.x()), Math.min(i.y() - fy0, fy1 - i.y()));
                if (borde <= ancho && !red.grafo().salientes(i.id()).estaVacia() && !red.grafo().entrantes(i.id()).estaVacia()) candidatos.add(i);
            }
            candidatos.sort((a, b) -> Double.compare(distanciaAlBorde(a, fx0, fx1, fy0, fy1), distanciaAlBorde(b, fx0, fx1, fy0, fy1)));
            List<Interseccion> elegidas = new ArrayList<>();
            for (Interseccion c : candidatos) {
                boolean lejos = true;
                for (Interseccion e : elegidas) if (c.distanciaA(e) < opc.separacionEntradas) { lejos = false; break; }
                if (lejos) elegidas.add(c);
                if (elegidas.size() >= opc.maxEntradas) break;
            }
            if (elegidas.size() >= 4 || banda == 0.5) {
                for (Interseccion e : elegidas) e.setTipo(TipoNodo.ENTRADA_SALIDA);
                if (elegidas.size() >= 2) return;
            }
        }
        // red muy pequeña: se usan las dos intersecciones más alejadas
        nodos.get(0).setTipo(TipoNodo.ENTRADA_SALIDA);
        nodos.get(nodos.size() - 1).setTipo(TipoNodo.ENTRADA_SALIDA);
    }

    private static double distanciaAlBorde(Interseccion i, double x0, double x1, double y0, double y1) {
        return Math.min(Math.min(i.x() - x0, x1 - i.x()), Math.min(i.y() - y0, y1 - i.y()));
    }

    /** Marca como semaforizadas las intersecciones más cercanas (dentro de {@code radio} m) a los puntos dados. */
    public static int marcarSemaforos(RedVial red, ProyeccionLocal proy, List<DatosAbiertos.Punto> puntos, double radio) {
        IndiceEspacial idx = new IndiceEspacial(100);
        for (Interseccion i : red.intersecciones()) idx.agregar(i);
        Set<String> marcadas = new HashSet<>();
        for (DatosAbiertos.Punto p : puntos) {
            Interseccion i = idx.masCercana(proy.x(p.lon()), proy.y(p.lat()), radio);
            if (i != null && marcadas.add(i.id())) i.setSemaforizada(true);
        }
        return marcadas.size();
    }

    @SuppressWarnings("unchecked")
    private static int asignarServicios(RedVial red, ProyeccionLocal proy, String json, double radio) {
        IndiceEspacial idx = new IndiceEspacial(150);
        for (Interseccion i : red.intersecciones()) idx.agregar(i);
        int n = 0;
        for (Object o : (List<Object>) Json.leerObjeto(json).getOrDefault("elements", List.of())) {
            Map<String, Object> e = (Map<String, Object>) o;
            Map<String, Object> tags = etiquetas(e);
            String servicio = switch (Json.texto(tags, "amenity", "")) {
                case "hospital" -> "hospital";
                case "fire_station" -> "bomberos";
                case "police" -> "policia";
                default -> null;
            };
            if (servicio == null) continue;
            double lat = e.get("lat") != null ? Json.numero(e, "lat", 0) : Json.numero((Map<String, Object>) e.getOrDefault("center", Map.of()), "lat", Double.NaN);
            double lon = e.get("lon") != null ? Json.numero(e, "lon", 0) : Json.numero((Map<String, Object>) e.getOrDefault("center", Map.of()), "lon", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            Interseccion i = idx.masCercana(proy.x(lon), proy.y(lat), radio);
            if (i == null || i.servicios().contains(servicio)) continue;
            i.servicios().add(servicio);
            String nombre = Json.texto(tags, "name", "");
            if (!nombre.isBlank() && i.servicios().size() == 1) i.setNombre(nombre + " (" + i.nombre() + ")");
            n++;
        }
        return n;
    }

    /** Consulta de la API Overpass que descarga las calles y semáforos de una zona (s, w, n, e en grados). */
    public static String consultaCalles(double s, double w, double n, double e) {
        String bbox = s + "," + w + "," + n + "," + e;
        return "[out:json][timeout:180];(way[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"](" + bbox + "););(._;>;);out body;";
    }

    /** Consulta de la API Overpass que descarga hospitales, estaciones de bomberos y de policía de una zona. */
    public static String consultaServicios(double s, double w, double n, double e) {
        String bbox = s + "," + w + "," + n + "," + e;
        return "[out:json][timeout:60];nwr[\"amenity\"~\"^(hospital|fire_station|police)$\"](" + bbox + ");out center;";
    }
}
