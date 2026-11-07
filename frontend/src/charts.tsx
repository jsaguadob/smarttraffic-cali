import type { ReactElement } from 'react';

export interface Serie {
  nombre: string;
  color: string;
  datos: { x: number; y: number }[];
}

/** Gráfica de líneas en SVG, sin dependencias. */
export function GraficaLineas({ series, titulo, unidad, ancho = 520, alto = 220 }: { series: Serie[]; titulo: string; unidad: string; ancho?: number; alto?: number }): ReactElement {
  const m = { i: 44, d: 12, s: 10, b: 28 };
  const todos = series.flatMap((s) => s.datos);
  if (todos.length < 2) return <div className="grafica vacia">Sin datos todavía</div>;
  const xMax = Math.max(...todos.map((p) => p.x)), yMax = Math.max(1, ...todos.map((p) => p.y)) * 1.1;
  const sx = (x: number) => m.i + (x / (xMax || 1)) * (ancho - m.i - m.d);
  const sy = (y: number) => alto - m.b - (y / yMax) * (alto - m.b - m.s);
  const marcas = [0, 0.25, 0.5, 0.75, 1];
  return (
    <figure className="grafica">
      <figcaption>{titulo}</figcaption>
      <svg viewBox={`0 0 ${ancho} ${alto}`} role="img" aria-label={titulo}>
        {marcas.map((f) => (
          <g key={f}>
            <line x1={m.i} x2={ancho - m.d} y1={sy(yMax * f)} y2={sy(yMax * f)} className="rejilla" />
            <text x={m.i - 6} y={sy(yMax * f) + 4} textAnchor="end" className="eje">{(yMax * f).toFixed(yMax > 20 ? 0 : 1)}</text>
            <text x={sx(xMax * f)} y={alto - 8} textAnchor={f === 1 ? 'end' : f === 0 ? 'start' : 'middle'} className="eje">{Math.round((xMax * f) / 60)} min</text>
          </g>
        ))}
        {series.map((s) => (
          <polyline key={s.nombre} fill="none" stroke={s.color} strokeWidth={2.2} strokeLinejoin="round" points={s.datos.map((p) => `${sx(p.x).toFixed(1)},${sy(p.y).toFixed(1)}`).join(' ')} />
        ))}
      </svg>
      <div className="leyenda">
        {series.map((s) => (
          <span key={s.nombre}><i style={{ background: s.color }} />{s.nombre}</span>
        ))}
        <span className="unidad">{unidad}</span>
      </div>
    </figure>
  );
}

export interface FilaComparativa {
  etiqueta: string;
  a: number;
  b: number;
  unidad: string;
  /** true si un valor menor es mejor (tiempos, congestión). */
  menorEsMejor: boolean;
  decimales?: number;
}

/** Barras comparativas entre dos escenarios, con el porcentaje de diferencia. */
export function BarrasComparativas({ filas, nombreA, nombreB }: { filas: FilaComparativa[]; nombreA: string; nombreB: string }): ReactElement {
  return (
    <div className="barras">
      <div className="leyenda">
        <span><i style={{ background: 'var(--naranja)' }} />{nombreA}</span>
        <span><i style={{ background: 'var(--acento)' }} />{nombreB}</span>
      </div>
      {filas.map((f) => {
        const max = Math.max(f.a, f.b, 1e-9);
        const mejora = f.a === 0 ? 0 : ((f.menorEsMejor ? f.a - f.b : f.b - f.a) / Math.abs(f.a)) * 100;
        const dec = f.decimales ?? 0;
        return (
          <div className="fila-barra" key={f.etiqueta}>
            <div className="etiqueta">
              <b>{f.etiqueta}</b>
              <span className={mejora > 0.5 ? 'mejor' : mejora < -0.5 ? 'peor' : 'igual'}>{mejora > 0.5 ? '▲ ' : mejora < -0.5 ? '▼ ' : '= '}{Math.abs(mejora).toFixed(0)} %</span>
            </div>
            <div className="barra"><span style={{ width: `${(f.a / max) * 100}%`, background: 'var(--naranja)' }} /><em>{f.a.toLocaleString('es-CO', { maximumFractionDigits: dec, minimumFractionDigits: dec })} {f.unidad}</em></div>
            <div className="barra"><span style={{ width: `${(f.b / max) * 100}%`, background: 'var(--acento)' }} /><em>{f.b.toLocaleString('es-CO', { maximumFractionDigits: dec, minimumFractionDigits: dec })} {f.unidad}</em></div>
          </div>
        );
      })}
    </div>
  );
}
