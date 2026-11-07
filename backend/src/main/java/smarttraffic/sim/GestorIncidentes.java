package smarttraffic.sim;

import java.util.ArrayList;
import java.util.List;
import smarttraffic.estructuras.ArbolAvl;
import smarttraffic.estructuras.TablaHash;
import smarttraffic.red.Via;

/**
 * Registro de incidentes. Combina dos estructuras: una tabla hash para encontrar un incidente por
 * su identificador (INC-003) en O(1) y un árbol AVL ordenado por consecutivo, que permite listar
 * el historial en orden y consultar rangos sin ordenar cada vez.
 */
public final class GestorIncidentes {
    private final TablaHash<String, Incidente> porId = new TablaHash<>();
    private final ArbolAvl<Integer, Incidente> historial = new ArbolAvl<>();
    private int contador;

    public Incidente registrar(Via via, double posicion, int gravedad, double t) {
        if (gravedad < 1 || gravedad > 3) throw new IllegalArgumentException("La gravedad debe ser 1 (leve), 2 (moderada) o 3 (grave)");
        Incidente i = new Incidente(++contador, via.id(), posicion, gravedad, t);
        porId.poner(i.id, i);
        historial.insertar(i.consecutivo, i);
        return i;
    }

    public Incidente obtener(String id) {
        return porId.obtener(id);
    }

    public int total() {
        return contador;
    }

    /** Todos los incidentes, del más antiguo al más reciente. */
    public List<Incidente> historialOrdenado() {
        List<Incidente> lista = new ArrayList<>();
        historial.inorden((k, v) -> lista.add(v));
        return lista;
    }

    /** Incidentes que todavía bloquean o se están atendiendo. */
    public List<Incidente> abiertos() {
        List<Incidente> lista = new ArrayList<>();
        historial.inorden((k, v) -> { if (v.abierto()) lista.add(v); });
        return lista;
    }

    /** Los n incidentes más recientes, del más nuevo al más antiguo. */
    public List<Incidente> recientes(int n) {
        return historial.mayores(n);
    }

    /** Pasa el tiempo; devuelve los incidentes que se resolvieron solos en este paso. */
    public List<Incidente> avanzar(double dt, double t) {
        List<Incidente> resueltos = new ArrayList<>();
        for (Incidente i : abiertos()) {
            i.restante -= dt;
            if (i.restante <= 0) {
                i.estado = Incidente.Estado.RESUELTO;
                i.tResuelto = t;
                resueltos.add(i);
            }
        }
        return resueltos;
    }

    /** Llegó el equipo de emergencia: el incidente pasa a atenderse y se despeja más rápido. */
    public void atender(Incidente i) {
        if (i.estado == Incidente.Estado.ACTIVO) {
            i.estado = Incidente.Estado.EN_ATENCION;
            i.restante = Math.min(i.restante, Incidente.tiempoServicio(i.gravedad));
        }
    }

    public boolean resolver(String id, double t) {
        Incidente i = porId.obtener(id);
        if (i == null || !i.abierto()) return false;
        i.estado = Incidente.Estado.RESUELTO;
        i.tResuelto = t;
        return true;
    }
}
