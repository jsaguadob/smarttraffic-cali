import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactElement } from 'react';
import { api, BASE, sesion } from './api';
import { construirGeo } from './geometria';
import type { FuenteInstantanea } from './MapaTrafico';
import { Login, Marca } from './Login';
import { Consola } from './Consola';
import { Editor } from './Editor';
import { Comparar } from './Comparar';
import { Usuarios } from './Usuarios';
import type { Instantanea, Red, Usuario } from './tipos';

type Pagina = 'consola' | 'comparar' | 'editor' | 'usuarios';
interface Config {
  nombre: string;
  version: string;
  atribucion: string;
  coordenadasReales: boolean;
  demo: boolean;
}

export function App(): ReactElement {
  const [usuario, setUsuario] = useState<Usuario | null>(null);
  const [cargandoSesion, setCargandoSesion] = useState(true);
  const [config, setConfig] = useState<Config | null>(null);
  const [red, setRed] = useState<Red | null>(null);
  const [inst, setInst] = useState<Instantanea | null>(null);
  const [conectado, setConectado] = useState(false);
  const [pagina, setPagina] = useState<Pagina>('consola');
  const fuente = useRef<FuenteInstantanea>({ inst: null, recibido: 0 });

  const salir = useCallback(() => {
    sesion.borrar();
    setUsuario(null);
    setRed(null);
    setInst(null);
    fuente.current = { inst: null, recibido: 0 };
  }, []);

  useEffect(() => {
    sesion.alExpirar(salir);
    void api<Config>('GET', '/api/config').then(setConfig).catch(() => undefined);
    if (sesion.token()) {
      api<Usuario>('GET', '/api/auth/yo').then(setUsuario).catch(() => sesion.borrar()).finally(() => setCargandoSesion(false));
    } else {
      setCargandoSesion(false);
    }
  }, [salir]);

  const cargarRed = useCallback(async () => {
    setRed(await api<Red>('GET', '/api/red'));
  }, []);

  useEffect(() => {
    if (!usuario) return;
    void cargarRed();
    api<Instantanea>('GET', '/api/sim/estado').then((d) => { fuente.current = { inst: d, recibido: performance.now() }; setInst(d); }).catch(() => undefined);
    const es = new EventSource(BASE + '/api/sim/stream?token=' + encodeURIComponent(sesion.token() ?? ''));
    let ultimo = 0;
    es.onopen = () => setConectado(true);
    es.onerror = () => setConectado(false);
    es.onmessage = (m) => {
      const d = JSON.parse(m.data as string) as Instantanea;
      const ahora = performance.now();
      fuente.current = { inst: d, recibido: ahora };
      if (ahora - ultimo > 250) { ultimo = ahora; setInst(d); }
    };
    return () => es.close();
  }, [usuario, cargarRed]);

  // la página activa se guarda en la dirección (#/comparar) para poder volver a ella
  useEffect(() => {
    const leer = () => {
      const h = location.hash.replace('#/', '') as Pagina;
      if (['consola', 'comparar', 'editor', 'usuarios'].includes(h)) setPagina(h);
    };
    leer();
    window.addEventListener('hashchange', leer);
    return () => window.removeEventListener('hashchange', leer);
  }, []);

  const geo = useMemo(() => (red ? construirGeo(red) : null), [red]);
  const ir = (p: Pagina) => { location.hash = '#/' + p; setPagina(p); };

  if (cargandoSesion) return <div className="ingreso">Cargando…</div>;
  if (!usuario) return <Login demo={config?.demo ?? false} alIngresar={(u) => { setUsuario(u); }} />;

  const esAdmin = usuario.rol === 'ADMINISTRADOR';
  const paginas: [Pagina, string, boolean][] = [['consola', 'Centro de control', true], ['comparar', 'Comparar escenarios', true], ['editor', 'Editor de red', esAdmin], ['usuarios', 'Usuarios', esAdmin]];
  const visible = paginas.some(([p, , ok]) => p === pagina && ok) ? pagina : 'consola';

  return (
    <div className="app">
      <header className="cabecera">
        <div className="marca"><Marca />SmartTraffic</div>
        <nav className="nav" aria-label="Secciones">
          {paginas.filter(([, , ok]) => ok).map(([p, t]) => (
            <button key={p} aria-current={visible === p ? 'page' : undefined} onClick={() => ir(p)}>{t}</button>
          ))}
        </nav>
        <span className={'estado-red' + (conectado ? '' : ' caida')} title="Conexión en vivo con el servidor"><i />{conectado ? 'En vivo' : 'Sin conexión'}</span>
        <div className="usuario">
          <span className="nombre"><b>{usuario.nombre}</b></span>
          <span className="rol">{usuario.rol}</span>
          <button className="boton chico" onClick={salir}>Salir</button>
        </div>
      </header>
      <div className="contenido">
        {!geo || !inst ? (
          <div className="pagina"><div className="aviso">Cargando la red vial…</div></div>
        ) : visible === 'consola' ? (
          <Consola geo={geo} fuente={fuente} inst={inst} usuario={usuario} conectado={conectado} coordenadasReales={config?.coordenadasReales ?? false} />
        ) : visible === 'comparar' ? (
          <Comparar geo={geo} />
        ) : visible === 'editor' ? (
          <Editor red={red!} recargar={cargarRed} />
        ) : (
          <Usuarios yo={usuario} />
        )}
      </div>
      <footer className="pie">
        <span>SmartTraffic {config?.version} · código abierto (MIT)</span>
        {config?.coordenadasReales ? <span>Mapa © colaboradores de OpenStreetMap (ODbL)</span> : <span>Red de demostración ficticia: para el mapa real de Cali importa los datos de OpenStreetMap</span>}
        <span>El flujo mostrado es una estimación de un modelo, no una medición del tráfico real.</span>
      </footer>
    </div>
  );
}
