# Pruebas de navegador

Dos recorridos completos con un navegador real (Chromium controlado con Puppeteer) que ejercitan la pantalla
contra un servidor en marcha: ingreso por rol, simulación, accidentes por clic sobre el mapa, emergencias,
solo lectura del analista, editor de red (arrastrar, añadir, conectar, borrar, importar), comparación de
escenarios, usuarios y vista de celular.

```bash
# 1. Servidor en modo demostración con base de datos en memoria (desde la raíz del proyecto)
SMARTTRAFFIC_DEMO=1 SMARTTRAFFIC_PORT=8095 SMARTTRAFFIC_STATIC=$PWD/frontend/dist \
SMARTTRAFFIC_DB_URL="jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
java -Dsmarttraffic.pbkdf2=1000 -cp "backend/out/main:backend/lib/h2.jar:backend/lib/postgresql.jar" smarttraffic.Main

# 2. En otra terminal (instala las herramientas de prueba fuera del proyecto)
mkdir -p /tmp/e2e && cd /tmp/e2e && npm i puppeteer-core @sparticuz/chromium
mkdir -p capturas && NODE_PATH=/tmp/e2e/node_modules node /ruta/al/proyecto/frontend/e2e/recorrido-1.cjs
NODE_PATH=/tmp/e2e/node_modules node /ruta/al/proyecto/frontend/e2e/recorrido-2.cjs
```

Variables: `ST_URL` (por defecto `http://localhost:8095`) y `CAPTURAS` (carpeta de capturas, por defecto `./capturas`).

## Alineación del mapa base real (`recorrido-teselas.cjs`)

Comprueba que las teselas del mapa se pintan exactamente debajo de las calles (error medido: medio píxel). Como aquí no se usa
internet, un servidor de teselas **falso** (`servidor_teselas.py`, necesita Pillow) dibuja una marca roja en la latitud y longitud
de una intersección, calculada con matemática independiente de la de la pantalla.

```bash
# servidor de teselas falso: puerto, y la posición (x, y en metros locales) de la intersección objetivo (N11 = 261 211)
python3 frontend/e2e/servidor_teselas.py 9099 261 211 &
# pantalla compilada apuntando a ese servidor, y el servidor Java en modo demostración en el puerto 8097
cd frontend && VITE_TILES_URL='http://localhost:9099/{z}/{x}/{y}.png' npx vite build --outDir /tmp/dist-tiles
# ... arrancar el servidor Java con SMARTTRAFFIC_STATIC=/tmp/dist-tiles SMARTTRAFFIC_PORT=8097 y correr recorrido-teselas.cjs
```
