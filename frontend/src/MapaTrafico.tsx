import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import type { MutableRefObject } from 'react';
import type { Instantanea } from './tipos';
import { luzDeFase, nodoCercano, puntoEnVia, viaCercana } from './geometria';
import type { RedGeo } from './geometria';

export interface CapasMapa {
  congestion: boolean;
  semaforos: boolean;
  rutas: boolean;
  calor: boolean;
}

export interface PuntoMapa {
  x: number;
  y: number;
  nombre: string;
  puntaje: number;
}

export interface Hover {
  tipo: 'via' | 'nodo';
  id: string;
  px: number;
  py: number;
}

export interface FuenteInstantanea {
  inst: Instantanea | null;
  recibido: number;
}

export interface PropsMapa {
  geo: RedGeo;
  fuente: MutableRefObject<FuenteInstantanea>;
  capas: CapasMapa;
  /** Puntos del mapa de calor de accidentes: [x, y, peso]. */
  calor?: number[][];
  criticos?: PuntoMapa[];
  seleccion?: { tipo: 'via' | 'nodo'; id: string } | null;
  marcados?: string[];
  cursor?: string;
  editor?: boolean;
  enfocar?: { x: number; y: number; k?: number; n: number } | null;
  onClickVia?: (viaId: string, pos: number) => void;
  onClickNodo?: (nodoId: string) => void;
  onClickVacio?: (x: number, y: number) => void;
  onMoverNodo?: (nodoId: string, x: number, y: number, fin: boolean) => void;
  onHover?: (h: Hover | null) => void;
}

export interface ManejadorMapa {
  ajustar: () => void;
  acercar: (factor: number) => void;
}

const COLOR_NIVEL = ['#3a4f78', '#dba521', '#f0742f', '#e8344c'];
const COLOR_AUTO = ['#e8eef9', '#9ec5ff', '#c4cdd9', '#f2b8b8', '#a9e5c3', '#f7e08a'];
const COLOR_GRAVEDAD = ['', '#f4b740', '#ff8a3d', '#ef4565'];
const COLOR_LUZ = { verde: '#2fd48a', amarillo: '#f4b740', rojo: '#ef4565' } as const;
const LARGO = [4.5, 2.2, 11, 5.5, 9, 4.8];
const ANCHO = [1.9, 0.9, 2.5, 2.1, 2.5, 1.9];

interface Vista {
  k: number;
  ox: number;
  oy: number;
}

export const MapaTrafico = forwardRef<ManejadorMapa, PropsMapa>(function MapaTrafico(props, ref) {
  const lienzo = useRef<HTMLCanvasElement>(null);
  const contenedor = useRef<HTMLDivElement>(null);
  const propsRef = useRef(props);
  propsRef.current = props;
  const vista = useRef<Vista>({ k: 1, ox: 0, oy: 0 });
  const tamano = useRef({ w: 0, h: 0 });
  const rutas = useRef<Path2D[]>([]);
  const calorImg = useRef<{ lienzo: HTMLCanvasElement; x: number; y: number; k: number } | null>(null);
  const hover = useRef<{ tipo: 'via' | 'nodo'; id: string } | null>(null);
  const arrastre = useRef<{ modo: 'nada' | 'mapa' | 'nodo'; id?: string; x0: number; y0: number; ox0: number; oy0: number; movido: boolean } | null>(null);
  const punteros = useRef(new Map<number, { x: number; y: number }>());
  const pinza = useRef<{ d: number; k: number } | null>(null);
  const ajustado = useRef(false);
  const ultimoEnfoque = useRef(0);

  // ------------------------------------------------------------ vista

  const ajustar = () => {
    const { w, h } = tamano.current;
    if (w < 10 || h < 10) return;
    const c = propsRef.current.geo.caja;
    const bw = Math.max(1, c.maxX - c.minX), bh = Math.max(1, c.maxY - c.minY);
    const k = Math.min((w - 90) / bw, (h - 90) / bh);
    vista.current = { k, ox: (w - bw * k) / 2 - c.minX * k, oy: (h - bh * k) / 2 - c.minY * k };
    ajustado.current = true;
  };

  const acercar = (factor: number, cx?: number, cy?: number) => {
    const v = vista.current;
    const { w, h } = tamano.current;
    const x = cx ?? w / 2, y = cy ?? h / 2;
    const k = Math.max(0.04, Math.min(14, v.k * factor));
    const f = k / v.k;
    vista.current = { k, ox: x - (x - v.ox) * f, oy: y - (y - v.oy) * f };
  };

  useImperativeHandle(ref, () => ({ ajustar, acercar: (f) => acercar(f) }));

  // Solo lectura: permite a las pruebas de navegador saber en qué píxel del lienzo cae un punto del mapa.
  useEffect(() => {
    (window as unknown as { __mapa?: unknown }).__mapa = {
      aPantalla: (x: number, y: number) => ({ x: x * vista.current.k + vista.current.ox, y: y * vista.current.k + vista.current.oy }),
      escala: () => vista.current.k,
    };
  }, []);

  const aMundo = (px: number, py: number) => ({ x: (px - vista.current.ox) / vista.current.k, y: (py - vista.current.oy) / vista.current.k });

  // ------------------------------------------------------------ preparación (cuando cambia la red)

  useEffect(() => {
    rutas.current = props.geo.vias.map((g) => {
      const p = new Path2D();
      p.moveTo(g.pts[0], g.pts[1]);
      for (let i = 2; i < g.pts.length; i += 2) p.lineTo(g.pts[i], g.pts[i + 1]);
      return p;
    });
    if (!ajustado.current) ajustar();
  }, [props.geo]);

  useEffect(() => {
    // el mapa de calor se pinta una vez en un lienzo aparte (miles de puntos por cuadro serían demasiado)
    const puntos = props.calor;
    if (!puntos || puntos.length === 0) { calorImg.current = null; return; }
    const c = props.geo.caja;
    const margen = 120, k = Math.min(0.6, 2048 / Math.max(c.maxX - c.minX + 2 * margen, c.maxY - c.minY + 2 * margen));
    const cv = document.createElement('canvas');
    cv.width = Math.ceil((c.maxX - c.minX + 2 * margen) * k);
    cv.height = Math.ceil((c.maxY - c.minY + 2 * margen) * k);
    const g = cv.getContext('2d');
    if (!g) return;
    g.globalCompositeOperation = 'lighter';
    for (const [x, y, peso] of puntos) {
      const cx = (x - c.minX + margen) * k, cy = (y - c.minY + margen) * k, r = (46 + 4 * Math.sqrt(peso)) * k;
      const grad = g.createRadialGradient(cx, cy, 0, cx, cy, r);
      const a = Math.min(0.5, 0.05 + peso * 0.025);
      grad.addColorStop(0, `rgba(255,96,48,${a})`);
      grad.addColorStop(1, 'rgba(255,96,48,0)');
      g.fillStyle = grad;
      g.fillRect(cx - r, cy - r, r * 2, r * 2);
    }
    calorImg.current = { lienzo: cv, x: c.minX - margen, y: c.minY - margen, k };
  }, [props.calor, props.geo]);

  useEffect(() => {
    const e = props.enfocar;
    if (!e || e.n === ultimoEnfoque.current) return;
    ultimoEnfoque.current = e.n;
    const k = e.k ?? Math.max(vista.current.k, 1.3);
    const { w, h } = tamano.current;
    vista.current = { k, ox: w / 2 - e.x * k, oy: h / 2 - e.y * k };
  }, [props.enfocar]);

  // ------------------------------------------------------------ tamaño y eventos

  useEffect(() => {
    const cont = contenedor.current, cv = lienzo.current;
    if (!cont || !cv) return;
    const medir = () => {
      const r = cont.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      tamano.current = { w: r.width, h: r.height };
      cv.width = Math.max(1, Math.floor(r.width * dpr));
      cv.height = Math.max(1, Math.floor(r.height * dpr));
      if (!ajustado.current) ajustar();
    };
    medir();
    const ro = new ResizeObserver(medir);
    ro.observe(cont);
    const rueda = (ev: WheelEvent) => {
      ev.preventDefault();
      const r = cv.getBoundingClientRect();
      acercar(Math.pow(1.0015, -ev.deltaY), ev.clientX - r.left, ev.clientY - r.top);
    };
    cv.addEventListener('wheel', rueda, { passive: false });
    return () => {
      ro.disconnect();
      cv.removeEventListener('wheel', rueda);
    };
  }, []);

  const objetivoEn = (px: number, py: number) => {
    const { geo } = propsRef.current;
    const m = aMundo(px, py), k = vista.current.k;
    const n = nodoCercano(geo, m.x, m.y, 13 / k);
    if (n >= 0) return { tipo: 'nodo' as const, id: geo.nodos[n].id, x: m.x, y: m.y, pos: 0 };
    const v = viaCercana(geo, m.x, m.y, 9 / k);
    if (v) return { tipo: 'via' as const, id: v.via.via.id, x: m.x, y: m.y, pos: v.pos };
    return { tipo: 'vacio' as const, id: '', x: m.x, y: m.y, pos: 0 };
  };

  const posicion = (ev: React.PointerEvent) => {
    const r = lienzo.current!.getBoundingClientRect();
    return { x: ev.clientX - r.left, y: ev.clientY - r.top };
  };

  const alBajar = (ev: React.PointerEvent<HTMLCanvasElement>) => {
    lienzo.current?.setPointerCapture(ev.pointerId);
    const p = posicion(ev);
    punteros.current.set(ev.pointerId, p);
    if (punteros.current.size === 2) {
      const [a, b] = [...punteros.current.values()];
      pinza.current = { d: Math.hypot(a.x - b.x, a.y - b.y), k: vista.current.k };
      arrastre.current = null;
      return;
    }
    const o = objetivoEn(p.x, p.y);
    const moverNodo = propsRef.current.editor && o.tipo === 'nodo';
    arrastre.current = { modo: moverNodo ? 'nodo' : 'mapa', id: o.id, x0: p.x, y0: p.y, ox0: vista.current.ox, oy0: vista.current.oy, movido: false };
  };

  const alMover = (ev: React.PointerEvent<HTMLCanvasElement>) => {
    const p = posicion(ev);
    if (punteros.current.has(ev.pointerId)) punteros.current.set(ev.pointerId, p);
    if (punteros.current.size === 2 && pinza.current) {
      const [a, b] = [...punteros.current.values()];
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      const objetivo = pinza.current.k * (d / pinza.current.d);
      acercar(objetivo / vista.current.k, (a.x + b.x) / 2, (a.y + b.y) / 2);
      return;
    }
    const ar = arrastre.current;
    if (ar) {
      const dx = p.x - ar.x0, dy = p.y - ar.y0;
      if (!ar.movido && Math.hypot(dx, dy) > 4) ar.movido = true;
      if (ar.movido && ar.modo === 'mapa') vista.current = { ...vista.current, ox: ar.ox0 + dx, oy: ar.oy0 + dy };
      if (ar.movido && ar.modo === 'nodo' && ar.id) {
        const m = aMundo(p.x, p.y);
        propsRef.current.onMoverNodo?.(ar.id, m.x, m.y, false);
      }
      return;
    }
    const o = objetivoEn(p.x, p.y);
    const nuevo = o.tipo === 'vacio' ? null : { tipo: o.tipo, id: o.id };
    if (nuevo?.id !== hover.current?.id || nuevo?.tipo !== hover.current?.tipo) {
      hover.current = nuevo;
      propsRef.current.onHover?.(nuevo ? { ...nuevo, px: p.x, py: p.y } : null);
    } else if (nuevo) {
      propsRef.current.onHover?.({ ...nuevo, px: p.x, py: p.y });
    }
  };

  const alSoltar = (ev: React.PointerEvent<HTMLCanvasElement>) => {
    const p = posicion(ev);
    punteros.current.delete(ev.pointerId);
    pinza.current = null;
    const ar = arrastre.current;
    arrastre.current = null;
    if (!ar) return;
    const pr = propsRef.current;
    if (ar.modo === 'nodo' && ar.id && ar.movido) {
      const m = aMundo(p.x, p.y);
      pr.onMoverNodo?.(ar.id, m.x, m.y, true);
      return;
    }
    if (ar.movido) return;
    const o = objetivoEn(p.x, p.y);
    if (o.tipo === 'nodo') pr.onClickNodo?.(o.id);
    else if (o.tipo === 'via') pr.onClickVia?.(o.id, o.pos);
    else pr.onClickVacio?.(o.x, o.y);
  };

  const alSalir = () => {
    if (hover.current) { hover.current = null; propsRef.current.onHover?.(null); }
  };

  // ------------------------------------------------------------ dibujo

  useEffect(() => {
    let id = 0;
    const cuadro = (ahora: number) => {
      id = requestAnimationFrame(cuadro);
      const cv = lienzo.current;
      if (!cv || tamano.current.w < 10) return;
      const ctx = cv.getContext('2d');
      if (!ctx) return;
      dibujar(ctx, cv, ahora);
    };
    id = requestAnimationFrame(cuadro);
    return () => cancelAnimationFrame(id);
  }, []);

  function dibujar(ctx: CanvasRenderingContext2D, cv: HTMLCanvasElement, ahora: number) {
    const pr = propsRef.current;
    const { geo, capas } = pr;
    const dpr = window.devicePixelRatio || 1;
    const { k, ox, oy } = vista.current;
    const px = 1 / k;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#0c1322';
    ctx.fillRect(0, 0, cv.width, cv.height);
    ctx.setTransform(dpr * k, 0, 0, dpr * k, dpr * ox, dpr * oy);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const { inst, recibido } = pr.fuente.current;
    const niveles = new Map<number, number[]>();
    if (inst) for (const c of inst.c) niveles.set(c[0], c);

    // mapa de calor de accidentes
    if (capas.calor && calorImg.current) {
      const ci = calorImg.current;
      ctx.globalAlpha = 0.9;
      ctx.drawImage(ci.lienzo, ci.x, ci.y, ci.lienzo.width / ci.k, ci.lienzo.height / ci.k);
      ctx.globalAlpha = 1;
    }

    // calles: borde oscuro y relleno del color de la congestión
    const ancho = Math.max(3.4, 5.4 * px);
    for (let i = 0; i < geo.vias.length; i++) {
      const g = geo.vias[i];
      const mayor = g.via.clase.startsWith('primary') || g.via.clase.startsWith('trunk') || g.via.clase.startsWith('motorway') || g.via.clase.startsWith('secondary');
      ctx.lineWidth = (mayor ? ancho * 1.2 : ancho) + Math.max(1.1, 1.8 * px);
      ctx.strokeStyle = '#050912';
      ctx.stroke(rutas.current[i]);
    }
    for (let i = 0; i < geo.vias.length; i++) {
      const g = geo.vias[i];
      const mayor = g.via.clase.startsWith('primary') || g.via.clase.startsWith('trunk') || g.via.clase.startsWith('motorway') || g.via.clase.startsWith('secondary');
      const c = niveles.get(i);
      ctx.lineWidth = mayor ? ancho * 1.2 : ancho;
      ctx.strokeStyle = capas.congestion && c ? COLOR_NIVEL[c[1]] : COLOR_NIVEL[0];
      ctx.stroke(rutas.current[i]);
    }

    // sentido de las calles de un solo sentido
    if (k > 0.3) {
      ctx.fillStyle = 'rgba(220,232,255,0.55)';
      for (const g of geo.vias) {
        if (!g.via.clase && !g.unSentido) continue;
        if (!g.unSentido || g.largo < 40) continue;
        const p = puntoEnVia(g, g.via.longitud * 0.5), s = Math.max(2.6, 3.6 * px);
        ctx.save();
        ctx.translate(p.x, p.y);
        ctx.rotate(p.ang);
        ctx.beginPath();
        ctx.moveTo(s, 0);
        ctx.lineTo(-s * 0.7, s * 0.8);
        ctx.lineTo(-s * 0.7, -s * 0.8);
        ctx.closePath();
        ctx.fill();
        ctx.restore();
      }
    }

    // selección y hover
    const resaltar = (sel: { tipo: string; id: string } | null | undefined, color: string, grosor: number) => {
      if (!sel) return;
      if (sel.tipo === 'via') {
        const i = geo.indiceVia.get(sel.id);
        if (i === undefined) return;
        ctx.strokeStyle = color;
        ctx.lineWidth = ancho + grosor * px;
        ctx.globalAlpha = 0.6;
        ctx.stroke(rutas.current[i]);
        ctx.globalAlpha = 1;
      } else {
        const i = geo.indiceNodo.get(sel.id);
        if (i === undefined) return;
        ctx.strokeStyle = color;
        ctx.lineWidth = 2.5 * px;
        ctx.beginPath();
        ctx.arc(geo.nodos[i].x, geo.nodos[i].y, 11 * px, 0, Math.PI * 2);
        ctx.stroke();
      }
    };
    resaltar(hover.current, '#7cc4ff', 5);
    resaltar(pr.seleccion, '#3ea6ff', 8);

    // rutas prioritarias de emergencias
    if (inst && capas.rutas) {
      for (const e of inst.e) {
        if (e.atendiendo) continue;
        ctx.save();
        ctx.setLineDash([9 * px, 6 * px]);
        ctx.lineDashOffset = -ahora / 40 * px;
        ctx.lineWidth = Math.max(2.4, 3.4 * px);
        ctx.strokeStyle = 'rgba(88,190,255,0.95)';
        ctx.shadowColor = 'rgba(88,190,255,0.9)';
        ctx.shadowBlur = 8;
        ctx.beginPath();
        const g0 = geo.vias[e.via];
        if (!g0) { ctx.restore(); continue; }
        const inicio = puntoEnVia(g0, e.pos);
        ctx.moveTo(inicio.x, inicio.y);
        const d0 = Math.min(g0.largo, e.pos * g0.escala);
        for (let j = 1; j < g0.acum.length; j++) if (g0.acum[j] > d0) ctx.lineTo(g0.pts[j * 2], g0.pts[j * 2 + 1]);
        for (let r = 1; r < e.ruta.length; r++) {
          const g = geo.vias[e.ruta[r]];
          if (!g) continue;
          for (let j = 0; j < g.pts.length; j += 2) ctx.lineTo(g.pts[j], g.pts[j + 1]);
        }
        ctx.stroke();
        ctx.restore();
      }
    }

    // intersecciones
    const rn = Math.max(3.2, 4.2 * px);
    for (const n of geo.nodos) {
      if (n.servicios.length > 0) continue;
      ctx.beginPath();
      ctx.arc(n.x, n.y, rn, 0, Math.PI * 2);
      if (n.tipo === 'ENTRADA_SALIDA') {
        ctx.fillStyle = '#3ea6ff';
        ctx.fill();
        ctx.strokeStyle = '#0b1c33';
        ctx.lineWidth = 1.4 * px;
        ctx.stroke();
      } else {
        ctx.fillStyle = n.semaforizada ? '#0c1322' : '#26354f';
        ctx.fill();
        ctx.strokeStyle = n.semaforizada ? '#8da6cc' : '#56688a';
        ctx.lineWidth = 1.5 * px;
        ctx.stroke();
      }
    }

    // semáforos: una luz en la línea de pare de cada aproximación
    if (inst && capas.semaforos) {
      const r = Math.max(2.3, 3.3 * px);
      for (const s of inst.s) {
        const manual = s[2] >= 0;
        for (const vi of geo.entrantes[s[0]] ?? []) {
          const g = geo.vias[vi];
          const p = puntoEnVia(g, Math.max(0, g.via.longitud - 3.5));
          const nx = -Math.sin(p.ang) * 3.2, ny = Math.cos(p.ang) * 3.2;
          const luz = luzDeFase(s[1], g.grupo);
          ctx.beginPath();
          ctx.arc(p.x + nx, p.y + ny, r, 0, Math.PI * 2);
          ctx.fillStyle = COLOR_LUZ[luz];
          ctx.shadowColor = COLOR_LUZ[luz];
          ctx.shadowBlur = luz === 'rojo' ? 3 : 8;
          ctx.fill();
          ctx.shadowBlur = 0;
          if (manual) {
            ctx.strokeStyle = '#fff';
            ctx.lineWidth = 1.2 * px;
            ctx.stroke();
          }
        }
      }
    }

    // bases de emergencia
    for (const n of geo.nodos) {
      if (n.servicios.length === 0) continue;
      const r = Math.max(6.5, 8.5 * px);
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = '#f3f7ff';
      ctx.fill();
      ctx.lineWidth = 1.6 * px;
      ctx.strokeStyle = '#0b1220';
      ctx.stroke();
      ctx.font = `700 ${Math.max(7, 9.5 * px)}px system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = n.servicios.includes('hospital') ? '#d62a45' : '#1c4fa8';
      ctx.fillText(n.servicios.map((x) => (x === 'hospital' ? 'H' : x === 'bomberos' ? 'B' : 'P')).join(''), n.x, n.y + 0.4 * px);
    }

    // vehículos
    if (inst) {
      const dt = inst.corriendo ? Math.min(0.15, Math.max(0, (ahora - recibido) / 1000)) * inst.velocidad : 0;
      const detalle = k > 0.18;
      for (const v of inst.v) {
        const g = geo.vias[v[1]];
        if (!g) continue;
        const pos = Math.min(g.via.longitud, v[2] + v[3] * dt);
        const p = puntoEnVia(g, pos);
        const t = v[4];
        const largo = Math.max(LARGO[t], (detalle ? 7 : 4) * px), anch = Math.max(ANCHO[t], (detalle ? 3.5 : 2.5) * px);
        ctx.save();
        ctx.translate(p.x - Math.cos(p.ang) * largo * 0.5, p.y - Math.sin(p.ang) * largo * 0.5);
        ctx.rotate(p.ang);
        ctx.fillStyle = t === 1 ? '#ff9f43' : t === 2 ? '#f4c542' : COLOR_AUTO[v[0] % COLOR_AUTO.length];
        ctx.fillRect(-largo / 2, -anch / 2, largo, anch);
        if (detalle && v[3] < 0.6) {
          ctx.fillStyle = '#ff3b57';
          ctx.fillRect(-largo / 2, -anch / 2, largo * 0.2, anch);
        }
        ctx.restore();
      }
      // emergencias
      const destello = Math.floor(ahora / 230) % 2 === 0;
      for (const e of inst.e) {
        const g = geo.vias[e.via];
        if (!g) continue;
        const p = puntoEnVia(g, Math.min(g.via.longitud, e.pos + e.vel * dt));
        const largo = Math.max(LARGO[e.tipo], 11 * px), anch = Math.max(ANCHO[e.tipo], 5 * px);
        ctx.save();
        ctx.translate(p.x - Math.cos(p.ang) * largo * 0.5, p.y - Math.sin(p.ang) * largo * 0.5);
        ctx.rotate(p.ang);
        ctx.shadowColor = destello ? '#ff3b57' : '#3ea6ff';
        ctx.shadowBlur = 14;
        ctx.fillStyle = e.tipo === 4 ? '#e03a3a' : e.tipo === 5 ? '#2f6fe0' : '#ffffff';
        ctx.fillRect(-largo / 2, -anch / 2, largo, anch);
        ctx.shadowBlur = 0;
        ctx.fillStyle = destello ? '#ff3b57' : '#3ea6ff';
        ctx.fillRect(-largo * 0.1, -anch / 2, largo * 0.22, anch);
        ctx.restore();
      }
      // incidentes
      for (const i of inst.i) {
        const g = geo.vias[i.via];
        if (!g) continue;
        const p = puntoEnVia(g, i.pos);
        const pulso = 1 + 0.18 * Math.sin(ahora / 180);
        const r = Math.max(8, 11 * px) * pulso;
        ctx.beginPath();
        ctx.arc(p.x, p.y, r * 1.7, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(239,69,101,0.18)';
        ctx.fill();
        ctx.beginPath();
        ctx.moveTo(p.x, p.y - r);
        ctx.lineTo(p.x + r * 0.95, p.y + r * 0.7);
        ctx.lineTo(p.x - r * 0.95, p.y + r * 0.7);
        ctx.closePath();
        ctx.fillStyle = i.estado === 'EN_ATENCION' ? '#3ea6ff' : COLOR_GRAVEDAD[i.gravedad];
        ctx.fill();
        ctx.lineWidth = 1.6 * px;
        ctx.strokeStyle = '#0b1220';
        ctx.stroke();
        ctx.fillStyle = '#0b1220';
        ctx.font = `800 ${r * 1.05}px system-ui, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('!', p.x, p.y + r * 0.2);
      }
    }

    // puntos críticos de accidentalidad (datos abiertos)
    if (capas.calor && pr.criticos) {
      pr.criticos.slice(0, 10).forEach((c, i) => {
        ctx.beginPath();
        ctx.arc(c.x, c.y, Math.max(9, 12 * px), 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(12,19,34,0.85)';
        ctx.fill();
        ctx.strokeStyle = '#ff7a45';
        ctx.lineWidth = 2 * px;
        ctx.stroke();
        ctx.fillStyle = '#ffb08a';
        ctx.font = `700 ${Math.max(9, 11.5 * px)}px system-ui, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(String(i + 1), c.x, c.y + 0.4 * px);
      });
    }

    // intersecciones marcadas para la onda verde
    (pr.marcados ?? []).forEach((idn, i) => {
      const n = geo.indiceNodo.get(idn);
      if (n === undefined) return;
      const nodo = geo.nodos[n];
      ctx.beginPath();
      ctx.arc(nodo.x, nodo.y, 11 * px, 0, Math.PI * 2);
      ctx.fillStyle = '#3ea6ff';
      ctx.fill();
      ctx.fillStyle = '#06101f';
      ctx.font = `800 ${12 * px}px system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(String(i + 1), nodo.x, nodo.y + 0.5 * px);
    });
  }

  return (
    <div className="mapa" ref={contenedor}>
      <canvas
        ref={lienzo}
        data-testid="mapa"
        style={{ cursor: props.cursor ?? 'grab', touchAction: 'none' }}
        onPointerDown={alBajar}
        onPointerMove={alMover}
        onPointerUp={alSoltar}
        onPointerCancel={alSoltar}
        onPointerLeave={alSalir}
        aria-label="Mapa de tráfico interactivo"
      />
    </div>
  );
});
