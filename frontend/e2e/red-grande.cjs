// Red sintética del tamaño del centro de Cali real: 28x28 intersecciones (≈ 3.000 tramos), con curvas, sentidos y semáforos.
const N = 28, P = 95, nodos = [], vias = [];
const id = (i, j) => `G${i}_${j}`;
for (let i = 0; i < N; i++) for (let j = 0; j < N; j++) {
  const borde = i === 0 || j === 0 || i === N - 1 || j === N - 1;
  nodos.push({ id: id(i, j), nombre: `Calle ${10 + i} con Carrera ${5 + j}`, x: j * P + ((i * 7 + j * 13) % 11) - 5, y: i * P + ((i * 5 + j * 3) % 9) - 4, lat: null, lon: null, tipo: borde && (i + j) % 2 === 0 ? 'ENTRADA_SALIDA' : 'INTERIOR', semaforizada: !borde && (i + j) % 3 === 0, servicios: [] });
}
const nodo = Object.fromEntries(nodos.map((n) => [n.id, n]));
let k = 0;
const via = (a, b, nombre, vel) => { const A = nodo[a], B = nodo[b], mx = (A.x + B.x) / 2 + (A.y - B.y) * 0.06, my = (A.y + B.y) / 2 + (B.x - A.x) * 0.06; const pts = [A.x, A.y, mx, my, B.x, B.y]; const L = Math.hypot(mx - A.x, my - A.y) + Math.hypot(B.x - mx, B.y - my); vias.push({ id: `V${k++}`, desde: a, hacia: b, nombre, longitud: L, velocidad: vel, clase: 'residential', manual: false, puntos: pts }); };
for (let i = 0; i < N; i++) for (let j = 0; j < N; j++) {
  if (j + 1 < N) { via(id(i, j), id(i, j + 1), `Calle ${10 + i}`, 40); via(id(i, j + 1), id(i, j), `Calle ${10 + i}`, 40); }
  if (i + 1 < N) { via(id(i, j), id(i + 1, j), `Carrera ${5 + j}`, 40); via(id(i + 1, j), id(i, j), `Carrera ${5 + j}`, 40); }
}
console.log(JSON.stringify({ nombre: 'Red grande de prueba', origen: { lat: 3.4516, lon: -76.532 }, intersecciones: nodos, vias }));
