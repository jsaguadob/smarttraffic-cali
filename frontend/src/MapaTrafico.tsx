import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import type { MutableRefObject } from 'react';
import type { Instantanea } from './tipos';
import { CapaTeselas } from './teselas';
import type { Origen } from './teselas';
import { largoPolilinea, luzDeFase, nodoCercano, puntoEnPolilinea, puntoEnVia, viaCercana } from './geometria';
import type { RedGeo } from './geometria';

export interface CapasMapa {
  /** Mapa base real (teselas de OpenStreetMap) debajo de las calles. Solo si la red tiene ubicación real. */
  base: boolean;
  vehiculos: boolean;
  congestion: boolean;
  semaforos: boolean;
  rutas: boolean;
  calor: boolean;
}

/** Ruta de navegación dibujada sobre el mapa. */
export interface RutaMapa {
  opciones: { pts: number[]; sel: boolean }[];
  origen: { x: number; y: number } | null;
  destino: { x: number; y: number } | null;
  /** Vehículo guiado: avanza por la ruta según el tiempo simulado. */
  guia: { pts: number[]; t0: number; duracion: number } | null;
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
  /** Ubicación real de la red (si se importó de OpenStreetMap). */
  origen?: Origen | null;
  fuente: MutableRefObject<FuenteInstantanea>;
  capas: CapasMapa;
  /** Plantilla de la URL de las teselas ({z}/{x}/{y}); por defecto la de OpenStreetMap. */
  urlTeselas?: string;
  /** Puntos del mapa de calor de accidentes: [x, y, peso]. */
  calor?: number[][];
  criticos?: PuntoMapa[];
  seleccion?: { tipo: 'via' | 'nodo'; id: string } | null;
  marcados?: string[];
  cursor?: string;
  editor?: boolean;
  enfocar?: { x: number; y: number; k?: number; n: number } | null;
  /** Ruta de navegación a dibujar. */
  ruta?: RutaMapa | null;
  /** Mientras es verdadero, cada clic solo llama a {@code onClickMapa} (para elegir un punto del mapa). */
  modoElegir?: boolean;
  onClickMapa?: (x: number, y: number) => void;
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

/** Colores del tráfico, iguales a los de los mapas de navegación: verde fluido, amarillo, naranja y rojo. */
const COLOR_NIVEL = ['#34a853', '#fbbc04', '#f57c00', '#d93025'];
const COLOR_GRAVEDAD = ['', '#f9ab00', '#f57c00', '#d93025'];
const COLOR_LUZ = { verde: '#34a853', amarillo: '#fbbc04', rojo: '#d93025' } as const;
const COLOR_VEHICULO = ['#1a73e8', '#e8710a', '#8430ce'];
export const URL_TESELAS: string = import.meta.env.VITE_TILES_URL ?? 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';
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
  const todas = useRef<Path2D | null>(null);
  const estatica = useRef<{ cv: HTMLCanvasElement | null; clave: string }>({ cv: null, clave: '' });
  const calorImg = useRef<{ lienzo: HTMLCanvasElement; x: number; y: number; k: number } | null>(null);
  const hover = useRef<{ tipo: 'via' | 'nodo'; id: string } | null>(null);
  const arrastre = useRef<{ modo: 'nada' | 'mapa' | 'nodo'; id?: string; x0: number; y0: number; ox0: number; oy0: number; movido: boolean } | null>(null);
  const punteros = useRef(new Map<number, { x: number; y: number }>());
  const pinza = useRef<{ d: number; k: number } | null>(null);
  const ajustado = useRef(false);
  const ultimoEnfoque = useRef(0);
  const teselas = useRef<CapaTeselas | null>(null);
  const hayTeselasPendientes = () => (propsRef.current.capas.base && !!propsRef.current.origen);
  const medida = useRef({ ms: 0, n: 0 });
  /** Hasta cuándo (marca de tiempo) se dibuja a toda velocidad por haberse movido o tocado el mapa. */
  const interaccion = useRef(0);
  /** Trabajo que solo cambia cuando llega una instantánea nueva (no en cada cuadro): niveles por vía y un trazo por color. */
  const cacheInst = useRef<{ inst: Instantanea | null; niveles: Map<number, number[]>; porNivel: Path2D[]; hayAnimacion: boolean; firma: string }>({ inst: null, niveles: new Map(), porNivel: [], hayAnimacion: false, firma: '' });
  if (teselas.current === null) teselas.current = new CapaTeselas(props.urlTeselas ?? URL_TESELAS);

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
      cuadroMs: () => medida.current.ms,
      cuadros: () => medida.current.n,
    };
  }, []);

  const aMundo = (px: number, py: number) => ({ x: (px - vista.current.ox) / vista.current.k, y: (py - vista.current.oy) / vista.current.k });

  // ------------------------------------------------------------ preparación (cuando cambia la red)

  useEffect(() => {
    todas.current = null;
    estatica.current.clave = '';
    cacheInst.current.porNivel = [];
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
      interaccion.current = performance.now() + 600;
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
    interaccion.current = performance.now() + 600;
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
    interaccion.current = performance.now() + 600;
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
    pr.onClickMapa?.(o.x, o.y);
    if (pr.modoElegir) return;
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
    let ultimo = 0;
    const cuadro = (ahora: number) => {
      id = requestAnimationFrame(cuadro);
      const cv = lienzo.current;
      if (!cv || tamano.current.w < 10) return;
      // 30 cuadros por segundo bastan para mover vehículos que se interpolan; con todo quieto, 10 (solo para responder al zoom y al arrastre)
      const intervalo = interaccion.current > ahora || cacheInst.current.hayAnimacion || hayTeselasPendientes() ? 30 : 100;
      if (ahora - ultimo < intervalo) return;
      ultimo = ahora;
      const ctx = cv.getContext('2d');
      if (!ctx) return;
      const t0 = performance.now();
      dibujar(ctx, cv, ahora);
      medida.current.ms = medida.current.ms * 0.9 + (performance.now() - t0) * 0.1;
      medida.current.n++;
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
    const { w, h } = tamano.current;
    const conBase = capas.base && !!pr.origen;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#e8ecf1';
    ctx.fillRect(0, 0, cv.width, cv.height);
    ctx.setTransform(dpr * k, 0, 0, dpr * k, dpr * ox, dpr * oy);
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    // mapa base real
    if (conBase && pr.origen) teselas.current!.dibujar(ctx, pr.origen, { minX: -ox / k, minY: -oy / k, maxX: (w - ox) / k, maxY: (h - oy) / k }, k, dpr);

    const { inst, recibido } = pr.fuente.current;
    // los niveles de congestión cambian como máximo una vez por segundo: se reagrupan solo cuando cambian de verdad
    const cc = cacheInst.current;
    if (inst !== cc.inst || cc.porNivel.length === 0) { // también cuando cambió la red (el caché quedó vacío) aunque la instantánea siga siendo la misma
      const firma = inst ? inst.c.map((c) => c[0] * 4 + c[1]).join(',') : '';
      cc.inst = inst;
      cc.hayAnimacion = !!inst && (inst.corriendo || inst.e.length > 0 || inst.i.length > 0);
      if (firma !== cc.firma || cc.porNivel.length === 0) {
        cc.firma = firma;
        cc.niveles = new Map<number, number[]>();
        if (inst) for (const c of inst.c) cc.niveles.set(c[0], c);
        cc.porNivel = [new Path2D(), new Path2D(), new Path2D(), new Path2D()];
        for (let i = 0; i < geo.vias.length; i++) cc.porNivel[cc.niveles.get(i)?.[1] ?? 0].addPath(rutas.current[i]);
      }
    }

    // mapa de calor de accidentes
    if (capas.calor && calorImg.current) {
      const ci = calorImg.current;
      ctx.globalAlpha = 0.85;
      ctx.drawImage(ci.lienzo, ci.x, ci.y, ci.lienzo.width / ci.k, ci.lienzo.height / ci.k);
      ctx.globalAlpha = 1;
    }

    // La red (calles, tráfico y flechas de sentido) se pinta una sola vez en una imagen aparte que se reutiliza en cada cuadro.
    // Solo se vuelve a pintar cuando cambia algo que la afecta: el tráfico (como mucho una vez por segundo) o la vista.
    // Pintar miles de curvas 30 veces por segundo es lo que ahogaba a los computadores modestos.
    const ancho = Math.max(3.4, 5.2 * px);
    const grosor = Math.max(2.2, (conBase ? 3.6 : 2.8) * px);
    const clave = `${cv.width}x${cv.height}|${k.toFixed(6)}|${ox.toFixed(2)}|${oy.toFixed(2)}|${conBase ? 1 : 0}|${capas.congestion ? 1 : 0}|${cc.firma}|${geo.vias.length}`;
    const est = estatica.current;
    if (!est.cv || est.cv.width !== cv.width || est.cv.height !== cv.height) {
      est.cv = document.createElement('canvas');
      est.cv.width = cv.width;
      est.cv.height = cv.height;
      est.clave = '';
    }
    if (est.clave !== clave) {
      const sc = est.cv.getContext('2d')!;
      sc.setTransform(1, 0, 0, 1, 0, 0);
      sc.clearRect(0, 0, est.cv.width, est.cv.height);
      sc.setTransform(dpr * k, 0, 0, dpr * k, dpr * ox, dpr * oy);
      sc.lineCap = 'round';
      sc.lineJoin = 'round';
      // sin mapa base se dibujan las calles (con mapa base ya vienen en la imagen)
      if (!conBase) {
        if (!todas.current) { todas.current = new Path2D(); for (const p of rutas.current) todas.current.addPath(p); }
        sc.lineWidth = ancho + Math.max(1.0, 1.6 * px);
        sc.strokeStyle = '#b6bfcb';
        sc.stroke(todas.current);
        sc.lineWidth = ancho;
        sc.strokeStyle = '#ffffff';
        sc.stroke(todas.current);
      }
      // tráfico: una línea de color sobre cada calle (un trazo por color)
      if (capas.congestion) {
        sc.globalAlpha = 0.92;
        sc.lineWidth = grosor;
        for (let n = 0; n < 4; n++) {
          sc.strokeStyle = COLOR_NIVEL[n];
          sc.stroke(cc.porNivel[n]);
        }
        sc.globalAlpha = 1;
      }
      // sentido de las calles de un solo sentido
      if (k > 0.45) {
        sc.fillStyle = 'rgba(32,33,36,0.55)';
        for (const g of geo.vias) {
          if (!g.unSentido || g.largo < 40) continue;
          const p = puntoEnVia(g, g.via.longitud * 0.5), sz = Math.max(2.4, 3.2 * px);
          sc.save();
          sc.translate(p.x, p.y);
          sc.rotate(p.ang);
          sc.beginPath();
          sc.moveTo(sz, 0);
          sc.lineTo(-sz * 0.7, sz * 0.8);
          sc.lineTo(-sz * 0.7, -sz * 0.8);
          sc.closePath();
          sc.fill();
          sc.restore();
        }
      }
      est.clave = clave;
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(est.cv, 0, 0);
    ctx.setTransform(dpr * k, 0, 0, dpr * k, dpr * ox, dpr * oy);

    // selección y hover
    const resaltar = (sel: { tipo: string; id: string } | null | undefined, color: string, extra: number) => {
      if (!sel) return;
      if (sel.tipo === 'via') {
        const i = geo.indiceVia.get(sel.id);
        if (i === undefined) return;
        ctx.strokeStyle = color;
        ctx.lineWidth = grosor + extra * px;
        ctx.globalAlpha = 0.45;
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
    resaltar(hover.current, '#1a73e8', 5);
    resaltar(pr.seleccion, '#1a73e8', 9);

    // ruta de navegación: las alternativas en gris y la elegida en azul, con borde blanco
    const rt = pr.ruta;
    if (rt) {
      const trazarRuta = (pts: number[]) => {
        ctx.beginPath();
        ctx.moveTo(pts[0], pts[1]);
        for (let i = 2; i < pts.length; i += 2) ctx.lineTo(pts[i], pts[i + 1]);
      };
      for (const pasada of [false, true]) {
        for (const o of rt.opciones) {
          if (o.sel !== pasada || o.pts.length < 4) continue;
          trazarRuta(o.pts);
          ctx.lineWidth = Math.max(5.5, (o.sel ? 9 : 7) * px);
          ctx.strokeStyle = '#ffffff';
          ctx.stroke();
          trazarRuta(o.pts);
          ctx.lineWidth = Math.max(3.4, (o.sel ? 6 : 4.4) * px);
          ctx.strokeStyle = o.sel ? '#1a73e8' : '#9aa0a6';
          ctx.stroke();
        }
      }
    }

    // rutas prioritarias de emergencias
    if (inst && capas.rutas) {
      for (const e of inst.e) {
        if (e.atendiendo) continue;
        const g0 = geo.vias[e.via];
        if (!g0) continue;
        const trazar = () => {
          ctx.beginPath();
          const inicio = puntoEnVia(g0, e.pos);
          ctx.moveTo(inicio.x, inicio.y);
          const d0 = Math.min(g0.largo, e.pos * g0.escala);
          for (let j = 1; j < g0.acum.length; j++) if (g0.acum[j] > d0) ctx.lineTo(g0.pts[j * 2], g0.pts[j * 2 + 1]);
          for (let r = 1; r < e.ruta.length; r++) {
            const g = geo.vias[e.ruta[r]];
            if (!g) continue;
            for (let j = 0; j < g.pts.length; j += 2) ctx.lineTo(g.pts[j], g.pts[j + 1]);
          }
        };
        ctx.save();
        ctx.lineWidth = Math.max(5, 7 * px);
        ctx.strokeStyle = 'rgba(255,255,255,0.9)';
        trazar();
        ctx.stroke();
        ctx.lineWidth = Math.max(3, 4.2 * px);
        ctx.strokeStyle = '#1a73e8';
        ctx.setLineDash([10 * px, 6 * px]);
        ctx.lineDashOffset = (-ahora / 40) * px;
        trazar();
        ctx.stroke();
        ctx.restore();
      }
    }

    // intersecciones: en el editor todas; en la consola solo entradas, salidas y bases
    const rn = Math.max(3.4, 4.6 * px);
    for (const n of geo.nodos) {
      if (n.servicios.length > 0) continue;
      const entrada = n.tipo === 'ENTRADA_SALIDA';
      if (!pr.editor && !entrada) continue;
      ctx.beginPath();
      ctx.arc(n.x, n.y, entrada ? rn * 1.15 : rn, 0, Math.PI * 2);
      ctx.fillStyle = entrada ? '#1a73e8' : '#ffffff';
      ctx.fill();
      ctx.strokeStyle = entrada ? '#ffffff' : '#5f6368';
      ctx.lineWidth = 1.6 * px;
      ctx.stroke();
    }

    // semáforos: una luz en la línea de pare de cada aproximación
    if (inst && capas.semaforos && k > 0.35) {
      const r = Math.max(2.3, 3.2 * px);
      for (const s of inst.s) {
        const manual = s[2] >= 0;
        for (const vi of geo.entrantes[s[0]] ?? []) {
          const g = geo.vias[vi];
          const p = puntoEnVia(g, Math.max(0, g.via.longitud - 3.5));
          const nx = -Math.sin(p.ang) * 3.4, ny = Math.cos(p.ang) * 3.4;
          const luz = luzDeFase(s[1], g.grupo);
          ctx.beginPath();
          ctx.arc(p.x + nx, p.y + ny, r, 0, Math.PI * 2);
          ctx.fillStyle = COLOR_LUZ[luz];
          ctx.fill();
          ctx.lineWidth = 1.1 * px;
          ctx.strokeStyle = manual ? '#202124' : '#ffffff';
          ctx.stroke();
        }
      }
    }

    // bases de emergencia
    for (const n of geo.nodos) {
      if (n.servicios.length === 0) continue;
      const r = Math.max(7, 9.5 * px);
      ctx.beginPath();
      ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
      ctx.fillStyle = n.servicios.includes('hospital') ? '#d93025' : '#1a73e8';
      ctx.fill();
      ctx.lineWidth = 2 * px;
      ctx.strokeStyle = '#ffffff';
      ctx.stroke();
      ctx.font = `700 ${Math.max(7, 9.5 * px)}px system-ui, sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillStyle = '#ffffff';
      ctx.fillText(n.servicios.map((x) => (x === 'hospital' ? 'H' : x === 'bomberos' ? 'B' : 'P')).join(''), n.x, n.y + 0.4 * px);
    }

    if (inst) {
      const dt = inst.corriendo ? Math.min(0.15, Math.max(0, (ahora - recibido) / 1000)) * inst.velocidad : 0;
      // vehículos
      if (capas.vehiculos) {
        const detalle = k > 0.18;
        for (const v of inst.v) {
          const g = geo.vias[v[1]];
          if (!g) continue;
          const p = puntoEnVia(g, Math.min(g.via.longitud, v[2] + v[3] * dt));
          const t = v[4];
          const largo = Math.max(LARGO[t], (detalle ? 6.5 : 4) * px), anch = Math.max(ANCHO[t], (detalle ? 3.4 : 2.4) * px);
          ctx.save();
          ctx.translate(p.x - Math.cos(p.ang) * largo * 0.5, p.y - Math.sin(p.ang) * largo * 0.5);
          ctx.rotate(p.ang);
          ctx.fillStyle = COLOR_VEHICULO[t === 1 ? 1 : t === 2 ? 2 : 0];
          ctx.fillRect(-largo / 2, -anch / 2, largo, anch);
          if (detalle) {
            ctx.lineWidth = 0.9 * px;
            ctx.strokeStyle = '#ffffff';
            ctx.strokeRect(-largo / 2, -anch / 2, largo, anch);
          }
          ctx.restore();
        }
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
        ctx.fillStyle = e.tipo === 4 ? '#d93025' : e.tipo === 5 ? '#1967d2' : '#ffffff';
        ctx.fillRect(-largo / 2, -anch / 2, largo, anch);
        ctx.lineWidth = 1.2 * px;
        ctx.strokeStyle = '#202124';
        ctx.strokeRect(-largo / 2, -anch / 2, largo, anch);
        ctx.fillStyle = destello ? '#d93025' : '#1a73e8';
        ctx.fillRect(-largo * 0.12, -anch / 2, largo * 0.24, anch);
        ctx.restore();
      }
      // incidentes: marcador redondo con signo de exclamación
      for (const i of inst.i) {
        const g = geo.vias[i.via];
        if (!g) continue;
        const p = puntoEnVia(g, i.pos);
        const pulso = 1 + 0.12 * Math.sin(ahora / 200);
        const r = Math.max(8, 10.5 * px) * pulso;
        ctx.beginPath();
        ctx.arc(p.x, p.y, r * 1.8, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(217,48,37,0.16)';
        ctx.fill();
        ctx.beginPath();
        ctx.arc(p.x, p.y, r, 0, Math.PI * 2);
        ctx.fillStyle = i.estado === 'EN_ATENCION' ? '#1a73e8' : COLOR_GRAVEDAD[i.gravedad];
        ctx.fill();
        ctx.lineWidth = 2 * px;
        ctx.strokeStyle = '#ffffff';
        ctx.stroke();
        ctx.fillStyle = '#ffffff';
        ctx.font = `800 ${r * 1.2}px system-ui, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText('!', p.x, p.y + r * 0.08);
      }
    }

    // puntos críticos de accidentalidad (datos abiertos)
    if (capas.calor && pr.criticos) {
      pr.criticos.slice(0, 10).forEach((c, i) => {
        ctx.beginPath();
        ctx.arc(c.x, c.y, Math.max(9, 11.5 * px), 0, Math.PI * 2);
        ctx.fillStyle = '#ffffff';
        ctx.fill();
        ctx.strokeStyle = '#d93025';
        ctx.lineWidth = 2.2 * px;
        ctx.stroke();
        ctx.fillStyle = '#d93025';
        ctx.font = `700 ${Math.max(9, 11.5 * px)}px system-ui, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(String(i + 1), c.x, c.y + 0.4 * px);
      });
    }

    // origen, destino y vehículo guiado de la navegación
    if (rt) {
      const pin = (pt: { x: number; y: number }, letra: string, color: string) => {
        const r = Math.max(10, 12.5 * px);
        ctx.beginPath();
        ctx.arc(pt.x, pt.y, r, 0, Math.PI * 2);
        ctx.fillStyle = color;
        ctx.fill();
        ctx.lineWidth = 2.4 * px;
        ctx.strokeStyle = '#ffffff';
        ctx.stroke();
        ctx.fillStyle = '#ffffff';
        ctx.font = `700 ${r * 1.15}px system-ui, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        ctx.fillText(letra, pt.x, pt.y + r * 0.06);
      };
      if (rt.origen) pin(rt.origen, 'A', '#1a73e8');
      if (rt.destino) pin(rt.destino, 'B', '#d93025');
      if (rt.guia && inst) {
        const dtG = inst.corriendo ? Math.min(0.15, Math.max(0, (ahora - recibido) / 1000)) * inst.velocidad : 0;
        const prog = Math.max(0, Math.min(1, (inst.t + dtG - rt.guia.t0) / rt.guia.duracion));
        const q = puntoEnPolilinea(rt.guia.pts, prog * largoPolilinea(rt.guia.pts));
        const r = Math.max(9, 11 * px);
        ctx.beginPath();
        ctx.arc(q.x, q.y, r * 1.9, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(26,115,232,0.2)';
        ctx.fill();
        ctx.beginPath();
        ctx.arc(q.x, q.y, r, 0, Math.PI * 2);
        ctx.fillStyle = '#1a73e8';
        ctx.fill();
        ctx.lineWidth = 2.6 * px;
        ctx.strokeStyle = '#ffffff';
        ctx.stroke();
        ctx.save();
        ctx.translate(q.x, q.y);
        ctx.rotate(q.ang);
        ctx.beginPath();
        ctx.moveTo(r * 0.62, 0);
        ctx.lineTo(-r * 0.38, r * 0.5);
        ctx.lineTo(-r * 0.38, -r * 0.5);
        ctx.closePath();
        ctx.fillStyle = '#ffffff';
        ctx.fill();
        ctx.restore();
      }
    }

    // intersecciones marcadas para la onda verde
    (pr.marcados ?? []).forEach((idn, i) => {
      const n = geo.indiceNodo.get(idn);
      if (n === undefined) return;
      const nodo = geo.nodos[n];
      ctx.beginPath();
      ctx.arc(nodo.x, nodo.y, 11 * px, 0, Math.PI * 2);
      ctx.fillStyle = '#1a73e8';
      ctx.fill();
      ctx.lineWidth = 2 * px;
      ctx.strokeStyle = '#ffffff';
      ctx.stroke();
      ctx.fillStyle = '#ffffff';
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
