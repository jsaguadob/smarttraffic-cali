import { useState } from 'react';
import type { FormEvent, ReactElement } from 'react';
import { ingresar } from './api';
import { ILUSTRACION as I } from './ilustracion';
import type { Usuario } from './tipos';

export function Marca({ tam = 28 }: { tam?: number }): ReactElement {
  return (
    <svg width={tam} height={tam} viewBox="0 0 64 64" aria-hidden="true">
      <rect width="64" height="64" rx="14" fill="#1a73e8" />
      <rect x="24" y="9" width="16" height="46" rx="8" fill="#ffffff" />
      <circle cx="32" cy="20" r="4.5" fill="#d93025" />
      <circle cx="32" cy="32" r="4.5" fill="#fbbc04" />
      <circle cx="32" cy="44" r="4.5" fill="#34a853" />
    </svg>
  );
}

const DEMO = [
  { rol: 'Administrador', correo: 'admin@smarttraffic.test', detalle: 'edita la red y los usuarios' },
  { rol: 'Operador de movilidad', correo: 'operador@smarttraffic.test', detalle: 'controla la simulación e incidentes' },
  { rol: 'Analista', correo: 'analista@smarttraffic.test', detalle: 'consulta y compara' },
];

export function Login({ demo, alIngresar }: { demo: boolean; alIngresar: (u: Usuario) => void }): ReactElement {
  const [correo, setCorreo] = useState('');
  const [clave, setClave] = useState('');
  const [error, setError] = useState('');
  const [cargando, setCargando] = useState(false);

  const entrar = async (c: string, k: string) => {
    setError('');
    setCargando(true);
    try {
      alIngresar(await ingresar(c, k));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No se pudo ingresar');
    } finally {
      setCargando(false);
    }
  };

  const enviar = (ev: FormEvent) => {
    ev.preventDefault();
    void entrar(correo, clave);
  };

  return (
    <main className="ingreso">
      <section className="ingreso-mapa" aria-hidden="true">
        <svg viewBox={`0 0 ${I.ancho} ${I.alto}`} preserveAspectRatio="xMidYMid slice" focusable="false">
          <rect width={I.ancho} height={I.alto} fill="#e8ecf1" />
          <g fill="none" strokeLinecap="round" strokeLinejoin="round">
            <path d={I.calles} stroke="#bcc4cf" strokeWidth="17" />
            <path d={I.calles} stroke="#ffffff" strokeWidth="12" />
            <path d={I.calles} stroke="#34a853" strokeWidth="4.5" opacity="0.9" />
            <path d={I.amarillo} stroke="#fbbc04" strokeWidth="4.5" />
            <path d={I.rojo} stroke="#d93025" strokeWidth="4.5" />
            <path d={I.ruta} stroke="#ffffff" strokeWidth="17" />
            <path d={I.ruta} stroke="#1a73e8" strokeWidth="10" />
          </g>
          <g fontFamily="system-ui, sans-serif" fontWeight="700" fontSize="21" textAnchor="middle" fill="#fff">
            <circle cx={I.a[0]} cy={I.a[1]} r="20" fill="#1a73e8" stroke="#fff" strokeWidth="4" /><text x={I.a[0]} y={I.a[1] + 7}>A</text>
            <circle cx={I.b[0]} cy={I.b[1]} r="20" fill="#d93025" stroke="#fff" strokeWidth="4" /><text x={I.b[0]} y={I.b[1] + 7}>B</text>
          </g>
        </svg>
      </section>
      <section className="ingreso-form">
        <div className="ingreso-contenido">
          <div className="marca"><Marca tam={30} />SmartTraffic</div>
          <h1>Iniciar sesión</h1>
          <p className="subtitulo">Navegación y control de movilidad en Cali</p>
          <form onSubmit={enviar}>
            <label className="campo">Correo electrónico<input type="email" autoComplete="username" value={correo} onChange={(e) => setCorreo(e.target.value)} required autoFocus /></label>
            <label className="campo">Contraseña<input type="password" autoComplete="current-password" value={clave} onChange={(e) => setClave(e.target.value)} required /></label>
            {error && <div className="error" role="alert">{error}</div>}
            <button className="boton primario" disabled={cargando}>{cargando ? 'Ingresando…' : 'Iniciar sesión'}</button>
          </form>
          {demo && (
            <details className="demo-roles">
              <summary>Modo demostración · cuentas de prueba</summary>
              <p>Solo para probar. No uses estas cuentas en un servidor público.</p>
              {DEMO.map((d) => (
                <button key={d.correo} type="button" disabled={cargando} onClick={() => void entrar(d.correo, 'SmartTraffic2026')}>
                  <b>{d.rol}</b><span>{d.detalle}</span>
                </button>
              ))}
            </details>
          )}
        </div>
        <footer>Proyecto académico de Estructuras de Datos · Mapas © colaboradores de OpenStreetMap</footer>
      </section>
    </main>
  );
}
