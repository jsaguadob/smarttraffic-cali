import type { NodoRed, Red, ViaRed } from './tipos';

/** Geometría de una vía lista para dibujar: la poligonal se desplaza a la derecha del sentido de marcha. */
export interface ViaGeo {
  via: ViaRed;
  indice: number;
  /** Puntos de la poligonal desplazada (x0, y0, x1, y1...). */
  pts: number[];
  /** Distancia acumulada en cada punto. */
  acum: number[];
  largo: number;
  /** Relación entre la longitud lógica de la vía y la de su dibujo. */
  escala: number;
  /** Grupo de semáforo de esta aproximación (igual que en el servidor). */
  grupo: 0 | 1;
  nodoIni: number;
  nodoFin: number;
  unSentido: boolean;
}

export interface RedGeo {
  red: Red;
  vias: ViaGeo[];
  nodos: NodoRed[];
  indiceNodo: Map<string, number>;
  indiceVia: Map<string, number>;
  entrantes: number[][];
  caja: { minX: number; minY: number; maxX: number; maxY: number };
}

const DESPLAZAMIENTO = 1.9; // metros a la derecha del eje

function desplazar(p: number[], d: number): number[] {
  const n = p.length / 2;
  const salida: number[] = new Array(p.length);
  for (let i = 0; i < n; i++) {
    const a = Math.max(0, i - 1), b = Math.min(n - 1, i + 1);
    let dx = p[b * 2] - p[a * 2], dy = p[b * 2 + 1] - p[a * 2 + 1];
    const l = Math.hypot(dx, dy) || 1;
    dx /= l;
    dy /= l;
    salida[i * 2] = p[i * 2] - dy * d; // la derecha del sentido de marcha (con el eje y hacia abajo)
    salida[i * 2 + 1] = p[i * 2 + 1] + dx * d;
  }
  return salida;
}

export function construirGeo(red: Red): RedGeo {
  const indiceNodo = new Map<string, number>();
  red.intersecciones.forEach((n, i) => indiceNodo.set(n.id, i));
  const pares = new Set<string>(red.vias.map((v) => v.desde + '>' + v.hacia));
  const indiceVia = new Map<string, number>();
  const entrantes: number[][] = red.intersecciones.map(() => []);
  const vias: ViaGeo[] = red.vias.map((v, i) => {
    indiceVia.set(v.id, i);
    const pts = desplazar(v.puntos, DESPLAZAMIENTO);
    const acum = [0];
    for (let k = 2; k < pts.length; k += 2) acum.push(acum[acum.length - 1] + Math.hypot(pts[k] - pts[k - 2], pts[k + 1] - pts[k - 1]));
    const largo = acum[acum.length - 1] || 1;
    const n = v.puntos.length;
    const dx = v.puntos[n - 2] - v.puntos[n - 4], dy = v.puntos[n - 1] - v.puntos[n - 3];
    const g: ViaGeo = {
      via: v,
      indice: i,
      pts,
      acum,
      largo,
      escala: largo / (v.longitud || largo),
      grupo: Math.abs(dx) >= Math.abs(dy) ? 0 : 1,
      nodoIni: indiceNodo.get(v.desde) ?? -1,
      nodoFin: indiceNodo.get(v.hacia) ?? -1,
      unSentido: !pares.has(v.hacia + '>' + v.desde),
    };
    if (g.nodoFin >= 0) entrantes[g.nodoFin].push(i);
    return g;
  });
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const n of red.intersecciones) {
    minX = Math.min(minX, n.x);
    maxX = Math.max(maxX, n.x);
    minY = Math.min(minY, n.y);
    maxY = Math.max(maxY, n.y);
  }
  if (!isFinite(minX)) { minX = minY = 0; maxX = maxY = 100; }
  return { red, vias, nodos: red.intersecciones, indiceNodo, indiceVia, entrantes, caja: { minX, minY, maxX, maxY } };
}

/** Punto de la vía a {@code pos} metros (lógicos) de su inicio: posición en el plano y ángulo de marcha. */
export function puntoEnVia(g: ViaGeo, pos: number): { x: number; y: number; ang: number } {
  const d = Math.max(0, Math.min(g.largo, pos * g.escala));
  const n = g.acum.length;
  let i = 1;
  while (i < n - 1 && g.acum[i] < d) i++;
  const a = g.acum[i - 1], b = g.acum[i];
  const f = b > a ? (d - a) / (b - a) : 0;
  const x0 = g.pts[(i - 1) * 2], y0 = g.pts[(i - 1) * 2 + 1], x1 = g.pts[i * 2], y1 = g.pts[i * 2 + 1];
  return { x: x0 + (x1 - x0) * f, y: y0 + (y1 - y0) * f, ang: Math.atan2(y1 - y0, x1 - x0) };
}

/** Punto más cercano de la vía a (x, y): distancia y posición lógica en metros. */
export function proyectar(g: ViaGeo, x: number, y: number): { dist: number; pos: number } {
  let mejor = Infinity, posMejor = 0;
  for (let i = 2; i < g.pts.length; i += 2) {
    const x0 = g.pts[i - 2], y0 = g.pts[i - 1], dx = g.pts[i] - x0, dy = g.pts[i + 1] - y0;
    const l2 = dx * dx + dy * dy;
    const t = l2 === 0 ? 0 : Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / l2));
    const d = Math.hypot(x - (x0 + dx * t), y - (y0 + dy * t));
    if (d < mejor) { mejor = d; posMejor = (g.acum[i / 2 - 1] + Math.sqrt(l2) * t) / g.escala; }
  }
  return { dist: mejor, pos: posMejor };
}

export function nodoCercano(rg: RedGeo, x: number, y: number, radio: number): number {
  let mejor = -1, md = radio;
  for (let i = 0; i < rg.nodos.length; i++) {
    const d = Math.hypot(rg.nodos[i].x - x, rg.nodos[i].y - y);
    if (d <= md) { md = d; mejor = i; }
  }
  return mejor;
}

export function viaCercana(rg: RedGeo, x: number, y: number, radio: number): { via: ViaGeo; pos: number; dist: number } | null {
  let mejor: { via: ViaGeo; pos: number; dist: number } | null = null;
  for (const g of rg.vias) {
    const p = proyectar(g, x, y);
    if (p.dist <= radio && (!mejor || p.dist < mejor.dist)) mejor = { via: g, pos: p.pos, dist: p.dist };
  }
  return mejor;
}

/** Luz que ve el grupo dado según la fase del semáforo (0-5). */
export function luzDeFase(fase: number, grupo: number): 'verde' | 'amarillo' | 'rojo' {
  if (fase === 0 && grupo === 0) return 'verde';
  if (fase === 1 && grupo === 0) return 'amarillo';
  if (fase === 3 && grupo === 1) return 'verde';
  if (fase === 4 && grupo === 1) return 'amarillo';
  return 'rojo';
}

/** Longitud de una polilínea (x0, y0, x1, y1...). */
export function largoPolilinea(p: number[]): number {
  let t = 0;
  for (let i = 2; i < p.length; i += 2) t += Math.hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1]);
  return t;
}

/** Punto de una polilínea a {@code d} metros de su inicio, con el ángulo de marcha. */
export function puntoEnPolilinea(p: number[], d: number): { x: number; y: number; ang: number } {
  let resto = Math.max(0, d);
  for (let i = 2; i < p.length; i += 2) {
    const dx = p[i] - p[i - 2], dy = p[i + 1] - p[i - 1], seg = Math.hypot(dx, dy);
    if (resto <= seg || i === p.length - 2) {
      const f = seg === 0 ? 0 : Math.min(1, resto / seg);
      return { x: p[i - 2] + dx * f, y: p[i - 1] + dy * f, ang: Math.atan2(dy, dx) };
    }
    resto -= seg;
  }
  return { x: p[0] ?? 0, y: p[1] ?? 0, ang: 0 };
}
