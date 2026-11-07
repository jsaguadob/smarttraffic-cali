// Se ejecuta antes de compilar. En Netlify la pantalla y el servidor Java viven en sitios distintos:
// sin la dirección del servidor la pantalla se publicaría, pero sin poder conectarse a nada.
const url = process.env.VITE_API_URL ?? '';
if (process.env.NETLIFY === 'true' && !url) {
  console.error('\n✖ Falta la variable VITE_API_URL.\n  Defínela en Netlify (Site configuration → Environment variables) con la dirección HTTPS del servidor Java,\n  por ejemplo https://smarttraffic-api.ejemplo.com  (ver docs/despliegue.md).\n');
  process.exit(1);
}
if (url && !/^https?:\/\/[^\s/]+/.test(url)) {
  console.error(`\n✖ VITE_API_URL no parece una dirección válida: «${url}»\n`);
  process.exit(1);
}
if (process.env.NETLIFY === 'true' && !url.startsWith('https://')) {
  console.error('\n✖ En Netlify la dirección del servidor debe ser HTTPS (el navegador bloquea llamadas HTTP desde una página HTTPS).\n');
  process.exit(1);
}
console.log(url ? `✔ La pantalla hablará con el servidor en ${url}` : '✔ La pantalla hablará con el mismo sitio que la entrega (modo local)');
