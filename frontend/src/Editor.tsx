import { useEffect, useMemo, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { api, numero } from './api';
import { construirGeo } from './geometria';
import { ImportarMapa } from './ImportarMapa';
import { MapaTrafico } from './MapaTrafico';
import type { CapasMapa, FuenteInstantanea, ManejadorMapa } from './MapaTrafico';
import type { NodoRed, Red, ResultadoEdicion } from './tipos';

type Herr = 'seleccionar' | 'anadir' | 'conectar' | 'eliminar';

const HERRAMIENTAS: [Herr, string, string, string][] = [
  ['seleccionar', 'Mover', 'M5 3l14 8-6 2 3 6-3 1-3-6-5 4z', 'Arrastra una intersección para moverla; haz clic para editar sus datos.'],
  ['anadir', 'Añadir', 'M12 5v14M5 12h14', 'Haz clic en un espacio vacío para crear una intersección.'],
  ['conectar', 'Calle', 'M4 20L20 4M4 20h4M4 20v-4M20 4h-4M20 4v4', 'Haz clic en una intersección y luego en otra para crear una calle entre ellas.'],
  ['eliminar', 'Borrar', 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13', 'Haz clic en una intersección o en una calle para borrarla.'],
];

const CAPAS: CapasMapa = { base: true, vehiculos: false, congestion: false, semaforos: false, rutas: false, calor: false };

function moverLocal(red: Red, id: string, x: number, y: number): Red {
  return {
    ...red,
    intersecciones: red.intersecciones.map((n) => (n.id === id ? { ...n, x, y } : n)),
    vias: red.vias.map((v) => {
      if (v.desde !== id && v.hacia !== id) return v;
      const p = v.puntos.slice();
      if (v.desde === id) { p[0] = x; p[1] = y; }
      if (v.hacia === id) { p[p.length - 2] = x; p[p.length - 1] = y; }
      return { ...v, puntos: p };
    }),
  };
}

export function Editor({ red, recargar, alMapaImportado }: { red: Red; recargar: () => Promise<void>; alMapaImportado: () => void }): ReactElement {
  const mapa = useRef<ManejadorMapa>(null);
  const fuente = useRef<FuenteInstantanea>({ inst: null, recibido: 0 });
  const [local, setLocal] = useState<Red>(red);
  const [herr, setHerr] = useState<Herr>('seleccionar');
  const [sel, setSel] = useState<{ tipo: 'via' | 'nodo'; id: string } | null>(null);
  const [origen, setOrigen] = useState<string | null>(null);
  const [conexion, setConexion] = useState<{ desde: string; hacia: string } | null>(null);
  const [resultado, setResultado] = useState<ResultadoEdicion | null>(null);
  const [error, setError] = useState('');
  const [info, setInfo] = useState('');
  const arrastrando = useRef(false);

  useEffect(() => { if (!arrastrando.current) setLocal(red); }, [red]);
  const geo = useMemo(() => construirGeo(local), [local]);

  const ejecutar = async (metodo: string, ruta: string, cuerpo?: unknown, ok?: string): Promise<ResultadoEdicion | null> => {
    setError('');
    try {
      const r = await api<ResultadoEdicion>(metodo, ruta, cuerpo);
      setResultado(r);
      if (ok) setInfo(ok);
      await recargar();
      return r;
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Error');
      await recargar();
      return null;
    }
  };

  const nodo = sel?.tipo === 'nodo' ? local.intersecciones.find((n) => n.id === sel.id) : undefined;
  const via = sel?.tipo === 'via' ? local.vias.find((v) => v.id === sel.id) : undefined;

  const alClickNodo = (id: string) => {
    setInfo('');
    if (herr === 'conectar') {
      if (!origen) setOrigen(id);
      else if (origen === id) setOrigen(null);
      else { setConexion({ desde: origen, hacia: id }); setOrigen(null); }
    } else if (herr === 'eliminar') {
      const n = local.intersecciones.find((x) => x.id === id);
      if (window.confirm(`¿Borrar la intersección «${n?.nombre}» y todas las calles que llegan a ella?`)) void ejecutar('DELETE', `/api/red/intersecciones/${id}`, undefined, 'Intersección borrada');
    } else {
      setSel({ tipo: 'nodo', id });
    }
  };

  const alClickVia = (id: string) => {
    setInfo('');
    if (herr === 'eliminar') {
      const v = local.vias.find((x) => x.id === id);
      if (window.confirm(`¿Borrar el tramo «${v?.nombre}»?`)) void ejecutar('DELETE', `/api/red/vias/${id}`, undefined, 'Tramo borrado');
    } else {
      setSel({ tipo: 'via', id });
    }
  };

  const alClickVacio = (x: number, y: number) => {
    if (herr === 'anadir') void ejecutar('POST', '/api/red/intersecciones', { nombre: 'Intersección nueva', x: Math.round(x), y: Math.round(y), semaforizada: true }, 'Intersección creada: conéctala con una calle para que se pueda simular');
    else if (herr === 'seleccionar') setSel(null);
  };

  const alMover = (id: string, x: number, y: number, fin: boolean) => {
    arrastrando.current = !fin;
    setLocal((r) => moverLocal(r, id, x, y));
    if (fin) void ejecutar('PUT', `/api/red/intersecciones/${id}`, { x: Math.round(x * 10) / 10, y: Math.round(y * 10) / 10 }, 'Intersección movida');
  };

  const leerArchivo = (ev: React.ChangeEvent<HTMLInputElement>, destino: 'red' | 'accidentes') => {
    const f = ev.target.files?.[0];
    ev.target.value = '';
    if (!f) return;
    void f.text().then(async (texto) => {
      if (destino === 'red') await ejecutar('POST', '/api/red/importar', texto, 'Red importada');
      else {
        try {
          const r = await api<{ total: number }>('POST', '/api/accidentes', { contenido: texto });
          setInfo(`Se importaron ${r.total} accidentes`);
          setError('');
        } catch (e) { setError(e instanceof Error ? e.message : 'Error'); }
      }
    });
  };

  const hint = HERRAMIENTAS.find((h) => h[0] === herr)![3];

  return (
    <div className="editor">
      <div className="herramientas" role="toolbar" aria-label="Herramientas del editor">
        {HERRAMIENTAS.map(([h, nombre, d]) => (
          <button key={h} className="herr" aria-pressed={herr === h} onClick={() => { setHerr(h); setOrigen(null); setConexion(null); }} title={nombre}>
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={d} /></svg>
            {nombre}
          </button>
        ))}
      </div>
      <div className="mapa-contenedor">
        <MapaTrafico
          ref={mapa} geo={geo} origen={local.origen} fuente={fuente} capas={CAPAS} editor seleccion={sel} marcados={origen ? [origen] : []} cursor={herr === 'seleccionar' ? 'grab' : 'crosshair'}
          onClickNodo={alClickNodo} onClickVia={alClickVia} onClickVacio={alClickVacio} onMoverNodo={alMover}
        />
        <div className="zoom">
          <button aria-label="Acercar" onClick={() => mapa.current?.acercar(1.4)}>+</button>
          <button aria-label="Alejar" onClick={() => mapa.current?.acercar(1 / 1.4)}>−</button>
          <button aria-label="Ajustar a la pantalla" onClick={() => mapa.current?.ajustar()}>⤢</button>
        </div>
        <div className="pista" role="status">{origen ? <><b>Origen elegido.</b> Ahora haz clic en la intersección de destino (o en la misma para cancelar).</> : hint}</div>
      </div>
      <aside className="lateral">
        <div className="cuerpo">
          <section>
            <h3>Red vial</h3>
            <p style={{ margin: '6px 0 0', color: 'var(--texto2)' }}><b style={{ color: 'var(--texto)' }}>{local.nombre}</b><br />{local.intersecciones.length} intersecciones · {local.vias.length} tramos</p>
          </section>
          {error && <div className="error" role="alert">{error}</div>}
          {info && !error && <div className="aviso" role="status">{info}</div>}
          {resultado && !resultado.simulable && <div className="aviso alerta" role="status"><b>La red no se puede simular todavía:</b> {resultado.problemas[0]}</div>}
          {resultado?.simulable && <div className="chip ok" style={{ alignSelf: 'flex-start' }}>✔ La red se puede simular y ya está en uso</div>}

          {conexion && (
            <FormularioCalle desde={local.intersecciones.find((n) => n.id === conexion.desde)!} hacia={local.intersecciones.find((n) => n.id === conexion.hacia)!}
              cancelar={() => setConexion(null)} crear={async (c) => { if (await ejecutar('POST', '/api/red/vias', c, 'Calle creada')) setConexion(null); }} />
          )}
          {nodo && !conexion && <FormularioNodo key={nodo.id} nodo={nodo} guardar={(c) => void ejecutar('PUT', `/api/red/intersecciones/${nodo.id}`, c, 'Intersección guardada')} />}
          {via && !conexion && (
            <section className="tarjeta" key={via.id}>
              <h2>Tramo</h2>
              <FormularioVia via={via} guardar={(c) => void ejecutar('PUT', `/api/red/vias/${via.id}`, c, 'Tramo guardado')} />
            </section>
          )}
          <section>
            <h3>Mapa real de Cali</h3>
            <div style={{ marginTop: 8 }}><ImportarMapa alTerminar={alMapaImportado} /></div>
          </section>
          <section>
            <h3>Importar archivos</h3>
            <div style={{ display: 'grid', gap: 10, marginTop: 8 }}>
              <label className="campo">Red de calles (JSON de SmartTraffic)
                <input type="file" accept=".json,application/json" onChange={(e) => leerArchivo(e, 'red')} aria-label="Importar red" />
                <small>Es el archivo que genera <code>./importar-cali.sh</code> con el mapa real de OpenStreetMap.</small>
              </label>
              <label className="campo">Accidentes (CSV o GeoJSON de datos abiertos)
                <input type="file" accept=".csv,.json,.geojson,text/csv,application/json" onChange={(e) => leerArchivo(e, 'accidentes')} aria-label="Importar accidentes" />
                <small>Con coordenadas (latitud y longitud). Se usan para los puntos críticos.</small>
              </label>
            </div>
          </section>
        </div>
      </aside>
    </div>
  );
}

function FormularioNodo({ nodo, guardar }: { nodo: NodoRed; guardar: (c: Record<string, unknown>) => void }): ReactElement {
  const [nombre, setNombre] = useState(nodo.nombre);
  const [tipo, setTipo] = useState<string>(nodo.tipo);
  const [sem, setSem] = useState(nodo.semaforizada);
  const [serv, setServ] = useState<string[]>(nodo.servicios);
  const alternar = (s: string) => setServ((a) => (a.includes(s) ? a.filter((x) => x !== s) : [...a, s]));
  return (
    <section className="tarjeta" style={{ display: 'grid', gap: 10 }}>
      <h2>Intersección</h2>
      <label className="campo">Nombre<input value={nombre} maxLength={200} onChange={(e) => setNombre(e.target.value)} /></label>
      <label className="campo">Papel
        <select value={tipo} onChange={(e) => setTipo(e.target.value)}>
          <option value="INTERIOR">Intersección interior</option>
          <option value="ENTRADA_SALIDA">Entrada y salida de vehículos</option>
          <option value="BASE_EMERGENCIAS">Base de emergencias</option>
        </select>
      </label>
      <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}><input type="checkbox" checked={sem} onChange={(e) => setSem(e.target.checked)} /> Tiene semáforo</label>
      <div className="campo">Servicios de emergencia que salen de aquí
        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
          {[['hospital', 'Hospital'], ['bomberos', 'Bomberos'], ['policia', 'Policía']].map(([k, t]) => (
            <label key={k} style={{ display: 'flex', gap: 5, alignItems: 'center' }}><input type="checkbox" checked={serv.includes(k)} onChange={() => alternar(k)} />{t}</label>
          ))}
        </div>
      </div>
      <button className="boton primario" onClick={() => guardar({ nombre, tipo, semaforizada: sem, servicios: serv })}>Guardar intersección</button>
    </section>
  );
}

function FormularioVia({ via, guardar }: { via: { nombre: string; velocidad: number; longitud: number; manual: boolean; desde: string; hacia: string }; guardar: (c: Record<string, unknown>) => void }): ReactElement {
  const [nombre, setNombre] = useState(via.nombre);
  const [vel, setVel] = useState(String(Math.round(via.velocidad)));
  const [largo, setLargo] = useState(via.manual ? String(Math.round(via.longitud)) : '');
  return (
    <div style={{ display: 'grid', gap: 10 }}>
      <label className="campo">Nombre<input value={nombre} maxLength={200} onChange={(e) => setNombre(e.target.value)} /></label>
      <label className="campo">Velocidad máxima (km/h)<input type="number" min={10} max={100} value={vel} onChange={(e) => setVel(e.target.value)} /></label>
      <label className="campo">Longitud (m)<input type="number" min={1} placeholder={`Automática: ${numero(via.longitud)} m`} value={largo} onChange={(e) => setLargo(e.target.value)} /><small>Déjalo vacío para usar la distancia del dibujo.</small></label>
      <button className="boton primario" onClick={() => guardar({ nombre, velocidad: Number(vel), longitud: largo === '' ? null : Number(largo) })}>Guardar tramo</button>
    </div>
  );
}

function FormularioCalle({ desde, hacia, crear, cancelar }: { desde: NodoRed; hacia: NodoRed; crear: (c: Record<string, unknown>) => Promise<void>; cancelar: () => void }): ReactElement {
  const [nombre, setNombre] = useState('Calle nueva');
  const [vel, setVel] = useState('40');
  const [doble, setDoble] = useState(true);
  return (
    <section className="tarjeta" style={{ display: 'grid', gap: 10 }} aria-label="Nueva calle">
      <h2>Nueva calle</h2>
      <p style={{ margin: 0, color: 'var(--texto2)' }}>{desde.nombre} → {hacia.nombre}</p>
      <label className="campo">Nombre<input value={nombre} maxLength={200} onChange={(e) => setNombre(e.target.value)} /></label>
      <label className="campo">Velocidad máxima (km/h)<input type="number" min={10} max={100} value={vel} onChange={(e) => setVel(e.target.value)} /></label>
      <label style={{ display: 'flex', gap: 8, alignItems: 'center' }}><input type="checkbox" checked={doble} onChange={(e) => setDoble(e.target.checked)} /> Doble sentido</label>
      <div className="acciones">
        <button className="boton primario" onClick={() => void crear({ desde: desde.id, hacia: hacia.id, nombre, velocidad: Number(vel), doble })}>Crear calle</button>
        <button className="boton" onClick={cancelar}>Cancelar</button>
      </div>
    </section>
  );
}
