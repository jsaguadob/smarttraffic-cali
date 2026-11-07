import type { Usuario } from './tipos';

const CLAVE_TOKEN = 'smarttraffic_token';
/** Dirección del servidor Java. Vacía = el mismo sitio (lo normal cuando el servidor entrega la pantalla). En Netlify se define con VITE_API_URL. */
export const BASE: string = (import.meta.env.VITE_API_URL ?? '').replace(/\/+$/, '');
let alExpirar: (() => void) | null = null;

export class ErrorApi extends Error {
  estado: number;
  constructor(estado: number, mensaje: string) {
    super(mensaje);
    this.estado = estado;
  }
}

export const sesion = {
  token: (): string | null => sessionStorage.getItem(CLAVE_TOKEN),
  guardar: (t: string): void => sessionStorage.setItem(CLAVE_TOKEN, t),
  borrar: (): void => sessionStorage.removeItem(CLAVE_TOKEN),
  alExpirar: (f: () => void): void => {
    alExpirar = f;
  },
};

export async function api<T>(metodo: string, ruta: string, cuerpo?: unknown): Promise<T> {
  const cab: Record<string, string> = {};
  const token = sesion.token();
  if (token) cab['Authorization'] = 'Bearer ' + token;
  if (cuerpo !== undefined) cab['Content-Type'] = 'application/json';
  let r: Response;
  try {
    r = await fetch(BASE + ruta, { method: metodo, headers: cab, body: cuerpo === undefined ? undefined : typeof cuerpo === 'string' ? cuerpo : JSON.stringify(cuerpo) });
  } catch {
    throw new ErrorApi(0, 'No se pudo conectar con el servidor. Revisa que esté en marcha.');
  }
  let datos: unknown = null;
  const texto = await r.text();
  try {
    datos = texto ? JSON.parse(texto) : null;
  } catch {
    datos = null;
  }
  if (!r.ok) {
    const msg = (datos as { error?: string } | null)?.error ?? `Error ${r.status}`;
    if (r.status === 401 && token && alExpirar && !ruta.includes('/auth/login')) alExpirar();
    throw new ErrorApi(r.status, msg);
  }
  return datos as T;
}

export async function ingresar(correo: string, clave: string): Promise<Usuario> {
  const r = await api<{ token: string; usuario: Usuario }>('POST', '/api/auth/login', { correo, clave });
  sesion.guardar(r.token);
  return r.usuario;
}

/** Formato mm:ss para un tiempo en segundos. */
export function reloj(segundos: number): string {
  const s = Math.max(0, Math.floor(segundos));
  return String(Math.floor(s / 60)).padStart(2, '0') + ':' + String(s % 60).padStart(2, '0');
}

export function numero(n: number, decimales = 0): string {
  return n.toLocaleString('es-CO', { minimumFractionDigits: decimales, maximumFractionDigits: decimales });
}
