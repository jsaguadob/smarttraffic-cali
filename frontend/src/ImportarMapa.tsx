import { useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { api } from './api';

interface Estado {
  estado: 'OCIOSO' | 'DESCARGANDO' | 'PROCESANDO' | 'LISTO' | 'ERROR';
  mensaje: string;
  segundos: number;
  resultado?: { intersecciones: number; vias: number; semaforos: number; entradas: number; bases: number };
}

interface PruebaRed {
  nombre: string;
  url: string;
  ok: boolean;
  http: number;
  ms: number;
  direcciones: string[];
  error?: string;
}

const ZONAS: { id: string; nombre: string; detalle: string; z: [number, number, number, number] }[] = [
  { id: 'centro', nombre: 'Centro de Cali', detalle: 'unos 2 km de lado · rápido', z: [3.442, -76.542, 3.462, -76.522] },
  { id: 'ampliada', nombre: 'Centro ampliado', detalle: 'unos 4 km de lado · tarda más', z: [3.4325, -76.5515, 3.4715, -76.5125] },
];

/** Descarga el mapa real de una zona de OpenStreetMap desde el servidor y lo deja como la red de la aplicación. */
export function ImportarMapa({ alTerminar }: { alTerminar: () => void }): ReactElement {
  const [zona, setZona] = useState('centro');
  const [est, setEst] = useState<Estado | null>(null);
  const [error, setError] = useState('');
  const sondeo = useRef<number | undefined>(undefined);
  const avisado = useRef(false);
  const [diagnostico, setDiagnostico] = useState<{ pruebas: PruebaRed[]; resumen: string } | null>(null);
  const [probando, setProbando] = useState(false);
  const [subiendo, setSubiendo] = useState(false);
  const [exito, setExito] = useState('');

  const consultar = async () => {
    try {
      const e = await api<Estado>('GET', '/api/red/importar-osm');
      setEst(e);
      if (e.estado === 'LISTO' && !avisado.current) { avisado.current = true; window.clearInterval(sondeo.current); alTerminar(); }
      if (e.estado === 'ERROR') window.clearInterval(sondeo.current);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Error');
      window.clearInterval(sondeo.current);
    }
  };

  useEffect(() => () => window.clearInterval(sondeo.current), []);

  const iniciar = async () => {
    setError('');
    avisado.current = false;
    const z = ZONAS.find((x) => x.id === zona)!.z;
    try {
      setEst(await api<Estado>('POST', '/api/red/importar-osm', { sur: z[0], oeste: z[1], norte: z[2], este: z[3] }));
      window.clearInterval(sondeo.current);
      sondeo.current = window.setInterval(() => void consultar(), 1500);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Error');
    }
  };

  const probarConexion = async () => {
    setProbando(true);
    setError('');
    try {
      setDiagnostico(await api('GET', '/api/red/diagnostico'));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Error');
    } finally {
      setProbando(false);
    }
  };

  const abrirDescarga = async () => {
    setError('');
    const z = ZONAS.find((x) => x.id === zona)!.z;
    try {
      const r = await api<{ url: string }>('GET', `/api/red/importar-osm/enlace?sur=${z[0]}&oeste=${z[1]}&norte=${z[2]}&este=${z[3]}`);
      window.open(r.url, '_blank', 'noopener');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Error');
    }
  };

  const subirArchivo = (ev: React.ChangeEvent<HTMLInputElement>) => {
    const f = ev.target.files?.[0];
    ev.target.value = '';
    if (!f) return;
    setError('');
    setExito('');
    setSubiendo(true);
    void f.text().then(async (texto) => {
      try {
        await api('POST', '/api/red/importar-overpass', texto);
        setExito('Listo: el mapa del archivo ya es la red de la aplicación.');
        avisado.current = true;
        alTerminar();
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Error');
      } finally {
        setSubiendo(false);
      }
    });
  };

  const enCurso = est?.estado === 'DESCARGANDO' || est?.estado === 'PROCESANDO';
  return (
    <div style={{ display: 'grid', gap: 10 }}>
      <div className="segmentos" role="group" aria-label="Zona a descargar" style={{ flexWrap: 'wrap' }}>
        {ZONAS.map((z) => <button key={z.id} aria-pressed={zona === z.id} disabled={enCurso} onClick={() => setZona(z.id)} title={z.detalle}>{z.nombre}</button>)}
      </div>
      <small style={{ color: 'var(--texto3)' }}>{ZONAS.find((x) => x.id === zona)!.detalle}. Los datos vienen de OpenStreetMap.</small>
      <button className="boton primario" disabled={enCurso} onClick={() => void iniciar()}>{enCurso ? 'Descargando…' : 'Descargar el mapa real'}</button>
      {enCurso && <><div className="progreso" aria-hidden="true"><i /></div><span role="status" style={{ fontSize: 12.5 }}>{est?.mensaje} ({est?.segundos} s)</span></>}
      {est?.estado === 'LISTO' && <div className="aviso" role="status">{est.mensaje} {est.resultado && `${est.resultado.semaforos} semáforos y ${est.resultado.bases} bases de emergencia.`}</div>}
      {est?.estado === 'ERROR' && <div className="error" role="alert">{est.mensaje}</div>}
      {error && <div className="error" role="alert">{error}</div>}
      {exito && <div className="aviso" role="status">{exito}</div>}
      {(est?.estado === 'ERROR' || diagnostico) && (
        <div style={{ display: 'grid', gap: 10, borderTop: '1px solid var(--borde)', paddingTop: 10 }}>
          <b style={{ fontWeight: 500 }}>¿No se pudo? Opciones</b>
          <button className="boton" onClick={() => void probarConexion()} disabled={probando}>{probando ? 'Probando…' : 'Probar la conexión del servidor'}</button>
          {diagnostico && (
            <div role="region" aria-label="Resultado de la prueba de conexión" style={{ display: 'grid', gap: 6, fontSize: 12.5 }}>
              <div className="aviso">{diagnostico.resumen}</div>
              {diagnostico.pruebas.map((p) => (
                <div key={p.url} style={{ display: 'grid', gridTemplateColumns: '18px 1fr', gap: 6 }}>
                  <span aria-label={p.ok ? 'Funciona' : 'Falla'} style={{ color: p.ok ? 'var(--ok)' : 'var(--peligro)', fontWeight: 700 }}>{p.ok ? '✔' : '✖'}</span>
                  <span>{p.nombre}<small style={{ display: 'block', color: 'var(--texto3)' }}>{p.ok ? `respondió ${p.http} en ${p.ms} ms` : p.error}{p.direcciones.length > 0 ? ` · ${p.direcciones.slice(0, 2).join(', ')}` : ''}</small></span>
                </div>
              ))}
            </div>
          )}
          <div style={{ display: 'grid', gap: 6, fontSize: 12.5, color: 'var(--texto2)' }}>
            <span><b>Plan B, sin internet en el servidor:</b> descarga el mapa con tu navegador y súbelo.</span>
            <button className="boton" onClick={() => void abrirDescarga()}>1. Descargar el mapa con mi navegador</button>
            <small>Se abre una pestaña (puede tardar un minuto). Cuando termine de cargar, guarda la página con Ctrl+S como <code>mapa.json</code>.</small>
            <label className="campo">2. Subir el archivo
              <input type="file" accept=".json,application/json" disabled={subiendo} onChange={subirArchivo} aria-label="Subir archivo del mapa" />
            </label>
            {subiendo && <span role="status">Convirtiendo el archivo…</span>}
          </div>
        </div>
      )}
    </div>
  );
}
