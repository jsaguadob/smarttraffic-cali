const chromium = require('@sparticuz/chromium').default || require('@sparticuz/chromium');
const puppeteer = require('puppeteer-core');
const fs = require('fs');
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
  p.on('dialog', (d) => d.accept());
  const texto = () => p.evaluate(() => document.body.innerText);
  const esperar = async (cond, ms = 8000) => { const f = Date.now(); while (Date.now() - f < ms) { if (await cond()) return true; await dormir(120); } return false; };
  const hayTexto = (t) => async () => (await texto()).toLowerCase().includes(t.toLowerCase());
  const clic = (t, sel = 'button') => p.evaluate((t, sel) => { const e = [...document.querySelectorAll(sel)].find((x) => x.textContent.trim() === t || x.getAttribute('title')?.startsWith(t) || x.getAttribute('aria-label') === t); if (!e) return false; e.click(); return true; }, t, sel);
  const foto = (n) => p.screenshot({ path: OUT + n + '.png' });
  const api = (m, r, cuerpo) => p.evaluate(async (m, r, cuerpo) => { const x = await fetch(r, { method: m, headers: { Authorization: 'Bearer ' + sessionStorage.getItem('smarttraffic_token'), 'Content-Type': 'application/json' }, body: cuerpo ? JSON.stringify(cuerpo) : undefined }); return { estado: x.status, cuerpo: await x.json().catch(() => null) }; }, m, r, cuerpo);
  const aPantalla = (x, y) => p.evaluate((x, y) => window.__mapa.aPantalla(x, y), x, y);
  const escala = () => p.evaluate(() => window.__mapa.escala());
  const lienzo = async () => (await (await p.$('canvas[data-testid="mapa"]')).boundingBox());

  await p.goto(URL, { waitUntil: 'domcontentloaded' });
  await esperar(hayTexto('Ingresa con tu cuenta'));
  await p.waitForFunction(() => [...document.querySelectorAll('button')].some((x) => x.textContent.includes('Administrador')));
  await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Administrador')).click());
  ok(await esperar(hayTexto('Editor de red'), 12000), 'el administrador ve «Editor de red» y «Usuarios» en el menú');
  ok((await texto()).includes('Usuarios'), 'y la administración de usuarios');

  // ------------------------------------------------ editor de red
  await clic('Editor de red');
  ok(await esperar(hayTexto('18 intersecciones'), 8000), 'el editor muestra la red: 18 intersecciones'); await dormir(600); await foto('8-editor');
  const red0 = (await api('GET', '/api/red')).cuerpo; const n11 = red0.intersecciones.find((n) => n.id === 'N11');
  // arrastrar una intersección
  let k = await escala(), pt = await aPantalla(n11.x, n11.y), r = await lienzo(), dxPx = 70, dyPx = 30;
  await p.mouse.move(r.x + pt.x, r.y + pt.y); await p.mouse.down(); await p.mouse.move(r.x + pt.x + dxPx / 2, r.y + pt.y + dyPx / 2, { steps: 5 }); await p.mouse.move(r.x + pt.x + dxPx, r.y + pt.y + dyPx, { steps: 5 }); await p.mouse.up();
  ok(await esperar(async () => Math.abs(((await api('GET', '/api/red')).cuerpo.intersecciones.find((n) => n.id === 'N11').x) - (n11.x + dxPx / k)) < 2, 6000), 'arrastrar una intersección en el mapa la mueve y queda guardado (con las calles que llegan a ella)');
  const red1 = (await api('GET', '/api/red')).cuerpo; const v = red1.vias.find((x) => x.hacia === 'N11');
  ok(Math.abs(v.puntos[v.puntos.length - 2] - red1.intersecciones.find((n) => n.id === 'N11').x) < 0.2, 'las calles que llegan a la intersección siguen su nueva posición');
  ok(Math.abs(red1.vias.find((x) => x.id === v.id).longitud - red0.vias.find((x) => x.id === v.id).longitud) > 1, 'y su longitud se recalcula');
  // añadir una intersección
  await clic('Añadir'); const cx = 128, cy = 100; pt = await aPantalla(cx, cy); r = await lienzo(); await p.mouse.click(r.x + pt.x, r.y + pt.y);
  ok(await esperar(hayTexto('19 intersecciones'), 6000), 'la herramienta Añadir crea una intersección donde se hace clic');
  ok(await esperar(hayTexto('no se puede simular todavía'), 4000), 'avisa que una intersección suelta no se puede simular todavía');
  const nueva = (await api('GET', '/api/red')).cuerpo.intersecciones.find((n) => n.nombre === 'Intersección nueva'); ok(!!nueva, 'la intersección nueva quedó en el servidor');
  // conectarla con una calle
  await clic('Calle'); await p.mouse.click(r.x + pt.x, r.y + pt.y); const n01 = red0.intersecciones.find((n) => n.id === 'N01'); const p01 = await aPantalla(n01.x, n01.y); await p.mouse.click(r.x + p01.x, r.y + p01.y);
  ok(await esperar(hayTexto('Nueva calle'), 4000), 'elegir dos intersecciones abre el formulario de la calle');
  await clic('Crear calle');
  ok(await esperar(hayTexto('La red se puede simular'), 6000), 'al conectarla la red vuelve a ser simulable y entra en uso'); await foto('9-editor-calle');
  ok((await api('GET', '/api/red')).cuerpo.vias.length === 42, 'se crearon los dos sentidos de la calle (42 tramos)');
  // borrar
  await clic('Borrar'); await p.mouse.click(r.x + pt.x, r.y + pt.y);
  ok(await esperar(async () => (await api('GET', '/api/red')).cuerpo.intersecciones.length === 18, 6000), 'la herramienta Borrar elimina la intersección y sus calles'); ok((await api('GET', '/api/red')).cuerpo.vias.length === 40, 'y vuelven los 40 tramos');
  // importar un archivo de red
  fs.writeFileSync('/tmp/red-export.json', JSON.stringify((await api('GET', '/api/red')).cuerpo));
  const entrada = await p.$('input[aria-label="Importar red"]'); await entrada.uploadFile('/tmp/red-export.json');
  ok(await esperar(hayTexto('Red importada'), 6000), 'subir un archivo JSON importa la red completa');
  const mala = '/tmp/mala.json'; fs.writeFileSync(mala, '{"nombre":"x","intersecciones":[{"id":"A","nombre":"A","x":0,"y":0,"tipo":"ENTRADA_SALIDA"}],"vias":[]}');
  await (await p.$('input[aria-label="Importar red"]')).uploadFile(mala);
  ok(await esperar(hayTexto('no se puede simular'), 6000), 'un archivo de red inservible se rechaza con un mensaje claro');

  // ------------------------------------------------ comparar
  await clic('Comparar escenarios');
  ok(await esperar(hayTexto('Comparar tráfico tradicional y SmartTraffic'), 5000), 'abre la comparación de escenarios');
  await p.select('select:not([aria-label])', '300');
  await clic('▶ Comparar escenarios');
  ok(await esperar(async () => !!(await p.$('[data-testid=resumen-comparacion]')), 40000), 'la comparación termina y muestra el resumen');
  const resumen = await p.$eval('[data-testid=resumen-comparacion]', (e) => e.textContent); console.log('   resumen:', resumen.slice(0, 230));
  ok(/(redujo|aumentó) el tiempo de viaje medio/.test(resumen), 'el resumen dice cuánto cambia el tiempo de viaje');
  ok((await p.$$('figure.grafica svg')).length >= 2 && (await p.$$('.fila-barra')).length >= 5, 'muestra las barras comparativas y las gráficas de tiempo'); await dormir(300); await foto('10-comparar');
  ok(await esperar(async () => (await p.$$('table.tabla tbody tr')).length >= 2, 5000), 'las dos simulaciones quedan en el historial guardado');

  // ------------------------------------------------ usuarios
  await clic('Usuarios');
  ok(await esperar(hayTexto('Crear usuario'), 5000), 'abre la administración de usuarios');
  const rellenar = async (clave) => { const e = await p.$$('form.formulario input'); for (const i of e) { await i.focus(); await p.keyboard.down('Control'); await p.keyboard.press('KeyA'); await p.keyboard.up('Control'); await p.keyboard.press('Backspace'); } await e[0].type('Prueba Uno'); await e[1].type('e2e@smarttraffic.test'); await e[2].type(clave); await p.select('form.formulario select', 'ANALISTA'); await clic('Crear usuario'); };
  await rellenar('corta1'); ok(await esperar(hayTexto('al menos 10 caracteres'), 5000), 'una contraseña débil se rechaza con el motivo');
  await rellenar('ClaveBuena2026'); ok(await esperar(hayTexto('e2e@smarttraffic.test'), 5000), 'un usuario nuevo con contraseña fuerte se crea y aparece en la lista'); await foto('11-usuarios');
  ok(await p.$eval('select[aria-label^="Rol de Administración"]', (e) => e.disabled), 'el administrador no puede cambiarse a sí mismo el rol');
  await p.evaluate(() => [...document.querySelectorAll('tr')].find((t) => t.textContent.includes('e2e@')).querySelectorAll('button')[0].click());
  ok(await esperar(async () => (await p.evaluate(() => [...document.querySelectorAll('tr')].find((t) => t.textContent.includes('e2e@')).textContent)).includes('Desactivado'), 5000), 'desactivar un usuario se refleja en la lista');

  // ------------------------------------------------ celular (operador)
  await clic('Salir'); await esperar(hayTexto('Ingresa con tu cuenta'));
  await p.setViewport({ width: 390, height: 844 });
  await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Operador de movilidad')).click());
  await esperar(hayTexto('Cómo llegar'), 12000); await dormir(1200);
  ok(!(await p.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1)), 'en el celular no hay desborde lateral'); const cv = await lienzo(); ok(cv.height > 300, 'y el mapa tiene un tamaño útil', JSON.stringify(cv)); await foto('12-celular');
  const util = errores.filter((e) => !/favicon|status of 40[0-9]/.test(e)); ok(util.length === 0, 'sin errores de consola ni de página', util.join(' | ').slice(0, 400));
  await b.close();
  console.log(fallas ? `\n${fallas} FALLAS` : '\nE2E SMARTTRAFFIC (parte 2): todo OK'); process.exit(fallas ? 1 : 0);
})().catch((e) => { console.error('FALLO', e.message); process.exit(1); });
