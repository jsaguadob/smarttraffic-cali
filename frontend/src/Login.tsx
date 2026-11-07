import { useState } from 'react';
import type { FormEvent, ReactElement } from 'react';
import { ingresar } from './api';
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
      <div className="tarjeta">
        <div className="marca"><Marca tam={34} />SmartTraffic</div>
        <p>Centro de control de movilidad urbana. Ingresa con tu cuenta.</p>
        <form onSubmit={enviar} style={{ display: 'grid', gap: 12 }}>
          <label className="campo">Correo<input type="email" autoComplete="username" value={correo} onChange={(e) => setCorreo(e.target.value)} required autoFocus /></label>
          <label className="campo">Contraseña<input type="password" autoComplete="current-password" value={clave} onChange={(e) => setClave(e.target.value)} required /></label>
          {error && <div className="error" role="alert">{error}</div>}
          <button className="boton primario" disabled={cargando}>{cargando ? 'Ingresando…' : 'Ingresar'}</button>
        </form>
        {demo && (
          <div className="demo-roles">
            <div className="aviso alerta">Modo demostración: cuentas de prueba, no usar en producción.</div>
            {DEMO.map((d) => (
              <button key={d.correo} className="boton" disabled={cargando} onClick={() => void entrar(d.correo, 'SmartTraffic2026')}>
                <b>{d.rol}</b>&nbsp;· {d.detalle}
              </button>
            ))}
          </div>
        )}
      </div>
    </main>
  );
}
