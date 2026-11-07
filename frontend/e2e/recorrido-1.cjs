const chromium = require('@sparticuz/chromium').default || require('@sparticuz/chromium');
const puppeteer = require('puppeteer-core');
const URL = (process.env.ST_URL || 'http://localhost:8095'), OUT = (process.env.CAPTURAS || './capturas') + '/st-';
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
let fallas = 0;
const ok = (c, m, extra) => { console.log((c ? 'PASS' : 'FAIL') + ' · ' + m + (c ? '' : '  → ' + (extra || ''))); if (!c) fallas++; };

(async () => {
  const b = await puppeteer.launch({ args: [...chromium.args, '--no-sandbox'], executablePath: await chromium.executablePath(), headless: 'shell', defaultViewport: { width: 1440, height: 900 } });
  const p = await b.newPage();
  const errores = [];
  p.on('pageerror', (e) => errores.push('pageerror ' + e.message));
  p.on('console', (m) => { if (m.type() === 'error') errores.push('console ' + m.text()); });
  const texto = () => p.evaluate(() => document.body.innerText);
  const esperar = async (cond, ms = 8000) => { const f = Date.now(); while (Date.now() - f < ms) { if (await cond()) return true; await dormir(120); } return false; };
  const hayTexto = (t) => async () => (await texto()).toLowerCase().includes(t.toLowerCase());
  const clic = async (t, sel = 'button') => p.evaluate((t, sel) => { const e = [...document.querySelectorAll(sel)].find((x) => x.textContent.trim() === t || x.getAttribute('title')?.startsWith(t) || x.getAttribute('aria-label') === t); if (!e) return false; e.click(); return true; }, t, sel);
  const foto = (n) => p.screenshot({ path: OUT + n + '.png' });
  const api = (m, r, cuerpo) => p.evaluate(async (m, r, cuerpo) => { const x = await fetch(r, { method: m, headers: { Authorization: 'Bearer ' + sessionStorage.getItem('smarttraffic_token'), 'Content-Type': 'application/json' }, body: cuerpo ? JSON.stringify(cuerpo) : undefined }); return { estado: x.status, cuerpo: await x.json().catch(() => null) }; }, m, r, cuerpo);
  const salir = async () => { await clic('Salir'); await esperar(hayTexto('Ingresa con tu cuenta')); };
  const aPantalla = (x, y) => p.evaluate((x, y) => window.__mapa.aPantalla(x, y), x, y);
  const lienzo = async () => (await (await p.$('canvas[data-testid="mapa"]')).boundingBox());
  const clicEnMapa = async (x, y) => { const pt = await aPantalla(x, y), r = await lienzo(); await p.mouse.click(r.x + pt.x, r.y + pt.y); };
  const pixeles = () => p.evaluate(() => { const c = document.querySelector('canvas[data-testid="mapa"]'); const g = c.getContext('2d'); const d = g.getImageData(c.width * 0.3, c.height * 0.3, c.width * 0.4, c.height * 0.4).data; let n = 0; const bg = [232, 236, 241]; for (let i = 0; i < d.length; i += 4) if (Math.abs(d[i] - bg[0]) + Math.abs(d[i + 1] - bg[1]) + Math.abs(d[i + 2] - bg[2]) > 40) n++; return n; });

  // ------------------------------------------------ ingreso
  await p.goto(URL, { waitUntil: 'domcontentloaded' });
  ok(await esperar(hayTexto('Iniciar sesión')), 'abre la pantalla de ingreso'); await foto('1-ingreso');
  await p.evaluate(() => document.querySelector('details.demo-roles').setAttribute('open', '')); ok((await texto()).includes('Modo demostración') && (await texto()).includes('Analista'), 'en modo demostración ofrece (en un desplegable) entrar con un clic como cada rol');
  await p.type('input[type=email]', 'operador@smarttraffic.test'); await p.type('input[type=password]', 'incorrecta123'); await p.keyboard.press('Enter');
  ok(await esperar(hayTexto('Correo o contraseña incorrectos')), 'una contraseña mala muestra un mensaje claro y no revela nada más');

  // ------------------------------------------------ operador
  await clic('Operador de movilidad · controla la simulación e incidentes', 'button') || await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Operador de movilidad')).click());
  ok(await esperar(hayTexto('Cómo llegar'), 15000), 'el operador entra al centro de control (se abre en «Cómo llegar»)'); await clic('Resumen', 'button'); await esperar(hayTexto('Estado de la red'));
  ok(await esperar(async () => (await pixeles()) > 800, 8000), 'el mapa se dibuja (calles visibles en el lienzo)', 'píxeles ' + await pixeles()); await foto('2-consola-inicial');
  const red = (await api('GET', '/api/red')).cuerpo;
  ok(red.intersecciones.length === 18 && red.vias.length === 40, 'la red de demostración tiene 18 intersecciones y 40 tramos');

  await clic('Iniciar', 'button') || await clic('▶ Iniciar', 'button');
  await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.trim() === '16x')?.click());
  ok(await esperar(async () => /0[1-9]:\d\d|[1-9]\d:\d\d/.test(await p.$eval('.reloj', (e) => e.textContent)), 15000), 'el reloj de la simulación avanza', await p.$eval('.reloj', (e) => e.textContent));
  ok(await esperar(async () => Number(await p.$eval('[data-testid=kpi-activos]', (e) => e.textContent)) > 15, 15000), 'aparecen vehículos circulando', await p.$eval('[data-testid=kpi-activos]', (e) => e.textContent));
  await dormir(2500); await foto('3-trafico');
  ok((await pixeles()) > 3000, 'los vehículos y las calles se ven en el mapa');

  // hover sobre una calle
  const h12e = red.vias.find((v) => v.id === 'H12E'); const m = h12e.puntos; const mx = (m[0] + m[m.length - 2]) / 2, my = (m[1] + m[m.length - 1]) / 2;
  const pt = await aPantalla(mx, my), rc = await lienzo(); await p.mouse.move(rc.x + pt.x, rc.y + pt.y + 2); await p.mouse.move(rc.x + pt.x + 1, rc.y + pt.y + 2);
  ok(await esperar(async () => (await p.$$eval('.tooltip', (l) => l.map((e) => e.textContent).join(' '))).includes('Calle 13'), 3000), 'al pasar el mouse sobre una calle muestra su nombre y estado');
  // atajo de teclado y herramienta
  await p.mouse.move(rc.x + 5, rc.y + 5); await p.keyboard.press('a');
  ok(await p.$eval('button.herr[title^="Accidente"]', (e) => e.getAttribute('aria-pressed')) === 'true', 'la tecla A elige la herramienta de accidente');
  ok((await texto()).includes('Haz clic sobre una calle para provocar un accidente grave'), 'una pista explica qué hacer con la herramienta elegida');
  await clicEnMapa(mx, my);
  ok(await esperar(async () => (await texto()).includes('Incidentes (1)'), 6000), 'un clic sobre la calle registra un accidente (y aparece en el panel de incidentes)'); await dormir(1200);
  const estado = (await api('GET', '/api/sim/estado')).cuerpo;
  ok(estado.i.length === 1 && estado.i[0].gravedad === 3, 'el accidente es grave y quedó en el servidor', JSON.stringify(estado.i)); await foto('4-accidente');
  await clic('Eventos', 'button');
  ok(await esperar(async () => /Ambulancia EMG-\d+ sale de Hospital/.test(await texto()), 6000), 'se despacha una ambulancia desde el hospital', (await texto()).slice(-400));
  ok(/Bomberos EMG-\d+ sale de Estación/.test(await texto()), 'y los bomberos desde su estación (accidente grave)');
  await clic('Resumen', 'button');
  // emergencia manual
  await p.keyboard.press('e'); const n33 = red.intersecciones.find((n) => n.id === 'N33'); await clicEnMapa(n33.x, n33.y);
  ok(await esperar(async () => ((await api('GET', '/api/sim/estado')).cuerpo.ev || []).some((x) => /Ambulancia EMG-\d+ sale de .* hacia Calle 5 con Carrera 39/.test(x.msg)), 6000), 'la herramienta Ambulancia despacha un vehículo de emergencia a la intersección elegida');
  await dormir(3000); await foto('5-emergencias');
  // semáforos
  await clic('Tiempo fijo', 'button');
  ok(await esperar(async () => (await api('GET', '/api/sim/estado')).cuerpo.modo === 'FIJO', 4000), 'cambiar a semáforos de tiempo fijo se aplica en el servidor');
  await clic('Inteligente', 'button');
  ok(await esperar(async () => (await api('GET', '/api/sim/estado')).cuerpo.modo === 'INTELIGENTE', 4000), 'y volver a inteligentes también');
  // despejar incidente
  await clic('Incidentes (1)', 'button');
  await clic('Despejar', 'button');
  ok(await esperar(async () => (await api('GET', '/api/sim/estado')).cuerpo.i.length === 0, 5000), 'el operador puede despejar el accidente');
  await p.keyboard.press(' ');
  ok(await esperar(async () => (await api('GET', '/api/sim/estado')).cuerpo.corriendo === false, 4000), 'la barra espaciadora pausa la simulación');

  // ------------------------------------------------ analista: solo lectura
  await salir();
  await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Analista')).click());
  ok(await esperar(hayTexto('Solo lectura'), 10000), 'el analista ve la consola en solo lectura');
  ok(await p.$eval('button.herr[title^="Accidente"]', (e) => e.disabled), 'las herramientas de operación están desactivadas para el analista');
  ok(await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Iniciar') || x.textContent.includes('Pausar')).disabled), 'y los controles de la simulación también');
  ok(!(await texto()).includes('Editor de red') && !(await texto()).includes('Usuarios'), 'no ve el editor de red ni la administración de usuarios');
  await foto('6-analista');
  ok((await api('POST', '/api/sim/vehiculos', { cantidad: 5 })).estado === 403, 'y el servidor rechaza sus órdenes de operación (403)');

  // ------------------------------------------------ celular
  await p.setViewport({ width: 390, height: 844 }); await dormir(700);
  const desborde = await p.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1); ok(!desborde, 'en el celular la página no se desborda de lado');
  await foto('7-celular'); await p.setViewport({ width: 1440, height: 900 });
  const util = errores.filter((e) => !/favicon|status of 401|status of 403/.test(e)); // el 401 (contraseña mala) y el 403 (orden prohibida) son pruebas a propósito ok(util.length === 0, 'sin errores de consola ni de página', util.join(' | ').slice(0, 400));
  await b.close();
  console.log(fallas ? `\n${fallas} FALLAS` : '\nE2E SMARTTRAFFIC (parte 1): todo OK'); process.exit(fallas ? 1 : 0);
})().catch((e) => { console.error('FALLO', e.message); process.exit(1); });
