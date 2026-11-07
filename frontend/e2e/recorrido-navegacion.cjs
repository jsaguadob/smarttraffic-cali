const chromium = require('@sparticuz/chromium').default || require('@sparticuz/chromium');
const puppeteer = require('puppeteer-core');
const URL = (process.env.ST_URL || 'http://localhost:8095'), OUT = (process.env.CAPTURAS || './capturas') + '/st-';
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
let fallas = 0;
const ok = (c, m, extra) => { console.log((c ? 'PASS' : 'FAIL') + ' · ' + m + (c ? '' : '  → ' + (extra || ''))); if (!c) fallas++; };
(async () => {
  const b = await puppeteer.launch({ args: [...chromium.args, '--no-sandbox'], executablePath: await chromium.executablePath(), headless: 'shell', defaultViewport: { width: 1440, height: 900 } });
  const p = await b.newPage(); const errores = [];
  p.on('pageerror', (e) => errores.push('pageerror ' + e.message)); p.on('console', (m) => { if (m.type() === 'error') errores.push(m.text()); });
  const texto = () => p.evaluate(() => document.body.innerText);
  const esperar = async (c, ms = 8000) => { const f = Date.now(); while (Date.now() - f < ms) { if (await c()) return true; await dormir(120); } return false; };
  const hayTexto = (t) => async () => (await texto()).toLowerCase().includes(t.toLowerCase());
  const clic = (t, sel = 'button') => p.evaluate((t, sel) => { const e = [...document.querySelectorAll(sel)].find((x) => x.textContent.trim() === t || x.getAttribute('aria-label') === t); if (!e) return false; e.click(); return true; }, t, sel);
  const api = (m, r, cuerpo) => p.evaluate(async (m, r, cuerpo) => { const x = await fetch(r, { method: m, headers: { Authorization: 'Bearer ' + sessionStorage.getItem('smarttraffic_token'), 'Content-Type': 'application/json' }, body: cuerpo ? JSON.stringify(cuerpo) : undefined }); return { estado: x.status, cuerpo: await x.json().catch(() => null) }; }, m, r, cuerpo);
  const azules = () => p.evaluate(() => { const c = document.querySelector('canvas[data-testid="mapa"]'), g = c.getContext('2d'), d = g.getImageData(0, 0, c.width, c.height).data; let n = 0; for (let i = 0; i < d.length; i += 4) if (Math.abs(d[i] - 26) < 6 && Math.abs(d[i + 1] - 115) < 6 && Math.abs(d[i + 2] - 232) < 6) n++; return n; });
  const escribir = async (rotulo, valor) => { // selecciona todo con el teclado (más fiable que el triple clic) y comprueba que quedó exactamente lo escrito
    const el = await p.$(`input[aria-label="${rotulo}"]`);
    for (let i = 0; i < 3; i++) {
      await el.focus(); await p.keyboard.down('Control'); await p.keyboard.press('KeyA'); await p.keyboard.up('Control'); await p.keyboard.press('Backspace'); await el.type(valor);
      if ((await p.$eval(`input[aria-label="${rotulo}"]`, (e) => e.value)) === valor) return;
    }
  };
  const elegirSugerencia = async (inicio, tipo) => { const ok = await esperar(async () => p.evaluate((i, t) => [...document.querySelectorAll('[role=option]')].some((x) => x.textContent.startsWith(i) && x.textContent.includes(t)), inicio, tipo), 5000); if (!ok) return false; await p.evaluate((i, t) => [...document.querySelectorAll('[role=option]')].find((x) => x.textContent.startsWith(i) && x.textContent.includes(t)).dispatchEvent(new MouseEvent('mousedown', { bubbles: true })), inicio, tipo); return true; };

  await p.goto(URL); await p.waitForFunction(() => [...document.querySelectorAll('button')].some((x) => x.textContent.includes('Operador de movilidad')));
  await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Operador de movilidad')).click());
  ok(await esperar(async () => !!(await p.$('input[aria-label="Origen"]')), 15000), 'la consola abre en «Cómo llegar» con los campos Origen y Destino'); await dormir(800);
  // buscar por nombre, sin tildes ni mayúsculas
  await escribir('Origen', 'CALLE 25 con carrera 1');
  ok(await elegirSugerencia('Calle 25 con Carrera 1', 'Intersección'), 'al escribir «CALLE 25 con carrera 1» ofrece esa intersección (sin importar mayúsculas)');
  await escribir('Destino', 'calle 5 con carrera 39');
  ok(await elegirSugerencia('Calle 5 con Carrera 39', 'Intersección'), 'y para el destino también');
  // un lugar ya elegido se puede editar sin que se pierda lo que se escribe
  await p.focus('input[aria-label="Origen"]'); await p.keyboard.press('End'); await p.keyboard.type('x');
  ok((await p.$eval('input[aria-label="Origen"]', (e) => e.value)) === 'Calle 25 con Carrera 1x', 'al editar un lugar ya elegido, la letra escrita no se pierde ni se borra el campo', await p.$eval('input[aria-label="Origen"]', (e) => e.value));
  await escribir('Origen', 'calle 25 con carrera 1'); await elegirSugerencia('Calle 25 con Carrera 1', 'Intersección');
  await clic('Cómo llegar', 'button.primario');
  ok(await esperar(async () => !!(await p.$('[data-testid=tiempo-ruta]')), 8000), 'el botón «Cómo llegar» calcula la ruta y muestra el tiempo');
  const tiempo = await p.$eval('[data-testid=tiempo-ruta]', (e) => e.textContent); console.log('   tiempo de la ruta más rápida:', tiempo, '|', (await p.$$eval('.opcion-ruta', (l) => l.map((e) => e.textContent.replace(/\s+/g, ' ').slice(0, 90)))).join(' || '));
  ok((await p.$$('.opcion-ruta')).length >= 2, 'ofrece la más rápida y al menos una alternativa');
  const pasos = await p.$$eval('.pasos li', (l) => l.map((e) => e.textContent.replace(/\s+/g, ' ').trim()));
  ok(pasos.length >= 3 && /^↑?\s*Sal por/.test(pasos[0]) && pasos[pasos.length - 1].includes('Has llegado a tu destino'), 'las instrucciones empiezan con «Sal por…» y terminan con «Has llegado a tu destino»', pasos.join(' | '));
  ok(pasos.some((s) => /Gira .* (derecha|izquierda)/.test(s)), 'e incluyen giros a la derecha o a la izquierda', pasos.join(' | '));
  await dormir(600); ok((await azules()) > 300, 'la ruta se dibuja en azul sobre el mapa'); await p.screenshot({ path: OUT + '15-ruta.png' });
  await p.evaluate(() => document.querySelectorAll('.opcion-ruta')[1].click());
  ok(await p.evaluate(() => document.querySelectorAll('.opcion-ruta')[1].getAttribute('aria-pressed')) === 'true', 'se puede elegir una alternativa');
  await p.evaluate(() => document.querySelectorAll('.opcion-ruta')[0].click());
  // elegir en el mapa
  await clic('Elegir destino en el mapa');
  ok(await esperar(hayTexto('Haz clic en el mapa para elegir el destino'), 3000), 'el botón 📍 pide hacer clic en el mapa');
  const cv = await (await p.$('canvas[data-testid="mapa"]')).boundingBox(); await p.mouse.click(cv.x + cv.width * 0.5, cv.y + cv.height * 0.45);
  ok(await esperar(async () => (await p.$eval('input[aria-label="Destino"]', (e) => e.value)) === 'Punto en el mapa', 3000), 'el clic fija el destino como «Punto en el mapa»');
  await clic('Cómo llegar', 'button.primario'); ok(await esperar(async () => !!(await p.$('[data-testid=tiempo-ruta]')), 6000), 'y calcula la ruta hasta ese punto');
  // navegación guiada con un accidente en el camino
  await escribir('Origen', 'calle 25 con carrera 1'); await elegirSugerencia('Calle 25 con Carrera 1', 'Intersección');
  await escribir('Destino', 'calle 5 con carrera 39'); await elegirSugerencia('Calle 5 con Carrera 39', 'Intersección');
  await clic('Cómo llegar', 'button.primario'); await esperar(async () => !!(await p.$('[data-testid=tiempo-ruta]')), 6000);
  await api('POST', '/api/sim/control', { accion: 'iniciar', velocidad: 4 });
  const ruta = (await api('POST', '/api/ruta', { origen: { nodo: 'N00' }, destino: { nodo: 'N33' }, alternativas: 0 })).cuerpo.opciones[0];
  await clic('Iniciar navegación');
  ok(await esperar(async () => !!(await p.$('[data-testid=guia]')), 5000), 'al iniciar la navegación aparece la guía sobre el mapa');
  const t1 = await p.$eval('[data-testid=guia]', (e) => e.textContent);
  ok(/En .*(m|km): /.test(t1) && /Quedan/.test(t1), 'la guía dice qué hacer y a qué distancia, y cuánto falta', t1);
  await dormir(3500); const t2 = await p.$eval('[data-testid=guia]', (e) => e.textContent); ok(t1 !== t2, 'la guía avanza con el tiempo de la simulación', t1 + ' → ' + t2);
  const intermedia = ruta.vias[Math.floor(ruta.vias.length / 2)];
  await api('POST', '/api/sim/incidentes', { via: intermedia, fraccion: 0.5, gravedad: 3 });
  ok(await esperar(hayTexto('Hay un accidente en tu ruta'), 6000), 'un accidente sobre la ruta durante la navegación se avisa en pantalla');
  await dormir(1500);
  const nueva = await p.evaluate(() => [...document.querySelectorAll('.pasos li')].map((e) => e.textContent.replace(/\s+/g, ' ').trim()).join(' | '));
  ok(await p.$('[data-testid=guia]') !== null || (await texto()).includes('Has llegado'), 'y la navegación continúa con una ruta nueva desde donde iba', nueva);
  await p.screenshot({ path: OUT + '16-guia-accidente.png' });
  // llegada
  ok(await esperar(hayTexto('Has llegado a tu destino'), 60000), 'al terminar la ruta la guía dice «Has llegado a tu destino»');
  await clic('Limpiar'); ok(await esperar(async () => !(await p.$('[data-testid=tiempo-ruta]')), 3000), '«Limpiar» borra la ruta');
  const util = errores.filter((e) => !/favicon|status of 40/.test(e)); ok(util.length === 0, 'sin errores de consola ni de página', util.join(' | ').slice(0, 300));
  await b.close(); console.log(fallas ? `\n${fallas} FALLAS` : '\nE2E NAVEGACIÓN: todo OK'); process.exit(fallas ? 1 : 0);
})().catch((e) => { console.error('FALLO', e.message); process.exit(1); });
