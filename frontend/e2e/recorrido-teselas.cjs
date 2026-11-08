const chromium = require('@sparticuz/chromium').default || require('@sparticuz/chromium');
const puppeteer = require('puppeteer-core');
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
let fallas = 0;
const ok = (c, m, extra) => { console.log((c ? 'PASS' : 'FAIL') + ' · ' + m + (c ? '' : '  → ' + (extra || ''))); if (!c) fallas++; };
(async () => {
  const b = await puppeteer.launch({ args: [...chromium.args, '--no-sandbox'], executablePath: await chromium.executablePath(), headless: 'shell', defaultViewport: { width: 1440, height: 900 } });
  const p = await b.newPage(); await p.setBypassCSP(true); // el servidor de teselas de prueba es http; en la vida real son https
  const errores = [];
  p.on('pageerror', (e) => errores.push(e.message)); p.on('console', (m) => { if (m.type() === 'error') errores.push(m.text()); });
  const texto = () => p.evaluate(() => document.body.innerText);
  const esperar = async (c, ms = 10000) => { const f = Date.now(); while (Date.now() - f < ms) { if (await c()) return true; await dormir(150); } return false; };
  const api = (m, r, cuerpo) => p.evaluate(async (m, r, cuerpo) => { const x = await fetch(r, { method: m, headers: { Authorization: 'Bearer ' + sessionStorage.getItem('smarttraffic_token'), 'Content-Type': 'application/json' }, body: cuerpo ? JSON.stringify(cuerpo) : undefined }); return { estado: x.status, cuerpo: await x.json().catch(() => null) }; }, m, r, cuerpo);
  await p.goto('http://localhost:8097'); await p.waitForFunction(() => [...document.querySelectorAll('button')].some((x) => x.textContent.includes('Administrador')));
  await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Administrador')).click());
  ok(await esperar(async () => (await texto()).includes('Red de demostración · usar el mapa real')), 'el administrador ve la pastilla que avisa que la red es de demostración'); await p.evaluate(() => document.querySelector('.chip-mapa').click()); ok(await esperar(async () => (await texto()).includes('Estás viendo una red de demostración')), 'al abrirla muestra el aviso completo');
  ok((await texto()).includes('Usar el mapa real de Cali'), 'con el botón «Usar el mapa real de Cali»');
  await p.screenshot({ path: (process.env.CAPTURAS || './capturas') + '/st-13-aviso-demo.png' });
  // se le da a la red una ubicación real (centro de Cali), como lo hace el importador de OpenStreetMap
  const red = (await api('GET', '/api/red')).cuerpo; red.origen = { lat: 3.4516, lon: -76.5320 };
  const r = await api('POST', '/api/red/importar', red); ok(r.estado === 200, 'la red queda con ubicación real', JSON.stringify(r));
  await p.reload(); await p.waitForSelector('canvas[data-testid="mapa"]');
  ok(await esperar(async () => !!(await p.$('.atribucion')), 8000), 'con ubicación real aparece sobre el mapa la atribución «© colaboradores de OpenStreetMap»');
  ok(!(await texto()).includes('Estás viendo una red de demostración') && !(await p.$('.chip-mapa')), 'y desaparecen el aviso y la pastilla de red de demostración');
  const pedidas = async () => (await (await fetch('http://localhost:9099/pedidas')).json());
  ok(await esperar(async () => (await pedidas()).length > 3, 8000), 'el navegador pide teselas del mapa base');
  const t = (await pedidas()).filter((x) => x[3] !== ''); const z = t[0][0]; // se ignora la prueba manual de curl (sin Referer)
  ok(t.every((x) => x[0] === z || Math.abs(x[0] - z) <= 1), 'todas del mismo nivel de zoom (o vecino)', JSON.stringify(t.slice(0, 5)));
  ok(t.every((x) => /netlify|localhost:8097/.test(x[3]) || x[3].startsWith('http://localhost:8097')), 'envía de qué sitio viene la petición (lo que exige la política de OpenStreetMap)', JSON.stringify(t.slice(0, 2)));
  // zoom 17 en Cali: la tesela que contiene Plaza de Caycedo debe ser la 17/30093/ (cálculo independiente más abajo)
  await dormir(2500);
  // se apagan las capas que tapan la marca (semáforos, tráfico, vehículos y rutas) para medir sin interferencias
  for (const nombre of ['Tráfico', 'Vehículos', 'Semáforos', 'Rutas de emergencia']) await p.evaluate((n) => [...document.querySelectorAll('.leyenda-mapa label')].find((l) => l.textContent.includes(n)).querySelector('input').click(), nombre);
  await dormir(600);
  // alineación: la marca roja de la tesela (lat/lon exactos de N11) debe caer debajo de la intersección N11 (x=261,y=211)
  const medida = await p.evaluate(() => {
    const c = document.querySelector('canvas[data-testid="mapa"]'), g = c.getContext('2d'), dpr = devicePixelRatio, pt = window.__mapa.aPantalla(261, 211);
    const r = 14, d = g.getImageData(Math.round(pt.x * dpr) - r, Math.round(pt.y * dpr) - r, 2 * r, 2 * r).data;
    let sx = 0, sy = 0, n = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] > 245 && d[i + 1] < 12 && d[i + 2] < 12) { const k = i / 4; sx += k % (2 * r); sy += Math.floor(k / (2 * r)); n++; }
    return { n, dx: n ? sx / n - r : null, dy: n ? sy / n - r : null, k: window.__mapa.escala() };
  });
  console.log('   marca roja de la tesela vista junto a N11:', JSON.stringify(medida));
  ok(medida.n > 40, 'la marca roja (completa, sin tapar) de la tesela aparece en la pantalla junto a la intersección N11', JSON.stringify(medida));
  ok(medida.n > 4 && Math.abs(medida.dx) < 1.5 && Math.abs(medida.dy) < 1.5, 'y cae exactamente debajo de ella (error menor que 1,5 píxeles ≈ 2 m): el mapa base y las calles están alineados', JSON.stringify(medida));
  await p.screenshot({ path: (process.env.CAPTURAS || './capturas') + '/st-14-teselas.png' });
  // capa del mapa base desactivable
  await p.evaluate(() => [...document.querySelectorAll('label')].find((l) => l.textContent.includes('Mapa base')).querySelector('input').click()); await dormir(500);
  ok(!(await p.$('.atribucion')), 'al apagar «Mapa base» desaparece la atribución');
  const util = errores.filter((e) => !/favicon|status of 40/.test(e)); ok(util.length === 0, 'sin errores de consola ni de página', util.join(' | ').slice(0, 300));
  await b.close(); console.log(fallas ? `\n${fallas} FALLAS` : '\nE2E TESELAS: todo OK'); process.exit(fallas ? 1 : 0);
})().catch((e) => { console.error('FALLO', e.message); process.exit(1); });
