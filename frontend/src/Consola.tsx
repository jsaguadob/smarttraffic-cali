import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { MutableRefObject, ReactElement } from 'react';
import { api, numero, reloj } from './api';
import { puntoEnVia } from './geometria';
import type { RedGeo } from './geometria';
import { ImportarMapa } from './ImportarMapa';
import { MapaTrafico } from './MapaTrafico';
import { PanelRuta, useNavegacion } from './Navegacion';
import type { CapasMapa, FuenteInstantanea, Hover, ManejadorMapa, PuntoMapa } from './MapaTrafico';
import type { Criticos, Instantanea, Usuario } from './tipos';

type Herramienta = 'ver' | 'accidente' | 'ambulancia' | 'bomberos' | 'policia' | 'semaforo' | 'onda';

const ICONOS: Record<Herramienta, string> = {
  ver: 'M5 3l14 8-6 2 3 6-3 1-3-6-5 4z',
  accidente: 'M12 3l10 18H2zM12 10v5M12 17.5v.5',
  ambulancia: 'M12 4v16M4 12h16',
  bomberos: 'M12 3c1 4 5 5 5 10a5 5 0 0 1-10 0c0-2 1-3 2-4 0 2 1 3 2 3 0-3-1-5 1-9z',
  policia: 'M12 3l8 3v6c0 5-3.500 8-8 9-4.500-1-8-4-8-9V6z',
  semaforo: 'M8 3h8v18H8zM12 7.500v.1M12 12v.1M12 16.500v.1',
  onda: 'M3 15c3-8 6 8 9 0s6 8 9 0',
};

const NOMBRES: Record<Herramienta, [string, string]> = {
  ver: ['Ver', 'V'],
  accidente: ['Accidente', 'A'],
  ambulancia: ['Ambulancia', 'E'],
  bomberos: ['Bomberos', 'B'],
  policia: ['Policía', 'P'],
  semaforo: ['Semáforo', 'S'],
  onda: ['Onda verde', 'O'],
};

const FASES = ['Verde horizontal', 'Amarillo horizontal', 'Todo en rojo', 'Verde vertical', 'Amarillo vertical', 'Todo en rojo'];
const GRAVEDAD = ['', 'leve', 'moderado', 'grave'];
const NIVELES = ['Fluido', 'Moderado', 'Alto', 'Crítico'];

function Icono({ d, relleno }: { d: string; relleno?: boolean }): ReactElement {
  return (
    <svg viewBox="0 0 24 24" fill={relleno ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={d} />
    </svg>
  );
}

interface Props {
  geo: RedGeo;
  fuente: MutableRefObject<FuenteInstantanea>;
  inst: Instantanea;
  usuario: Usuario;
  conectado: boolean;
  coordenadasReales: boolean;
  /** Se llama cuando el administrador termina de importar el mapa real: hay que recargar la red. */
  alRedImportada: () => void;
}

export function Consola({ geo, fuente, inst, usuario, conectado, coordenadasReales, alRedImportada }: Props): ReactElement {
  const esAdmin = usuario.rol === 'ADMINISTRADOR';
  const [verImportar, setVerImportar] = useState(false);
  const [bannerMin, setBannerMin] = useState(true); // el aviso del mapa de demostración arranca minimizado para no tapar el mapa
  const puedeOperar = usuario.rol !== 'ANALISTA';
  const mapa = useRef<ManejadorMapa>(null);
  const [herramienta, setHerramienta] = useState<Herramienta>('ver');
  const [gravedad, setGravedad] = useState(3);
  const [seleccion, setSeleccion] = useState<{ tipo: 'via' | 'nodo'; id: string } | null>(null);
  const [capas, setCapas] = useState<CapasMapa>({ base: true, vehiculos: true, congestion: true, semaforos: true, rutas: true, calor: false });
  const [marcados, setMarcados] = useState<string[]>([]);
  const [hover, setHover] = useState<Hover | null>(null);
  const [pestana, setPestana] = useState<'ruta' | 'resumen' | 'incidentes' | 'eventos'>('ruta');
  const nav = useNavegacion(geo, inst);
  const [mensaje, setMensaje] = useState<{ texto: string; tipo: 'ok' | 'error' } | null>(null);
  const [criticos, setCriticos] = useState<Criticos | null>(null);
  const [enfocar, setEnfocar] = useState<{ x: number; y: number; k?: number; n: number } | null>(null);
  const [vpmLocal, setVpmLocal] = useState<number | null>(null);
  const contador = useRef(0);

  useEffect(() => {
    if (!coordenadasReales) { setCriticos(null); return; }
    api<Criticos>('GET', '/api/accidentes/criticos?top=10').then(setCriticos).catch(() => setCriticos(null));
  }, [geo, coordenadasReales]);

  const avisar = useCallback((texto: string, tipo: 'ok' | 'error' = 'ok') => {
    setMensaje({ texto, tipo });
    window.setTimeout(() => setMensaje((m) => (m?.texto === texto ? null : m)), 4200);
  }, []);

  const llamar = useCallback(async (metodo: string, ruta: string, cuerpo?: unknown, ok?: string) => {
    try {
      await api(metodo, ruta, cuerpo);
      if (ok) avisar(ok);
    } catch (e) {
      avisar(e instanceof Error ? e.message : 'Error', 'error');
    }
  }, [avisar]);

  const control = (accion: string, extra: Record<string, unknown> = {}) => llamar('POST', '/api/sim/control', { accion, ...extra });
  const config = (c: Record<string, unknown>) => llamar('POST', '/api/sim/config', c);

  // ---------------------------------------------------------------- teclado
  useEffect(() => {
    const tecla = (ev: KeyboardEvent) => {
      const t = ev.target as HTMLElement;
      if (['INPUT', 'SELECT', 'TEXTAREA'].includes(t.tagName) || ev.ctrlKey || ev.metaKey || ev.altKey) return;
      const mapaTeclas: Record<string, Herramienta> = { v: 'ver', a: 'accidente', e: 'ambulancia', b: 'bomberos', p: 'policia', s: 'semaforo', o: 'onda' };
      const h = mapaTeclas[ev.key.toLowerCase()];
      if (h && (puedeOperar || h === 'ver')) { setHerramienta(h); setMarcados([]); return; }
      if (ev.key === 'Escape') { setHerramienta('ver'); setMarcados([]); setSeleccion(null); }
      if (ev.key === ' ' && puedeOperar) { ev.preventDefault(); void control(fuente.current.inst?.corriendo ? 'pausar' : 'iniciar'); }
    };
    window.addEventListener('keydown', tecla);
    return () => window.removeEventListener('keydown', tecla);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [puedeOperar]);

  // ---------------------------------------------------------------- clics en el mapa
  const alClickVia = (viaId: string, pos: number) => {
    const g = geo.vias[geo.indiceVia.get(viaId) ?? -1];
    if (!g) return;
    if (herramienta === 'accidente') {
      void llamar('POST', '/api/sim/incidentes', { via: viaId, fraccion: pos / g.via.longitud, gravedad }, `Accidente ${GRAVEDAD[gravedad]} registrado en ${g.via.nombre}`);
    } else {
      setSeleccion({ tipo: 'via', id: viaId });
    }
  };

  const alClickNodo = (nodoId: string) => {
    const nodo = geo.nodos[geo.indiceNodo.get(nodoId) ?? -1];
    if (!nodo) return;
    if (herramienta === 'ambulancia' || herramienta === 'bomberos' || herramienta === 'policia') {
      const tipo = herramienta === 'ambulancia' ? 'AMBULANCIA' : herramienta === 'bomberos' ? 'BOMBEROS' : 'POLICIA';
      void llamar('POST', '/api/sim/emergencias', { tipo, destino: nodoId }, `${NOMBRES[herramienta][0]} en camino a ${nodo.nombre}`);
    } else if (herramienta === 'onda') {
      if (!nodo.semaforizada) { avisar('Esa intersección no tiene semáforo', 'error'); return; }
      setMarcados((m) => (m.includes(nodoId) ? m.filter((x) => x !== nodoId) : [...m, nodoId]));
    } else {
      setSeleccion({ tipo: 'nodo', id: nodoId });
    }
  };

  const enfocarVia = (indice: number) => {
    const g = geo.vias[indice];
    if (!g) return;
    const p = puntoEnVia(g, g.via.longitud / 2);
    setEnfocar({ x: p.x, y: p.y, k: 1.8, n: ++contador.current });
    setSeleccion({ tipo: 'via', id: g.via.id });
  };

  const enfocarNodo = (id: string) => {
    const n = geo.nodos[geo.indiceNodo.get(id) ?? -1];
    if (!n) return;
    setEnfocar({ x: n.x, y: n.y, k: 1.8, n: ++contador.current });
    setSeleccion({ tipo: 'nodo', id });
  };

  // ---------------------------------------------------------------- datos derivados
  const niveles = useMemo(() => new Map(inst.c.map((c) => [c[0], c])), [inst.c]);
  const puntosCriticos: PuntoMapa[] = useMemo(
    () => (criticos?.ranking ?? []).flatMap((c) => {
      const n = geo.nodos[geo.indiceNodo.get(c.id) ?? -1];
      return n ? [{ x: n.x, y: n.y, nombre: c.nombre, puntaje: c.puntaje }] : [];
    }),
    [criticos, geo],
  );
  const semaforoDe = (nodoId: string) => {
    const i = geo.indiceNodo.get(nodoId);
    return i === undefined ? undefined : inst.s.find((s) => s[0] === i);
  };

  const pista: Record<Herramienta, string> = {
    ver: 'Haz clic en una calle o intersección para ver sus datos. Arrastra para mover el mapa; la rueda acerca y aleja.',
    accidente: `Haz clic sobre una calle para provocar un accidente ${GRAVEDAD[gravedad]}: la calle se bloquea y salen los equipos de emergencia.`,
    ambulancia: 'Haz clic en una intersección: la ambulancia sale del hospital con ruta prioritaria (línea azul).',
    bomberos: 'Haz clic en una intersección: los bomberos salen de su estación con ruta prioritaria.',
    policia: 'Haz clic en una intersección: la patrulla sale de su estación con ruta prioritaria.',
    semaforo: 'Haz clic en una intersección con semáforo para fijar su verde o devolverlo al control automático.',
    onda: 'Marca en orden las intersecciones de una avenida y pulsa «Aplicar onda verde» para sincronizar sus semáforos.',
  };

  const sel = seleccion;
  const selVia = sel?.tipo === 'via' ? geo.vias[geo.indiceVia.get(sel.id) ?? -1] : undefined;
  const selNodo = sel?.tipo === 'nodo' ? geo.nodos[geo.indiceNodo.get(sel.id) ?? -1] : undefined;
  const k = inst.kpi;
  const cursor = herramienta === 'ver' && !nav.eligiendo ? 'grab' : 'crosshair';
  const vpm = vpmLocal ?? inst.vpm;

  const tooltip = (() => {
    if (!hover) return null;
    if (hover.tipo === 'via') {
      const g = geo.vias[geo.indiceVia.get(hover.id) ?? -1];
      if (!g) return null;
      const c = niveles.get(g.indice);
      return { titulo: g.via.nombre, lineas: [`Límite ${numero(g.via.velocidad)} km/h · ${numero(g.via.longitud)} m${g.unSentido ? ' · un sentido' : ''}`, c ? `${NIVELES[c[1]]} · ${c[2]} vehículos${c[3] ? ' · BLOQUEADA' : ''}` : 'Fluido · sin vehículos'] };
    }
    const n = geo.nodos[geo.indiceNodo.get(hover.id) ?? -1];
    if (!n) return null;
    const s = semaforoDe(n.id);
    return { titulo: n.nombre, lineas: [n.servicios.length ? 'Base de emergencias: ' + n.servicios.join(', ') : n.tipo === 'ENTRADA_SALIDA' ? 'Entrada y salida de la zona' : n.semaforizada ? 'Intersección con semáforo' : 'Intersección sin semáforo', s ? FASES[s[1]] : ''].filter(Boolean) };
  })();

  const herramientasLista: Herramienta[] = ['ver', 'accidente', 'ambulancia', 'bomberos', 'policia', 'semaforo', 'onda'];

  return (
    <div className="consola">
      <div className="barra-control" role="toolbar" aria-label="Control de la simulación">
        <div className="grupo">
          <button className="boton primario" disabled={!puedeOperar} onClick={() => void control(inst.corriendo ? 'pausar' : 'iniciar')} aria-label={inst.corriendo ? 'Pausar' : 'Iniciar'}>
            {inst.corriendo ? '⏸ Pausar' : '▶ Iniciar'}
          </button>
          <span className="reloj" aria-label="Tiempo simulado">{reloj(inst.t)}</span>
          <button className="boton" disabled={!puedeOperar} onClick={() => void control('reiniciar')}>↺ Reiniciar</button>
        </div>
        <div className="grupo">
          <label>Velocidad</label>
          <div className="segmentos" role="group" aria-label="Velocidad de la simulación">
            {[1, 2, 4, 8, 16].map((v) => (
              <button key={v} aria-pressed={inst.velocidad === v} disabled={!puedeOperar} onClick={() => void config({ velocidad: v })}>{v}x</button>
            ))}
          </div>
        </div>
        <div className="grupo">
          <label>Semáforos</label>
          <div className="segmentos" role="group" aria-label="Modo de los semáforos">
            <button aria-pressed={inst.modo === 'FIJO'} disabled={!puedeOperar} onClick={() => void config({ modo: 'FIJO' })}>Tiempo fijo</button>
            <button aria-pressed={inst.modo === 'INTELIGENTE'} disabled={!puedeOperar} onClick={() => void config({ modo: 'INTELIGENTE' })}>Inteligente</button>
          </div>
        </div>
        <div className="grupo">
          <label>Rutas</label>
          <div className="segmentos" role="group" aria-label="Reruteo dinámico">
            <button aria-pressed={!inst.reruteo} disabled={!puedeOperar} onClick={() => void config({ reruteo: false })}>Fijas</button>
            <button aria-pressed={inst.reruteo} disabled={!puedeOperar} onClick={() => void config({ reruteo: true })}>Alternas</button>
          </div>
        </div>
        <div className="grupo deslizador">
          <label htmlFor="vpm">Tráfico</label>
          <input
            id="vpm" type="range" min={0} max={200} step={5} value={vpm} disabled={!puedeOperar}
            onChange={(e) => setVpmLocal(Number(e.target.value))}
            onPointerUp={() => { if (vpmLocal !== null) { void config({ vehiculosPorMinuto: vpmLocal }); setVpmLocal(null); } }}
            onKeyUp={() => { if (vpmLocal !== null) { void config({ vehiculosPorMinuto: vpmLocal }); setVpmLocal(null); } }}
          />
          <b>{vpm} /min</b>
        </div>
        <div className="grupo">
          <button className="boton" disabled={!puedeOperar} onClick={() => void llamar('POST', '/api/sim/vehiculos', { cantidad: 50 }, 'Se agregaron 50 vehículos')}>+ 50 vehículos</button>
          <button className="boton" disabled={!puedeOperar} onClick={() => { void config({ vehiculosPorMinuto: 160 }); void llamar('POST', '/api/sim/vehiculos', { cantidad: 80 }, 'Hora pico: más vehículos entrando'); }}>Hora pico</button>
        </div>
        {!puedeOperar && <span className="chip alerta">Solo lectura (analista)</span>}
      </div>

      <div className="herramientas" role="toolbar" aria-label="Herramientas">
        {herramientasLista.map((h) => (
          <button key={h} className="herr" aria-pressed={herramienta === h} disabled={!puedeOperar && h !== 'ver'} title={`${NOMBRES[h][0]} (${NOMBRES[h][1]})`} onClick={() => { setHerramienta(h); setMarcados([]); }}>
            <Icono d={ICONOS[h]} />
            {NOMBRES[h][0]}
            <kbd>{NOMBRES[h][1]}</kbd>
          </button>
        ))}
        {herramienta === 'accidente' && (
          <div className="segmentos" style={{ flexDirection: 'column' }} role="group" aria-label="Gravedad del accidente">
            {[1, 2, 3].map((g) => (
              <button key={g} aria-pressed={gravedad === g} onClick={() => setGravedad(g)} title={`Accidente ${GRAVEDAD[g]}`}>{GRAVEDAD[g].slice(0, 3)}</button>
            ))}
          </div>
        )}
      </div>

      <div className="mapa-contenedor">
        <MapaTrafico
          ref={mapa} geo={geo} origen={geo.red.origen} fuente={fuente} capas={capas} ruta={nav.rutaMapa} modoElegir={nav.eligiendo !== null} onClickMapa={nav.alClickMapa} calor={criticos?.calor} criticos={puntosCriticos} seleccion={seleccion} marcados={marcados} cursor={cursor} enfocar={enfocar}
          onClickVia={alClickVia} onClickNodo={alClickNodo} onClickVacio={() => { if (herramienta === 'ver') setSeleccion(null); }} onHover={setHover}
        />
        {!conectado && <div className="desconectado" role="alert">Reconectando con el servidor…</div>}
        {tooltip && hover && (
          <div className="tooltip" style={{ left: Math.min(hover.px + 14, 9999), top: hover.py + 14 }}>
            <b>{tooltip.titulo}</b>
            {tooltip.lineas.map((l) => <span key={l}>{l}</span>)}
          </div>
        )}
        <div className="zoom">
          <button aria-label="Acercar" onClick={() => mapa.current?.acercar(1.4)}>+</button>
          <button aria-label="Alejar" onClick={() => mapa.current?.acercar(1 / 1.4)}>−</button>
          <button aria-label="Ajustar a la pantalla" onClick={() => mapa.current?.ajustar()}>⤢</button>
        </div>
        {capas.base && geo.red.origen && <div className="atribucion">© colaboradores de OpenStreetMap</div>}
        {esAdmin && !coordenadasReales && bannerMin && (
          <button className="chip-mapa" onClick={() => setBannerMin(false)} aria-label="Red de demostración: abrir opciones para usar el mapa real">
            <span aria-hidden="true">ⓘ</span> Red de demostración · usar el mapa real
          </button>
        )}
        {esAdmin && !coordenadasReales && !bannerMin && (
          <div className="banner-mapa" role="region" aria-label="Mapa de demostración">
            <div className="banner-cabeza">
              <b>Estás viendo una red de demostración (ficticia)</b>
              <button className="boton chico" onClick={() => setBannerMin(true)} aria-label="Minimizar el aviso">Minimizar ✕</button>
            </div>
            <p>Para ver las calles reales de Cali, el servidor puede descargar el mapa de OpenStreetMap.</p>
            {verImportar ? <ImportarMapa alTerminar={() => { alRedImportada(); avisar('Mapa cargado: ahora ves la red de calles importada.'); }} /> : <button className="boton primario" onClick={() => setVerImportar(true)}>Usar el mapa real de Cali</button>}
          </div>
        )}
        <div className="leyenda-mapa" aria-label="Capas del mapa">
          {geo.red.origen && <label><input type="checkbox" checked={capas.base} onChange={(e) => setCapas({ ...capas, base: e.target.checked })} /> Mapa base</label>}
          <label><input type="checkbox" checked={capas.congestion} onChange={(e) => setCapas({ ...capas, congestion: e.target.checked })} /> Tráfico</label>
          <span><i style={{ background: '#34a853' }} />Fluido <i style={{ background: '#fbbc04' }} />Lento</span>
          <span><i style={{ background: '#f57c00' }} />Denso <i style={{ background: '#d93025' }} />Detenido</span>
          <label><input type="checkbox" checked={capas.vehiculos} onChange={(e) => setCapas({ ...capas, vehiculos: e.target.checked })} /> Vehículos</label>
          <label><input type="checkbox" checked={capas.semaforos} onChange={(e) => setCapas({ ...capas, semaforos: e.target.checked })} /> Semáforos</label>
          <label><input type="checkbox" checked={capas.rutas} onChange={(e) => setCapas({ ...capas, rutas: e.target.checked })} /> Rutas de emergencia</label>
          {criticos && criticos.total > 0 && <label><input type="checkbox" checked={capas.calor} onChange={(e) => setCapas({ ...capas, calor: e.target.checked })} /> Accidentalidad histórica</label>}
        </div>
        {nav.banner && (
          <div className="guia" role="status" aria-live="polite" data-testid="guia">
            <div className="flecha" aria-hidden="true">{nav.banner.flecha}</div>
            <div><b>{nav.banner.titulo}</b><span>{nav.banner.detalle}</span></div>
          </div>
        )}
        <div className="pista" role="status">
          {nav.eligiendo ? <b>Haz clic en el mapa para elegir {nav.eligiendo === 'origen' ? 'el origen' : 'el destino'}.</b> : mensaje ? <b style={{ color: mensaje.tipo === 'error' ? '#d93025' : '#188038' }}>{mensaje.texto}</b> : <>{pista[herramienta]}</>}
          {herramienta === 'onda' && marcados.length >= 2 && puedeOperar && (
            <> <button className="boton chico primario" style={{ pointerEvents: 'auto', marginLeft: 8 }} onClick={() => { void llamar('POST', '/api/sim/semaforos/sincronizar', { nodos: marcados }, 'Onda verde aplicada'); setMarcados([]); }}>Aplicar onda verde ({marcados.length})</button></>
          )}
        </div>
      </div>

      <aside className="lateral" aria-label="Paneles">
        <div className="tabs" role="tablist">
          {(['ruta', 'resumen', 'incidentes', 'eventos'] as const).map((p) => (
            <button key={p} role="tab" aria-selected={pestana === p} onClick={() => setPestana(p)}>
              {p === 'ruta' ? 'Cómo llegar' : p === 'resumen' ? 'Resumen' : p === 'incidentes' ? `Incidentes${inst.i.length ? ` (${inst.i.length})` : ''}` : 'Eventos'}
            </button>
          ))}
        </div>
        <div className="cuerpo">
          {pestana === 'ruta' && <PanelRuta nav={nav} esReal={coordenadasReales} />}
          {pestana === 'resumen' && (
            <>
              {(selVia || selNodo) && (
                <section className="tarjeta detalle" aria-label="Elemento seleccionado">
                  {selVia && (() => {
                    const c = niveles.get(selVia.indice);
                    return (
                      <>
                        <h2>{selVia.via.nombre}</h2>
                        <dl>
                          <dt>Límite</dt><dd>{numero(selVia.via.velocidad)} km/h</dd>
                          <dt>Longitud</dt><dd>{numero(selVia.via.longitud)} m {selVia.unSentido ? '· un sentido' : ''}</dd>
                          <dt>Estado</dt><dd>{c ? NIVELES[c[1]] : 'Fluido'}{c?.[3] ? ' · BLOQUEADA' : ''}</dd>
                          <dt>Vehículos</dt><dd>{c ? c[2] : 0}</dd>
                        </dl>
                        {puedeOperar && <button className="boton chico peligro" onClick={() => void llamar('POST', '/api/sim/incidentes', { via: selVia.via.id, fraccion: 0.5, gravedad }, 'Accidente registrado')}>Provocar accidente aquí</button>}
                      </>
                    );
                  })()}
                  {selNodo && (() => {
                    const s = semaforoDe(selNodo.id);
                    return (
                      <>
                        <h2>{selNodo.nombre}</h2>
                        <dl>
                          <dt>Tipo</dt><dd>{selNodo.servicios.length ? 'Base: ' + selNodo.servicios.join(', ') : selNodo.tipo === 'ENTRADA_SALIDA' ? 'Entrada y salida' : 'Intersección'}</dd>
                          <dt>Semáforo</dt><dd>{s ? FASES[s[1]] + (s[2] >= 0 ? ' (fijado por el operador)' : '') : selNodo.semaforizada ? 'Sin cruce que controlar' : 'No tiene'}</dd>
                        </dl>
                        {s && puedeOperar && (
                          <div className="acciones">
                            <button className="boton chico" onClick={() => void llamar('POST', `/api/sim/semaforos/${selNodo.id}`, { grupo: 0 })}>Fijar verde horizontal</button>
                            <button className="boton chico" onClick={() => void llamar('POST', `/api/sim/semaforos/${selNodo.id}`, { grupo: 1 })}>Fijar verde vertical</button>
                            <button className="boton chico" onClick={() => void llamar('POST', `/api/sim/semaforos/${selNodo.id}`, { grupo: -1 })}>Automático</button>
                          </div>
                        )}
                      </>
                    );
                  })()}
                </section>
              )}
              <section>
                <h3>Estado de la red</h3>
                <dl className="metricas">
                  <div><dt>Vehículos</dt><dd data-testid="kpi-activos">{k.activos}</dd></div>
                  <div><dt>Esperando entrar</dt><dd>{k.enEspera}</dd></div>
                  <div><dt>Velocidad media</dt><dd>{numero(k.velocidadKmh, 1)} km/h</dd></div>
                  <div className={k.congestionadas > 5 ? 'peligro' : k.congestionadas > 0 ? 'alerta' : ''}><dt>Calles congestionadas</dt><dd>{k.congestionadas}</dd></div>
                  <div><dt>Semáforos</dt><dd>{k.semaforos}</dd></div>
                  <div><dt>Emergencias activas</dt><dd>{k.emergencias}</dd></div>
                  <div><dt>Viaje medio</dt><dd>{numero(k.viajeMedio, 0)} s</dd></div>
                  <div><dt>Espera media</dt><dd>{numero(k.esperaMedia, 0)} s</dd></div>
                  <div><dt>Viajes completados</dt><dd>{numero(k.completados)}</dd></div>
                  <div><dt>Cambios de ruta</dt><dd>{numero(k.reruteos)}</dd></div>
                </dl>
              </section>
              <section>
                <h3>Calles más congestionadas</h3>
                {inst.rk.length === 0 ? <p className="vacio-lateral">Sin congestión por ahora.</p> : (
                  <ul className="lista" style={{ marginTop: 8 }}>
                    {inst.rk.map((r) => (
                      <li key={r.via}>
                        <div>
                          <button className="enlace" onClick={() => enfocarVia(r.via)}>{r.nombre}</button>
                          <small>{r.vehiculos} vehículos</small>
                          <div className="barra-nivel"><i style={{ width: `${Math.round(r.puntaje * 100)}%`, background: r.puntaje > 0.75 ? '#d93025' : r.puntaje > 0.5 ? '#f57c00' : '#fbbc04' }} /></div>
                        </div>
                      </li>
                    ))}
                  </ul>
                )}
              </section>
              {criticos && criticos.ranking.length > 0 && (
                <section>
                  <h3>Puntos críticos de accidentes (datos abiertos)</h3>
                  <ul className="lista" style={{ marginTop: 8 }}>
                    {criticos.ranking.slice(0, 6).map((c, i) => (
                      <li key={c.id}>
                        <b>{i + 1}</b>
                        <div><button className="enlace" onClick={() => enfocarNodo(c.id)}>{c.nombre}</button><small>{c.accidentes} accidentes · puntaje {c.puntaje}</small></div>
                      </li>
                    ))}
                  </ul>
                  <p className="vacio-lateral" style={{ padding: '6px 0' }}>{criticos.asignados} de {criticos.total} accidentes cayeron dentro de la zona del mapa.</p>
                </section>
              )}
              {!coordenadasReales && <p className="aviso">Estás viendo la red de demostración ficticia. Con el mapa real de Cali importado verás aquí los puntos críticos de accidentalidad.</p>}
            </>
          )}
          {pestana === 'incidentes' && (
            inst.i.length === 0 ? <p className="vacio-lateral">No hay incidentes abiertos. Usa la herramienta «Accidente» para provocar uno.</p> : (
              <ul className="lista">
                {inst.i.map((i) => (
                  <li key={i.id}>
                    <div>
                      <b>{i.id}</b> <span className={'chip ' + (i.gravedad === 3 ? 'peligro' : i.gravedad === 2 ? 'alerta' : '')}>{GRAVEDAD[i.gravedad]}</span>
                      <button className="enlace" style={{ display: 'block' }} onClick={() => enfocarVia(i.via)}>{geo.vias[i.via]?.via.nombre}</button>
                      <small>{i.estado === 'EN_ATENCION' ? 'Equipo atendiendo' : 'Esperando equipo'} · se despeja en {Math.max(0, Math.round(i.restante))} s</small>
                    </div>
                    {puedeOperar && <button className="boton chico" onClick={() => void llamar('DELETE', `/api/sim/incidentes/${i.id}`, undefined, `${i.id} despejado`)}>Despejar</button>}
                  </li>
                ))}
              </ul>
            )
          )}
          {pestana === 'eventos' && (
            inst.ev.length === 0 ? <p className="vacio-lateral">Todavía no hay eventos.</p> : (
              <div>
                {[...inst.ev].reverse().map((e, i) => (
                  <div className={'evento ' + e.tipo} key={`${e.t}-${i}`}><time>{reloj(e.t)}</time><span className="msg">{e.msg}</span></div>
                ))}
              </div>
            )
          )}
        </div>
      </aside>
    </div>
  );
}
