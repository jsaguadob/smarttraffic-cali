import { useEffect, useMemo, useState } from 'react';
import type { ReactElement } from 'react';
import { api, numero } from './api';
import { BarrasComparativas, GraficaLineas } from './charts';
import type { RedGeo } from './geometria';
import type { Comparacion, FilaHistorial } from './tipos';

function mejora(a: number, b: number): number {
  return a === 0 ? 0 : ((a - b) / a) * 100;
}

export function Comparar({ geo }: { geo: RedGeo }): ReactElement {
  const centro = useMemo(() => {
    const c = geo.caja;
    return { x: (c.minX + c.maxX) / 2, y: (c.minY + c.maxY) / 2 };
  }, [geo]);
  const viaCentral = useMemo(() => {
    let mejor = geo.vias[0], md = Infinity;
    for (const g of geo.vias) {
      const m = g.via.puntos;
      const d = Math.hypot((m[0] + m[m.length - 2]) / 2 - centro.x, (m[1] + m[m.length - 1]) / 2 - centro.y);
      if (d < md && g.via.longitud > 60) { md = d; mejor = g; }
    }
    return mejor?.via.id ?? '';
  }, [geo, centro]);
  const nodoCentral = useMemo(() => {
    let mejor = geo.nodos[0], md = Infinity;
    for (const n of geo.nodos) { const d = Math.hypot(n.x - centro.x, n.y - centro.y); if (d < md) { md = d; mejor = n; } }
    return mejor?.id ?? '';
  }, [geo, centro]);

  const [vpm, setVpm] = useState(90);
  const [duracion, setDuracion] = useState(600);
  const [semilla, setSemilla] = useState(1);
  const [conAcc, setConAcc] = useState(true);
  const [via, setVia] = useState('');
  const [grav, setGrav] = useState(3);
  const [conEmg, setConEmg] = useState(true);
  const [nodo, setNodo] = useState('');
  const [filtro, setFiltro] = useState('');
  const [ejecutando, setEjecutando] = useState(false);
  const [error, setError] = useState('');
  const [res, setRes] = useState<Comparacion | null>(null);
  const [historial, setHistorial] = useState<FilaHistorial[]>([]);

  const viaSel = via || viaCentral;
  const nodoSel = nodo || nodoCentral;
  const opcionesVia = useMemo(() => {
    const f = filtro.trim().toLowerCase();
    const l = geo.vias.filter((g) => !f || g.via.nombre.toLowerCase().includes(f) || g.via.id.toLowerCase().includes(f)).slice(0, 150);
    const actual = geo.vias.find((g) => g.via.id === viaSel);
    return actual && !l.includes(actual) ? [actual, ...l] : l;
  }, [geo, filtro, viaSel]);
  const opcionesNodo = useMemo(() => {
    const f = filtro.trim().toLowerCase();
    const l = geo.nodos.filter((n) => !f || n.nombre.toLowerCase().includes(f)).slice(0, 150);
    const actual = geo.nodos.find((n) => n.id === nodoSel);
    return actual && !l.includes(actual) ? [actual, ...l] : l;
  }, [geo, filtro, nodoSel]);

  const cargarHistorial = () => { void api<FilaHistorial[]>('GET', '/api/historial?limite=10').then(setHistorial).catch(() => undefined); };
  useEffect(cargarHistorial, []);

  const ejecutar = async () => {
    setError('');
    setEjecutando(true);
    try {
      const cuerpo: Record<string, unknown> = { semilla, vehiculosPorMinuto: vpm, duracion };
      if (conAcc) Object.assign(cuerpo, { incidenteVia: viaSel, incidenteT: Math.round(duracion * 0.2), gravedad: grav });
      if (conEmg) Object.assign(cuerpo, { emergenciaNodo: nodoSel, emergenciaT: Math.round(duracion * 0.35) });
      setRes(await api<Comparacion>('POST', '/api/comparar', cuerpo));
      cargarHistorial();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Error');
    } finally {
      setEjecutando(false);
    }
  };

  const [a, b] = res?.escenarios ?? [];

  return (
    <div className="pagina">
      <div className="interior">
        <div className="cabeza-pagina">
          <h1>Comparar tráfico tradicional y SmartTraffic</h1>
          <p>Se simulan dos escenarios con exactamente la misma llegada de vehículos y los mismos eventos: uno con semáforos de tiempo fijo y rutas que no reaccionan, y otro con semáforos inteligentes, prioridad a emergencias y cambio de ruta ante la congestión. Los resultados son <b>estimaciones de un modelo</b>, no mediciones del tráfico real.</p>
        </div>
        <section className="tarjeta" aria-label="Parámetros de la comparación">
          <div className="formulario">
            <label className="campo">Vehículos por minuto: <b>{vpm}</b><input type="range" min={20} max={250} step={10} value={vpm} onChange={(e) => setVpm(Number(e.target.value))} /></label>
            <label className="campo">Duración
              <select value={duracion} onChange={(e) => setDuracion(Number(e.target.value))}>
                <option value={300}>5 minutos</option><option value={600}>10 minutos</option><option value={900}>15 minutos</option><option value={1800}>30 minutos</option>
              </select>
            </label>
            <label className="campo">Semilla (repite el mismo tráfico)<input type="number" min={0} value={semilla} onChange={(e) => setSemilla(Number(e.target.value))} /></label>
            <label className="campo">Buscar calle o intersección<input value={filtro} onChange={(e) => setFiltro(e.target.value)} placeholder="Escribe para filtrar las listas" /></label>
          </div>
          <div className="formulario" style={{ marginTop: 14 }}>
            <div className="campo">
              <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}><input type="checkbox" checked={conAcc} onChange={(e) => setConAcc(e.target.checked)} /> Provocar un accidente</label>
              <select disabled={!conAcc} value={viaSel} onChange={(e) => setVia(e.target.value)} aria-label="Calle del accidente">
                {opcionesVia.map((g) => <option key={g.via.id} value={g.via.id}>{g.via.nombre} ({g.via.id})</option>)}
              </select>
            </div>
            <label className="campo">Gravedad
              <select disabled={!conAcc} value={grav} onChange={(e) => setGrav(Number(e.target.value))}><option value={1}>Leve</option><option value={2}>Moderado</option><option value={3}>Grave</option></select>
            </label>
            <div className="campo">
              <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}><input type="checkbox" checked={conEmg} onChange={(e) => setConEmg(e.target.checked)} /> Enviar una ambulancia a</label>
              <select disabled={!conEmg} value={nodoSel} onChange={(e) => setNodo(e.target.value)} aria-label="Destino de la ambulancia">
                {opcionesNodo.map((n) => <option key={n.id} value={n.id}>{n.nombre}</option>)}
              </select>
            </div>
            <button className="boton primario" onClick={() => void ejecutar()} disabled={ejecutando}>{ejecutando ? 'Simulando…' : '▶ Comparar escenarios'}</button>
          </div>
          {error && <div className="error" role="alert" style={{ marginTop: 12 }}>{error}</div>}
        </section>

        {res && a && b && (
          <>
            <section className="tarjeta" aria-label="Resultado">
              <p className="resumen-comp" data-testid="resumen-comparacion">
                Con {res.vehiculosPorMinuto} vehículos por minuto durante {Math.round(res.duracion / 60)} minutos, SmartTraffic{' '}
                {mejora(a.viajeMedioAjustado, b.viajeMedioAjustado) >= 0 ? <>redujo el tiempo de viaje medio un <b>{mejora(a.viajeMedioAjustado, b.viajeMedioAjustado).toFixed(0)} %</b></> : <>aumentó el tiempo de viaje medio un <b style={{ color: 'var(--peligro)' }}>{Math.abs(mejora(a.viajeMedioAjustado, b.viajeMedioAjustado)).toFixed(0)} %</b></>}
                {' '}({numero(a.viajeMedioAjustado)} s → {numero(b.viajeMedioAjustado)} s) y la espera media un <b>{mejora(a.esperaMedia, b.esperaMedia).toFixed(0)} %</b> ({numero(a.esperaMedia)} s → {numero(b.esperaMedia)} s).
                {a.emergenciasAtendidas > 0 && b.emergenciasAtendidas > 0 && <> Las emergencias llegaron en {numero(b.respuestaMedia)} s frente a {numero(a.respuestaMedia)} s.</>}
              </p>
            </section>
            <div className="dos-col">
              <section className="tarjeta">
                <BarrasComparativas nombreA={a.nombre} nombreB={b.nombre} filas={[
                  { etiqueta: 'Tiempo de viaje medio', a: a.viajeMedioAjustado, b: b.viajeMedioAjustado, unidad: 's', menorEsMejor: true },
                  { etiqueta: 'Tiempo detenido por viaje', a: a.esperaMedia, b: b.esperaMedia, unidad: 's', menorEsMejor: true },
                  { etiqueta: 'Velocidad media', a: a.velocidadMedia, b: b.velocidadMedia, unidad: 'km/h', menorEsMejor: false, decimales: 1 },
                  { etiqueta: 'Calles congestionadas (promedio)', a: a.viasCongestionadasMedia, b: b.viasCongestionadasMedia, unidad: '', menorEsMejor: true, decimales: 1 },
                  { etiqueta: 'Viajes completados', a: a.completados, b: b.completados, unidad: '', menorEsMejor: false },
                  ...(a.emergenciasAtendidas > 0 && b.emergenciasAtendidas > 0 ? [{ etiqueta: 'Respuesta de emergencias', a: a.respuestaMedia, b: b.respuestaMedia, unidad: 's', menorEsMejor: true } as const] : []),
                ]} />
              </section>
              <div style={{ display: 'grid', gap: 16 }}>
                <GraficaLineas titulo="Velocidad media de la red" unidad="km/h" series={[{ nombre: a.nombre, color: '#ff8a3d', datos: a.serie.map((m) => ({ x: m.t, y: m.velocidadKmh })) }, { nombre: b.nombre, color: '#3ea6ff', datos: b.serie.map((m) => ({ x: m.t, y: m.velocidadKmh })) }]} />
                <GraficaLineas titulo="Calles congestionadas" unidad="calles" series={[{ nombre: a.nombre, color: '#ff8a3d', datos: a.serie.map((m) => ({ x: m.t, y: m.congestionadas })) }, { nombre: b.nombre, color: '#3ea6ff', datos: b.serie.map((m) => ({ x: m.t, y: m.congestionadas })) }]} />
              </div>
            </div>
            <p className="aviso">Ambos escenarios recibieron {numero(a.generados)} vehículos. SmartTraffic hizo {numero(b.reruteos)} cambios de ruta. «Tiempo de viaje» incluye a los vehículos que aún no terminaban, para no favorecer al escenario que deja más gente atascada.</p>
          </>
        )}

        <section className="tarjeta" aria-label="Historial">
          <h2 style={{ marginBottom: 10 }}>Historial de simulaciones guardadas</h2>
          {historial.length === 0 ? <p style={{ color: 'var(--texto3)' }}>Aún no hay comparaciones guardadas.</p> : (
            <table className="tabla">
              <thead><tr><th>#</th><th>Fecha</th><th>Escenario</th><th>Tráfico</th><th>Duración</th><th>Semilla</th><th>Usuario</th></tr></thead>
              <tbody>
                {historial.map((h) => (
                  <tr key={h.id}>
                    <td>{h.id}</td><td>{new Date(h.creado).toLocaleString('es-CO')}</td><td>{h.modo === 'INTELIGENTE' ? 'SmartTraffic' : 'Tradicional'}</td>
                    <td>{h.vehiculosPorMinuto} /min</td><td>{Math.round(h.duracion / 60)} min</td><td>{h.semilla}</td><td>{h.usuario ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>
      </div>
    </div>
  );
}
