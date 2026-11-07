/**
 * Mapa base real: teselas de 256 px en proyección Web Mercator («slippy map», el esquema de OpenStreetMap).
 * La red se dibuja en metros locales (igual que el servidor); aquí se convierten los límites de cada tesela
 * a esos metros para pintar la imagen exactamente debajo de las calles.
 */
export interface Origen {
  lat: number;
  lon: number;
}

const RADIO_TIERRA = 6371008.8;
const rad = (g: number): number => (g * Math.PI) / 180;

/** Metros locales (x al este, y al sur) de un punto geográfico: la misma proyección que usa el servidor. */
export function aMetros(o: Origen, lat: number, lon: number): { x: number; y: number } {
  return { x: rad(lon - o.lon) * Math.cos(rad(o.lat)) * RADIO_TIERRA, y: -rad(lat - o.lat) * RADIO_TIERRA };
}

export function aGrados(o: Origen, x: number, y: number): { lat: number; lon: number } {
  return { lat: o.lat - (y / RADIO_TIERRA) * (180 / Math.PI), lon: o.lon + (x / (RADIO_TIERRA * Math.cos(rad(o.lat)))) * (180 / Math.PI) };
}

export const tileX = (lon: number, z: number): number => Math.floor(((lon + 180) / 360) * 2 ** z);
export const tileY = (lat: number, z: number): number => {
  const l = rad(lat);
  return Math.floor(((1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2) * 2 ** z);
};
export const lonDeTile = (x: number, z: number): number => (x / 2 ** z) * 360 - 180;
export const latDeTile = (y: number, z: number): number => (Math.atan(Math.sinh(Math.PI * (1 - (2 * y) / 2 ** z))) * 180) / Math.PI;

/** Nivel de zoom cuya resolución se parece más a la de la pantalla (k = píxeles por metro). */
export function zoomPara(k: number, latitud: number, dpr: number): number {
  const z = Math.round(Math.log2(156543.03392 * Math.cos(rad(latitud)) * k * dpr));
  return Math.max(3, Math.min(19, z));
}

interface Entrada {
  img: HTMLImageElement;
  estado: 'cargando' | 'listo' | 'error';
}

export interface Ventana {
  minX: number;
  minY: number;
  maxX: number;
  maxY: number;
}

/** Descarga y dibuja teselas con una caché limitada. Mientras llega una tesela se muestra la de menor zoom ya cargada. */
export class CapaTeselas {
  private readonly cache = new Map<string, Entrada>();
  private cola: string[] = [];
  private enCarga = 0;
  private necesarias = new Set<string>();
  private readonly urls = new Map<string, string>();

  private readonly plantilla: string;
  private readonly maxCache: number;
  private readonly maxSimultaneas: number;

  constructor(plantilla: string, maxCache = 400, maxSimultaneas = 10) {
    this.plantilla = plantilla;
    this.maxCache = maxCache;
    this.maxSimultaneas = maxSimultaneas;
  }

  private url(z: number, x: number, y: number): string {
    const sub = ['a', 'b', 'c'][(x + y) % 3];
    return this.plantilla.replace('{z}', String(z)).replace('{x}', String(x)).replace('{y}', String(y)).replace('{s}', sub);
  }

  private solicitar(clave: string, z: number, x: number, y: number): void {
    this.necesarias.add(clave);
    if (this.cache.has(clave)) return;
    this.urls.set(clave, this.url(z, x, y));
    this.cola.push(clave);
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.referrerPolicy = 'strict-origin-when-cross-origin';
    this.cache.set(clave, { img, estado: 'cargando' });
    this.avanzar();
  }

  private avanzar(): void {
    while (this.enCarga < this.maxSimultaneas && this.cola.length > 0) {
      const clave = this.cola.pop()!; // la última pedida es la más reciente (la que se ve ahora)
      const e = this.cache.get(clave);
      if (!e || e.estado !== 'cargando') continue;
      if (!this.necesarias.has(clave)) { this.cache.delete(clave); continue; } // ya salió de la pantalla
      this.enCarga++;
      e.img.onload = () => { e.estado = 'listo'; this.enCarga--; this.avanzar(); };
      e.img.onerror = () => { e.estado = 'error'; this.enCarga--; this.avanzar(); };
      e.img.src = this.urls.get(clave)!;
    }
    while (this.cache.size > this.maxCache) {
      const primera = this.cache.keys().next().value as string;
      this.cache.delete(primera);
    }
  }

  private listo(z: number, x: number, y: number): HTMLImageElement | null {
    const e = this.cache.get(`${z}/${x}/${y}`);
    return e && e.estado === 'listo' ? e.img : null;
  }

  /** Dibuja las teselas que cubren la ventana (en metros locales). El contexto ya debe estar en esa escala. */
  dibujar(ctx: CanvasRenderingContext2D, o: Origen, v: Ventana, k: number, dpr: number): void {
    this.necesarias = new Set<string>();
    let z = zoomPara(k, o.lat, dpr);
    const nw = aGrados(o, v.minX, v.minY), se = aGrados(o, v.maxX, v.maxY);
    let x0 = tileX(nw.lon, z), x1 = tileX(se.lon, z), y0 = tileY(nw.lat, z), y1 = tileY(se.lat, z);
    while ((x1 - x0 + 1) * (y1 - y0 + 1) > 90 && z > 3) { // demasiadas teselas: se baja un nivel
      z--;
      x0 = tileX(nw.lon, z); x1 = tileX(se.lon, z); y0 = tileY(nw.lat, z); y1 = tileY(se.lat, z);
    }
    const n = 2 ** z;
    for (let tx = Math.max(0, x0); tx <= Math.min(n - 1, x1); tx++) {
      for (let ty = Math.max(0, y0); ty <= Math.min(n - 1, y1); ty++) {
        const a = aMetros(o, latDeTile(ty, z), lonDeTile(tx, z)), b = aMetros(o, latDeTile(ty + 1, z), lonDeTile(tx + 1, z));
        const solape = 0.6 / k; // media décima de píxel: evita líneas finas entre teselas
        const img = this.listo(z, tx, ty);
        if (img) {
          ctx.drawImage(img, a.x, a.y, b.x - a.x + solape, b.y - a.y + solape);
        } else {
          this.solicitar(`${z}/${tx}/${ty}`, z, tx, ty);
          for (let d = 1; d <= 3 && z - d >= 0; d++) { // mientras llega, se muestra la tesela madre (más borrosa)
            const px = tx >> d, py = ty >> d, madre = this.listo(z - d, px, py);
            if (!madre) continue;
            const lado = 256 / 2 ** d;
            ctx.drawImage(madre, (tx - px * 2 ** d) * lado, (ty - py * 2 ** d) * lado, lado, lado, a.x, a.y, b.x - a.x + solape, b.y - a.y + solape);
            break;
          }
        }
      }
    }
    this.avanzar();
  }
}
