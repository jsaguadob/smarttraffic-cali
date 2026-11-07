import { useCallback, useEffect, useState } from 'react';
import type { FormEvent, ReactElement } from 'react';
import { api } from './api';
import type { Rol, Usuario } from './tipos';

const ROLES: [Rol, string][] = [['ADMINISTRADOR', 'Administrador'], ['OPERADOR', 'Operador de movilidad'], ['ANALISTA', 'Analista']];

export function Usuarios({ yo }: { yo: Usuario }): ReactElement {
  const [lista, setLista] = useState<Usuario[]>([]);
  const [error, setError] = useState('');
  const [ok, setOk] = useState('');
  const [nombre, setNombre] = useState('');
  const [correo, setCorreo] = useState('');
  const [clave, setClave] = useState('');
  const [rol, setRol] = useState<Rol>('OPERADOR');

  const cargar = useCallback(() => { void api<Usuario[]>('GET', '/api/usuarios').then(setLista).catch((e: Error) => setError(e.message)); }, []);
  useEffect(cargar, [cargar]);

  const crear = async (ev: FormEvent) => {
    ev.preventDefault();
    setError(''); setOk('');
    try {
      await api('POST', '/api/usuarios', { nombre, correo, clave, rol });
      setOk(`Usuario ${correo} creado`);
      setNombre(''); setCorreo(''); setClave('');
      cargar();
    } catch (e) { setError(e instanceof Error ? e.message : 'Error'); }
  };

  const cambiar = async (id: number, c: Record<string, unknown>, mensaje: string) => {
    setError(''); setOk('');
    try { await api('PUT', `/api/usuarios/${id}`, c); setOk(mensaje); cargar(); } catch (e) { setError(e instanceof Error ? e.message : 'Error'); cargar(); }
  };

  return (
    <div className="pagina">
      <div className="interior">
        <div className="cabeza-pagina">
          <h1>Usuarios</h1>
          <p>El <b>administrador</b> configura la red, los datos y los usuarios. El <b>operador de movilidad</b> ejecuta la simulación, registra incidentes y despacha emergencias. El <b>analista</b> consulta indicadores y compara escenarios, sin modificar nada.</p>
        </div>
        {error && <div className="error" role="alert">{error}</div>}
        {ok && <div className="aviso" role="status">{ok}</div>}
        <section className="tarjeta">
          <h2 style={{ marginBottom: 12 }}>Crear usuario</h2>
          <form className="formulario" onSubmit={(e) => void crear(e)}>
            <label className="campo">Nombre<input value={nombre} onChange={(e) => setNombre(e.target.value)} required /></label>
            <label className="campo">Correo<input type="email" value={correo} onChange={(e) => setCorreo(e.target.value)} required /></label>
            <label className="campo">Contraseña<input type="password" autoComplete="new-password" value={clave} onChange={(e) => setClave(e.target.value)} required /><small>Mínimo 10 caracteres, con letras y números.</small></label>
            <label className="campo">Rol<select value={rol} onChange={(e) => setRol(e.target.value as Rol)}>{ROLES.map(([r, t]) => <option key={r} value={r}>{t}</option>)}</select></label>
            <button className="boton primario">Crear usuario</button>
          </form>
        </section>
        <section className="tarjeta">
          <table className="tabla">
            <thead><tr><th>Nombre</th><th>Correo</th><th>Rol</th><th>Estado</th><th>Acciones</th></tr></thead>
            <tbody>
              {lista.map((u) => (
                <tr key={u.id}>
                  <td>{u.nombre}{u.id === yo.id && <span className="chip" style={{ marginLeft: 8 }}>tú</span>}</td>
                  <td>{u.correo}</td>
                  <td>
                    <select value={u.rol} aria-label={`Rol de ${u.nombre}`} disabled={u.id === yo.id} onChange={(e) => void cambiar(u.id, { rol: e.target.value }, `Rol de ${u.nombre} actualizado`)}>
                      {ROLES.map(([r, t]) => <option key={r} value={r}>{t}</option>)}
                    </select>
                  </td>
                  <td><span className={'chip ' + (u.activo ? 'ok' : 'peligro')}>{u.activo ? 'Activo' : 'Desactivado'}</span></td>
                  <td>
                    <div className="acciones">
                      <button className="boton chico" disabled={u.id === yo.id} onClick={() => void cambiar(u.id, { activo: !u.activo }, u.activo ? `${u.nombre} desactivado` : `${u.nombre} activado`)}>{u.activo ? 'Desactivar' : 'Activar'}</button>
                      <button className="boton chico" onClick={() => { const k = window.prompt(`Nueva contraseña para ${u.nombre} (mínimo 10 caracteres, con letras y números):`); if (k) void cambiar(u.id, { clave: k }, `Contraseña de ${u.nombre} actualizada`); }}>Cambiar contraseña</button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>
    </div>
  );
}
