// Mide cuántos cuadros por segundo mantiene el mapa con una red grande (≈ 3.000 calles) y ≈ 500 vehículos.
//   node red-grande.cjs > /tmp/red-grande.json          (genera la red de prueba)
//   PUERTO=8095 node rendimiento-mapa.cjs               (el servidor debe estar en modo demostración)
// Imprime los cuadros de cada segundo. Un navegador sin tarjeta gráfica sirve de caso difícil: antes de guardar la red como imagen
// el dibujo se atascaba a los pocos segundos; ahora sostiene 30 cuadros por segundo.
const chromium = require('@sparticuz/chromium').default || require('@sparticuz/chromium');
const puppeteer = require('puppeteer-core'); const fs = require('fs');
const PUERTO = process.env.PUERTO || '8095', RED = process.env.RED || '/tmp/red-grande.json';
const dormir = (ms) => new Promise((r) => setTimeout(r, ms));
const escenarios = [
  { nombre: 'sin mapa base, simulación corriendo', origen: false, sim: true },
  { nombre: 'con mapa base, simulación en pausa', origen: true, sim: false },
  { nombre: 'con mapa base, simulación corriendo', origen: true, sim: true },
];
(async () => {
  for (const e of escenarios) {
    const b = await puppeteer.launch({ args: [...chromium.args, '--no-sandbox'], executablePath: await chromium.executablePath(), headless: 'shell', defaultViewport: { width: 1440, height: 900 } });
    const p = await b.newPage(); await p.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }]);
    await p.goto(`http://localhost:${PUERTO}`); await p.waitForFunction(() => [...document.querySelectorAll('button')].some((x) => x.textContent.includes('Administrador')));
    await p.evaluate(() => [...document.querySelectorAll('button')].find((x) => x.textContent.includes('Administrador')).click());
    await p.waitForSelector('canvas[data-testid="mapa"]'); await dormir(800);
    const api = (m, r, cuerpo) => p.evaluate(async (m, r, cuerpo) => { const x = await fetch(r, { method: m, headers: { Authorization: 'Bearer ' + sessionStorage.getItem('smarttraffic_token'), 'Content-Type': 'application/json' }, body: cuerpo }); return x.status; }, m, r, cuerpo);
    const red = JSON.parse(fs.readFileSync(RED, 'utf8')); if (!e.origen) delete red.origen;
    await api('POST', '/api/red/importar', JSON.stringify(red));
    await api('POST', '/api/sim/config', JSON.stringify({ vehiculosPorMinuto: 200, modo: 'INTELIGENTE', reruteo: true, velocidad: 4 }));
    await api('POST', '/api/sim/control', JSON.stringify({ accion: e.sim ? 'iniciar' : 'pausar' }));
    await p.reload(); await p.waitForSelector('canvas[data-testid="mapa"]'); await dormir(1000);
    let a = await p.evaluate(() => window.__mapa.cuadros()); const serie = [];
    for (let i = 0; i < 8; i++) { await dormir(1000); const c = await p.evaluate(() => window.__mapa.cuadros()); serie.push(c - a); a = c; }
    console.log(`${e.nombre}: cuadros por segundo → ${serie.join(' ')}`);
    await b.close();
  }
  process.exit(0);
})();
