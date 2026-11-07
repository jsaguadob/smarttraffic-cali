import java.util.*;
import smarttraffic.red.*;
import smarttraffic.sim.*;
import smarttraffic.sim.ComparadorEscenarios.*;
public class Experimento {
    static double media(double[] a) { double s = 0; for (double x : a) s += x; return s / a.length; }
    static double sd(double[] a) { double m = media(a), s = 0; for (double x : a) s += (x - m) * (x - m); return Math.sqrt(s / (a.length - 1)); }
    public static void main(String[] args) {
        RedVial red = RedCali.crear();
        double[] cargas = {30, 60, 90, 150, 220};
        int semillas = 10;
        double dur = 600;
        StringBuilder j = new StringBuilder("{\"duracion\":" + dur + ",\"semillas\":" + semillas + ",\"red\":{\"intersecciones\":" + red.intersecciones().size() + ",\"vias\":" + red.vias().size() + "},\"flujo\":[");
        for (int c = 0; c < cargas.length; c++) {
            double[] tt = new double[semillas], ts = new double[semillas], et = new double[semillas], es = new double[semillas], vt = new double[semillas], vs = new double[semillas], rd = new double[semillas], gt = new double[semillas], gs = new double[semillas];
            double[] ct = new double[semillas], cs = new double[semillas];
            for (int s = 0; s < semillas; s++) {
                Resultado[] r = ComparadorEscenarios.comparar(red, 100 + s, cargas[c], dur, List.of());
                tt[s] = r[0].viajeMedioAjustado(); ts[s] = r[1].viajeMedioAjustado();
                et[s] = r[0].esperaMedia(); es[s] = r[1].esperaMedia();
                vt[s] = r[0].velocidadMedia(); vs[s] = r[1].velocidadMedia();
                gt[s] = r[0].completados(); gs[s] = r[1].completados();
                ct[s] = r[0].viasCongestionadasMedia(); cs[s] = r[1].viasCongestionadasMedia();
                rd[s] = 100.0 * (tt[s] - ts[s]) / tt[s];
            }
            j.append(c > 0 ? "," : "").append(String.format(Locale.ROOT,
                "{\"vpm\":%.0f,\"viajeTrad\":[%.2f,%.2f],\"viajeSmart\":[%.2f,%.2f],\"reduccionViaje\":[%.2f,%.2f],\"esperaTrad\":[%.2f,%.2f],\"esperaSmart\":[%.2f,%.2f],\"velTrad\":[%.2f,%.2f],\"velSmart\":[%.2f,%.2f],\"complTrad\":[%.1f,%.1f],\"complSmart\":[%.1f,%.1f],\"congTrad\":[%.2f,%.2f],\"congSmart\":[%.2f,%.2f]}",
                cargas[c], media(tt), sd(tt), media(ts), sd(ts), media(rd), sd(rd), media(et), sd(et), media(es), sd(es), media(vt), sd(vt), media(vs), sd(vs), media(gt), sd(gt), media(gs), sd(gs), media(ct), sd(ct), media(cs), sd(cs)));
        }
        j.append("],\"emergencias\":");
        // dos accidentes (a los 60 s y a los 200 s) con 60 vehículos por minuto
        String vA = null, vB = null;
        for (Via v : red.vias()) { if (vA == null && v.nombre().equals("Calle 13")) vA = v.id(); if (vB == null && v.nombre().equals("Carrera 15")) vB = v.id(); }
        List<Accion> acc = List.of(new Accion(60, "incidente", vA, 3, null), new Accion(200, "incidente", vB, 2, null));
        double[] rt = new double[semillas], rs = new double[semillas], at = new double[semillas], as = new double[semillas], ft = new double[semillas], fs = new double[semillas];
        for (int s = 0; s < semillas; s++) {
            Resultado[] r = ComparadorEscenarios.comparar(red, 100 + s, 60, dur, acc);
            rt[s] = r[0].respuestaMedia(); rs[s] = r[1].respuestaMedia(); at[s] = r[0].emergenciasAtendidas(); as[s] = r[1].emergenciasAtendidas();
            ft[s] = r[0].viajeMedioAjustado(); fs[s] = r[1].viajeMedioAjustado();
        }
        j.append(String.format(Locale.ROOT, "{\"vpm\":60,\"accidentes\":2,\"respTrad\":[%.2f,%.2f],\"respSmart\":[%.2f,%.2f],\"atendTrad\":[%.2f,%.2f],\"atendSmart\":[%.2f,%.2f],\"viajeTrad\":[%.2f,%.2f],\"viajeSmart\":[%.2f,%.2f]}}", media(rt), sd(rt), media(rs), sd(rs), media(at), sd(at), media(as), sd(as), media(ft), sd(ft), media(fs), sd(fs)));
        System.out.println(j);
    }
}
