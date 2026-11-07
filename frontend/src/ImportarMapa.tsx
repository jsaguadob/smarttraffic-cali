import { useEffect, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { api } from './api';

interface Estado {
  estado: 'OCIOSO' | 'DESCARGANDO' | 'PROCESANDO' | 'LISTO' | 'ERROR';
  mensaje: string;
  segundos: number;
  resultado?: { intersecciones: number; vias: number; semaforos: number; entradas: number; bases: number };
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
    </div>
  );
}
