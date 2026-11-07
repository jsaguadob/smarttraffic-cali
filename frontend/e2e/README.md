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
