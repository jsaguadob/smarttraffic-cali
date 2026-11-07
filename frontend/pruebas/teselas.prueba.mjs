// Pruebas de la matemática del mapa base. Se ejecutan con:  node --experimental-strip-types pruebas/teselas.prueba.mjs
import assert from 'node:assert/strict';
import { aGrados, aMetros, latDeTile, lonDeTile, tileX, tileY, zoomPara } from '../src/teselas.ts';

const cerca = (a, b, tol, msg) => assert.ok(Math.abs(a - b) <= tol, `${msg}: ${a} no está a ${tol} de ${b}`);
const caliCentro = { lat: 3.4516, lon: -76.532 };

// 1. Casos conocidos del esquema de teselas de OpenStreetMap
assert.equal(tileX(-180, 0), 0); assert.equal(tileY(85, 0), 0);
cerca(latDeTile(0, 0), 85.0511287798, 1e-9, 'borde norte del mundo'); cerca(lonDeTile(1, 1), 0, 1e-12, 'meridiano de Greenwich');
assert.equal(tileX(0, 1), 1); assert.equal(tileY(0, 1), 1, 'el ecuador cae en el borde entre las teselas 0 y 1');
// 2. Ida y vuelta: la tesela que contiene un punto contiene sus propios límites
for (const z of [10, 14, 17, 19]) {
  const x = tileX(caliCentro.lon, z), y = tileY(caliCentro.lat, z);
  assert.equal(tileX(lonDeTile(x, z) + 1e-9, z), x); assert.equal(tileY(latDeTile(y, z) - 1e-9, z), y);
  assert.ok(lonDeTile(x, z) <= caliCentro.lon && caliCentro.lon < lonDeTile(x + 1, z), 'la longitud está dentro de su tesela');
  assert.ok(latDeTile(y + 1, z) < caliCentro.lat && caliCentro.lat <= latDeTile(y, z), 'la latitud está dentro de su tesela');
}
// 3. Una tesela de zoom 17 en Cali mide ≈ 305 m de lado, y sale igual de ancha que de alta en nuestros metros locales
const z = 17, x = tileX(caliCentro.lon, z), y = tileY(caliCentro.lat, z);
const a = aMetros(caliCentro, latDeTile(y, z), lonDeTile(x, z)), b = aMetros(caliCentro, latDeTile(y + 1, z), lonDeTile(x + 1, z));
const esperado = (40075016.686 * Math.cos((caliCentro.lat * Math.PI) / 180)) / 2 ** z;
cerca(b.x - a.x, esperado, 0.6, 'ancho de la tesela en metros'); cerca(b.y - a.y, esperado, 0.6, 'alto de la tesela en metros');
assert.ok(b.y > a.y && b.x > a.x, 'el eje y crece hacia el sur, como en la pantalla');
// 4. Metros ↔ grados (igual que ProyeccionLocal del servidor)
const p = aMetros(caliCentro, 3.454, -76.53), q = aGrados(caliCentro, p.x, p.y);
cerca(q.lat, 3.454, 1e-9, 'latitud de vuelta'); cerca(q.lon, -76.53, 1e-9, 'longitud de vuelta');
assert.ok(p.x > 0 && p.y < 0, 'al este x positivo, al norte y negativo');
cerca(aMetros(caliCentro, 3.4516, -76.53).x, 222.4, 1.0, '0,002° de longitud ≈ 222 m a esta latitud');
// 5. Nivel de zoom según la escala de la pantalla
assert.equal(zoomPara(1, 3.45, 1), 17, 'a 1 px por metro corresponde zoom 17');
assert.ok(zoomPara(0.2, 3.45, 1) < zoomPara(2, 3.45, 1), 'más cerca, más detalle');
assert.equal(zoomPara(1e-9, 3.45, 1), 3); assert.equal(zoomPara(1e9, 3.45, 1), 19);
console.log('teselas: todas las pruebas pasaron');
