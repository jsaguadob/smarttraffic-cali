import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { api, numero } from './api';
import { largoPolilinea, puntoEnPolilinea } from './geometria';
import type { RedGeo } from './geometria';
import type { RutaMapa } from './MapaTrafico';
import { aMetros } from './teselas';
import type { Instantanea, Lugar, OpcionRuta, RespuestaLugares, RespuestaRuta } from './tipos';

export type Campo = 'origen' | 'destino';

export function fmtTiempo(s: number): string {
  if (s < 60) return `${Math.max(1, Math.round(s))} s`;
  const m = Math.round(s / 60);
  return m < 60 ? `${m} min` : `${Math.floor(m / 60)} h ${m % 60} min`;
}

export function fmtDist(m: number): string {
  return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${numero(m / 1000, 1)} km`;
}

const FLECHA: Record<string, string> = { salida: '↑', recto: '↑', derecha_suave: '↗', derecha: '→', derecha_fuerte: '↘', izquierda_suave: '↖', izquierda: '←', izquierda_fuerte: '↙', vuelta: '↩', llegada: '⚑' };

export interface Banner {
  flecha: string;
  titulo: string;
  detalle: string;
  llego: boolean;
}

export interface Navegacion {
  origen: Lugar | null;
  destino: Lugar | null;
  opciones: OpcionRuta[] | null;
  sel: number;
  cargando: boolean;
  error: string;
  aviso: string;
  eligiendo: Campo | null;
  guiando: boolean;
  banner: Banner | null;
  rutaMapa: RutaMapa | null;
  fijar: (c: Campo, l: Lugar | null) => void;
  elegirOpcion: (i: number) => void;
  eligiendoEnMapa: (c: Campo | null) => void;
  alClickMapa: (x: number, y: number) => void;
  calcular: () => void;
  limpiar: () => void;
  intercambiar: () => void;
  iniciarGuia: () => void;
  detenerGuia: () => void;
  usarMiUbicacion: () => void;
}

/** Estado y lógica de la navegación: buscar, calcular rutas, y guiar un vehículo por la ruta según el tiempo simulado. */
export function useNavegacion(geo: RedGeo, inst: Instantanea): Navegacion {
  const [origen, setOrigen] = useState<Lugar | null>(null);
  const [destino, setDestino] = useState<Lugar | null>(null);
  const [opciones, setOpciones] = useState<OpcionRuta[] | null>(null);
  const [sel, setSel] = useState(0);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');
  const [aviso, setAviso] = useState('');
  const [eligiendo, setEligiendo] = useState<Campo | null>(null);
  const [guia, setGuia] = useState<{ t0: number } | null>(null);
  const [llego, setLlego] = useState(false);
  const alertados = useRef(new Set<string>());
  const instRef = useRef(inst);
  instRef.current = inst;

  const pedir = useCallback(async (o: Lugar, d: Lugar, mantenerGuia: boolean) => {
    setCargando(true);
    setError('');
    try {
      const r = await api<RespuestaRuta>('POST', '/api/ruta', { origen: { x: o.x, y: o.y }, destino: { x: d.x, y: d.y }, alternativas: 2 });
      setOpciones(r.opciones);
      setSel(0);
      setLlego(false);
      if (mantenerGuia) setGuia({ t0: instRef.current.t });
    } catch (e) {
      setOpciones(null);
      setGuia(null);
      setError(e instanceof Error ? e.message : 'No se pudo calcular la ruta');
    } finally {
      setCargando(false);
    }
  }, []);

  const calcular = useCallback(() => {
    if (!origen || !destino) { setError('Elige el origen y el destino.'); return; }
    setAviso('');
    setGuia(null);
    void pedir(origen, destino, false);
  }, [origen, destino, pedir]);

  const rutaSel = opciones ? opciones[Math.min(sel, opciones.length - 1)] : null;
  const largo = useMemo(() => (rutaSel ? largoPolilinea(rutaSel.geometria) : 0), [rutaSel]);

  // progreso de la guía (0 a 1) según el tiempo simulado
  const progreso = guia && rutaSel ? Math.max(0, Math.min(1, (inst.t - guia.t0) / Math.max(1, rutaSel.tiempoActual))) : 0;

  // llegada
  useEffect(() => {
    if (guia && rutaSel && progreso >= 1) { setGuia(null); setLlego(true); }
  }, [guia, rutaSel, progreso]);

  // un accidente en la ruta mientras se navega: se avisa y se recalcula desde donde va el vehículo
  useEffect(() => {
    if (!guia || !rutaSel || !destino) return;
    for (const i of inst.i) {
      const via = geo.vias[i.via]?.via;
      if (!via || alertados.current.has(i.id) || !rutaSel.vias.includes(via.id)) continue;
      alertados.current.add(i.id);
      const q = puntoEnPolilinea(rutaSel.geometria, progreso * largo);
      setAviso(`Hay un accidente en tu ruta (${via.nombre}). Buscando otro camino…`);
      void pedir({ tipo: 'ubicacion', nombre: 'Tu posición', x: q.x, y: q.y }, destino, true);
      return;
    }
  }, [inst.i, guia, rutaSel, destino, geo, progreso, largo, pedir]);

  const banner: Banner | null = useMemo(() => {
    if (llego) return { flecha: '⚑', titulo: 'Has llegado a tu destino', detalle: destino?.nombre ?? '', llego: true };
    if (!guia || !rutaSel) return null;
    const recorrido = progreso * largo;
    let inicio = 0;
    let siguiente = rutaSel.pasos[rutaSel.pasos.length - 1];
    let aInicio = largo;
    for (const p of rutaSel.pasos) {
      if (inicio > recorrido + 1) { siguiente = p; aInicio = inicio; break; }
      inicio += p.distancia;
    }
    const falta = Math.max(0, aInicio - recorrido);
    return {
      flecha: FLECHA[siguiente.giro] ?? '↑',
      titulo: siguiente.giro === 'llegada' ? `En ${fmtDist(falta)}: llegas a tu destino` : `En ${fmtDist(falta)}: ${siguiente.texto}`,
      detalle: `Quedan ${fmtTiempo(rutaSel.tiempoActual * (1 - progreso))} · ${fmtDist(largo * (1 - progreso))}`,
      llego: false,
    };
  }, [guia, rutaSel, progreso, largo, llego, destino]);

  const rutaMapa: RutaMapa | null = useMemo(() => {
    if (!opciones || opciones.length === 0) return null;
    const g = rutaSel!.geometria;
    return {
      opciones: opciones.map((o, i) => ({ pts: o.geometria, sel: i === sel })),
      origen: { x: g[0], y: g[1] },
      destino: { x: g[g.length - 2], y: g[g.length - 1] },
      guia: guia && rutaSel ? { pts: rutaSel.geometria, t0: guia.t0, duracion: Math.max(1, rutaSel.tiempoActual) } : null,
    };
  }, [opciones, sel, guia, rutaSel]);

  return {
    origen, destino, opciones, sel, cargando, error, aviso, eligiendo, guiando: guia !== null, banner, rutaMapa,
    fijar: (c, l) => { (c === 'origen' ? setOrigen : setDestino)(l); setOpciones(null); setGuia(null); setLlego(false); setError(''); },
    elegirOpcion: (i) => { setSel(i); setGuia(null); setLlego(false); },
    eligiendoEnMapa: setEligiendo,
    alClickMapa: (x, y) => {
      if (!eligiendo) return;
      const lugar: Lugar = { tipo: 'mapa', nombre: 'Punto en el mapa', x, y };
      (eligiendo === 'origen' ? setOrigen : setDestino)(lugar);
      setOpciones(null); setGuia(null); setLlego(false); setError('');
      setEligiendo(null);
    },
    calcular,
    limpiar: () => { setOrigen(null); setDestino(null); setOpciones(null); setGuia(null); setLlego(false); setError(''); setAviso(''); setEligiendo(null); alertados.current.clear(); },
    intercambiar: () => { setOrigen(destino); setDestino(origen); setOpciones(null); setGuia(null); setLlego(false); },
    iniciarGuia: () => { setAviso(''); setLlego(false); setGuia({ t0: instRef.current.t }); },
    detenerGuia: () => { setGuia(null); setLlego(false); },
    usarMiUbicacion: () => {
      const o = geo.red.origen;
      if (!o) { setError('Para usar tu ubicación hace falta el mapa real.'); return; }
      if (!navigator.geolocation) { setError('Tu navegador no permite obtener la ubicación.'); return; }
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const m = aMetros(o, pos.coords.latitude, pos.coords.longitude);
          const c = geo.caja;
          if (m.x < c.minX - 300 || m.x > c.maxX + 300 || m.y < c.minY - 300 || m.y > c.maxY + 300) { setError('Estás fuera de la zona del mapa cargado.'); return; }
          setOrigen({ tipo: 'ubicacion', nombre: 'Tu ubicación', x: m.x, y: m.y, lat: pos.coords.latitude, lon: pos.coords.longitude });
          setOpciones(null); setError('');
        },
        () => setError('No se pudo obtener tu ubicación (revisa el permiso del navegador).'),
        { enableHighAccuracy: true, timeout: 10000 },
      );
    },
  };
}

// ---------------------------------------------------------------------------------------------- campo con sugerencias

function CampoLugar({ letra, etiqueta, valor, esReal, alElegir, alMapa, extra }: { letra: 'A' | 'B'; etiqueta: string; valor: Lugar | null; esReal: boolean; alElegir: (l: Lugar | null) => void; alMapa: () => void; extra?: ReactElement }): ReactElement {
  const id = useId();
  const [texto, setTexto] = useState('');
  const [sug, setSug] = useState<RespuestaLugares | null>(null);
  const [abierto, setAbierto] = useState(false);
  const [activo, setActivo] = useState(0);
  const [buscando, setBuscando] = useState(false);
  const temporizador = useRef<number | undefined>(undefined);

  useEffect(() => { setTexto(valor?.nombre ?? ''); }, [valor]);

  const buscar = (q: string, externo: boolean) => {
    window.clearTimeout(temporizador.current);
    if (q.trim().length < 2) { setSug(null); return; }
    const hacer = async () => {
      if (externo) setBuscando(true);
      try {
        setSug(await api<RespuestaLugares>('GET', `/api/lugares?q=${encodeURIComponent(q.trim())}${externo ? '&externo=1' : ''}`));
        setActivo(0);
      } catch { setSug(null); } finally { setBuscando(false); }
    };
    if (externo) void hacer(); else temporizador.current = window.setTimeout(() => void hacer(), 200);
  };

  const todas: Lugar[] = [...(sug?.externos ?? []), ...(sug?.locales ?? [])];
  const elegir = (l: Lugar) => { setTexto(l.nombre); setAbierto(false); setSug(null); alElegir(l); };
  const etiquetaTipo = (t: Lugar['tipo']) => (t === 'calle' ? 'Calle' : t === 'interseccion' ? 'Intersección' : t === 'servicio' ? 'Emergencias' : 'Lugar');

  return (
    <div className="ruta-campo">
      <span className={'punto ' + (letra === 'A' ? 'a' : 'b')} aria-hidden="true">{letra}</span>
      <input
        id={id} aria-label={etiqueta} placeholder={etiqueta} value={texto} autoComplete="off" role="combobox" aria-expanded={abierto && todas.length > 0} aria-controls={id + '-lista'}
        onChange={(e) => { setTexto(e.target.value); if (valor) alElegir(null); setAbierto(true); buscar(e.target.value, false); }}
        onFocus={() => setAbierto(true)}
        onBlur={() => window.setTimeout(() => setAbierto(false), 160)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') { e.preventDefault(); setActivo((a) => Math.min(a + 1, todas.length - 1)); }
          else if (e.key === 'ArrowUp') { e.preventDefault(); setActivo((a) => Math.max(a - 1, 0)); }
          else if (e.key === 'Enter') { e.preventDefault(); if (todas[activo]) elegir(todas[activo]); else if (esReal) buscar(texto, true); }
          else if (e.key === 'Escape') setAbierto(false);
        }}
      />
      <button className="boton chico" type="button" title="Elegir en el mapa" aria-label={`Elegir ${etiqueta.toLowerCase()} en el mapa`} onClick={alMapa}>📍</button>
      {extra}
      {abierto && (todas.length > 0 || (esReal && texto.trim().length >= 3) || sug?.aviso) && (
        <div className="sugerencias" id={id + '-lista'} role="listbox">
          {todas.map((l, i) => (
            <button key={`${l.tipo}-${l.nombre}-${l.x}-${l.y}`} role="option" aria-selected={i === activo} type="button" onMouseDown={(e) => { e.preventDefault(); elegir(l); }}>
              {l.nombre}<small>{etiquetaTipo(l.tipo)}{l.detalle ? ' · ' + l.detalle : ''}</small>
            </button>
          ))}
          {esReal && texto.trim().length >= 3 && (
            <button type="button" onMouseDown={(e) => { e.preventDefault(); buscar(texto, true); }}>
              {buscando ? 'Buscando en OpenStreetMap…' : `Buscar «${texto.trim()}» en OpenStreetMap`}<small>Restaurantes, clínicas, barrios, direcciones…</small>
            </button>
          )}
          {sug?.aviso && <div style={{ padding: '8px 12px', color: 'var(--texto2)', fontSize: 12.5 }}>{sug.aviso}</div>}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------- panel

export function PanelRuta({ nav, esReal }: { nav: Navegacion; esReal: boolean }): ReactElement {
  const op = nav.opciones ? nav.opciones[Math.min(nav.sel, nav.opciones.length - 1)] : null;
  const clase = (o: OpcionRuta) => (o.tiempoLibre === 0 ? 'fluido' : o.tiempoActual / o.tiempoLibre > 1.6 ? 'muy-lento' : o.tiempoActual / o.tiempoLibre > 1.25 ? 'lento' : 'fluido');
  return (
    <div style={{ display: 'grid', gap: 12 }}>
      <div className="ruta-campos">
        <CampoLugar letra="A" etiqueta="Origen" valor={nav.origen} esReal={esReal} alElegir={(l) => nav.fijar('origen', l)} alMapa={() => nav.eligiendoEnMapa(nav.eligiendo === 'origen' ? null : 'origen')}
          extra={esReal ? <button className="boton chico" type="button" title="Usar mi ubicación" aria-label="Usar mi ubicación" onClick={nav.usarMiUbicacion}>◎</button> : undefined} />
        <CampoLugar letra="B" etiqueta="Destino" valor={nav.destino} esReal={esReal} alElegir={(l) => nav.fijar('destino', l)} alMapa={() => nav.eligiendoEnMapa(nav.eligiendo === 'destino' ? null : 'destino')} />
      </div>
      {nav.eligiendo && <div className="aviso" role="status">Haz clic en el mapa para elegir {nav.eligiendo === 'origen' ? 'el origen' : 'el destino'}.</div>}
      <div className="acciones">
        <button className="boton primario" onClick={nav.calcular} disabled={nav.cargando || !nav.origen || !nav.destino}>{nav.cargando ? 'Calculando…' : 'Cómo llegar'}</button>
        <button className="boton" onClick={nav.intercambiar} disabled={!nav.origen && !nav.destino} aria-label="Intercambiar origen y destino" title="Intercambiar">⇅</button>
        <button className="boton" onClick={nav.limpiar} disabled={!nav.origen && !nav.destino}>Limpiar</button>
      </div>
      {nav.error && <div className="error" role="alert">{nav.error}</div>}
      {nav.aviso && <div className="aviso alerta" role="status">{nav.aviso}</div>}
      {nav.opciones && nav.opciones.length > 0 && (
        <>
          <div style={{ display: 'grid', gap: 8 }} role="group" aria-label="Rutas posibles">
            {nav.opciones.map((o, i) => (
              <button key={o.etiqueta} className="opcion-ruta" aria-pressed={nav.sel === i} onClick={() => nav.elegirOpcion(i)}>
                <span className={'tiempo ' + clase(o)} data-testid={i === 0 ? 'tiempo-ruta' : undefined}>{fmtTiempo(o.tiempoActual)}</span> <span style={{ color: 'var(--texto2)' }}>· {fmtDist(o.distancia)}</span>
                <small>{o.etiqueta}{o.retraso >= 20 ? ` · ${fmtTiempo(o.retraso)} más por el tráfico y los semáforos` : ' · sin demoras importantes'}</small>
              </button>
            ))}
          </div>
          {op && (
            <>
              <div className="acciones">
                {nav.guiando
                  ? <button className="boton peligro" onClick={nav.detenerGuia}>Detener navegación</button>
                  : <button className="boton primario" onClick={nav.iniciarGuia}>Iniciar navegación</button>}
              </div>
              <ol className="pasos" aria-label="Instrucciones">
                {op.pasos.map((p, i) => (
                  <li key={i}>
                    <span className="icono" aria-hidden="true">{FLECHA[p.giro] ?? '↑'}</span>
                    <span>{p.texto}</span>
                    <span className="dist">{p.distancia > 0 ? fmtDist(p.distancia) : ''}</span>
                  </li>
                ))}
              </ol>
              <small style={{ color: 'var(--texto3)' }}>Los tiempos son estimaciones del modelo de tráfico y cambian si se cierra una calle o se congestiona.</small>
            </>
          )}
        </>
      )}
      {!nav.opciones && !nav.error && <p className="vacio-lateral" style={{ margin: 0 }}>Escribe un origen y un destino (por ejemplo «Calle 13»), o elígelos en el mapa con 📍.</p>}
    </div>
  );
}
