# Despliegue: Netlify (pantalla) + servidor Java

SmartTraffic tiene dos partes que se publican por separado:

```
 Navegador ──►  Netlify (pantalla React, archivos estáticos)
     │
     └──────►  Servidor Java (API REST, simulación en vivo, base de datos PostgreSQL)
```

**Netlify no puede ejecutar el servidor Java**: la simulación corre continuamente y la transmisión en vivo mantiene
conexiones abiertas, algo que las funciones de Netlify no permiten. El servidor va en cualquier sitio que ejecute
contenedores o Java (un servicio de contenedores en la nube o un servidor Linux propio).

## Ramas

| Rama | Para qué | En Netlify |
|---|---|---|
| `main` | Lo estable. Solo entra lo que ya se probó en `desarrollo`. | Despliegue de producción |
| `desarrollo` | Trabajo diario y pruebas. | «Branch deploy»: `https://desarrollo--NOMBRE-DEL-SITIO.netlify.app` |

Flujo recomendado: cambios en `desarrollo` → revisar el despliegue de esa rama y que GitHub Actions esté en verde →
fusionar `desarrollo` en `main`.

## 1. El servidor Java

**Con Docker** (lo más simple; `docker-compose.yml` levanta el servidor y PostgreSQL):

```bash
export SMARTTRAFFIC_JWT_SECRET=$(openssl rand -base64 48)      # guárdala: si cambia, se cierran las sesiones
export SMARTTRAFFIC_ADMIN_CLAVE='UnaClaveLarga2026'              # primer administrador (mín. 10 caracteres, letras y números)
export SMARTTRAFFIC_CORS='https://NOMBRE-DEL-SITIO.netlify.app,https://*--NOMBRE-DEL-SITIO.netlify.app'
docker compose up --build
```

**Sin Docker** (necesitas JDK 17 o superior): `SMARTTRAFFIC_DEMO=1 ./run.sh` para probar en local, o `./run.sh`
con las variables de abajo para un servidor real.

| Variable | Qué hace |
|---|---|
| `PORT` o `SMARTTRAFFIC_PORT` | Puerto (por defecto 8080). Muchos servicios en la nube definen `PORT` solos. |
| `SMARTTRAFFIC_DB_URL` (o `DATABASE_URL`) | Base de datos. Acepta la **línea que entrega Neon, Render o Railway tal cual** (`postgresql://usuario:clave@host/base?sslmode=require`) o el formato JDBC (`jdbc:postgresql://host:5432/base`). Por defecto, un archivo H2 local (solo desarrollo). |
| `SMARTTRAFFIC_DB_USER`, `SMARTTRAFFIC_DB_PASSWORD` | Opcionales si ya vienen dentro de la línea. Si los defines, tienen prioridad. |
| `SMARTTRAFFIC_JWT_SECRET` | Clave de las sesiones (mín. 32 caracteres). Sin ella se genera una al azar en cada arranque. |
| `SMARTTRAFFIC_ADMIN_CORREO`, `SMARTTRAFFIC_ADMIN_CLAVE` | Primer administrador. Si no pones la clave se genera una y se imprime **una sola vez** en el registro. |
| `SMARTTRAFFIC_CORS` | Pantallas autorizadas, separadas por coma. Admite `*` dentro de la dirección (p. ej. `https://*--sitio.netlify.app` para las ramas). Un `*` solo se ignora a propósito. |
| `SMARTTRAFFIC_RED` | Archivo JSON con el mapa real importado; se carga una vez, cuando la base está vacía. |
| `SMARTTRAFFIC_DEMO=1` | Crea tres usuarios de prueba con contraseña conocida. **Nunca en un servidor público.** |

Usa HTTPS delante del servidor (el navegador bloquea llamadas HTTP desde una página HTTPS). Los servicios en la nube
lo dan de forma automática.

### Base de datos en Neon

1. En la consola de Neon abre tu proyecto → **Connect** (o *Connection details*) y copia la línea de conexión.
2. Pégala **solo** en el panel de variables del servicio donde corre el servidor, como `SMARTTRAFFIC_DB_URL`
   (o `DATABASE_URL`). No la pegues en el repositorio, en un chat ni en un documento: lleva la contraseña.
3. Prefiere la dirección **directa** (sin `-pooler` en el nombre del servidor): este servidor mantiene sus propias
   conexiones y la directa evita sorpresas con el balanceador de Neon.
4. Al arrancar, el servidor crea las tablas solo y el primer administrador con `SMARTTRAFFIC_ADMIN_CORREO` y
   `SMARTTRAFFIC_ADMIN_CLAVE`. El registro imprime a qué servidor se conecta, nunca el usuario ni la contraseña.
5. **Ramas de Neon:** crea una rama de base de datos `desarrollo` (copia barata de la de producción) y usa su propia
   línea para el servidor de `desarrollo`. Así las pruebas nunca tocan los datos de `main`.
6. En el plan gratuito, Neon suspende la base tras unos minutos sin uso: la primera petición después de una pausa
   puede tardar unos segundos.
7. Si la línea se llegó a pegar donde no debía, cambia la contraseña del rol en Neon (*Roles → Reset password*).

### El mapa real de Cali

En un computador con internet: `./importar-cali.sh` descarga las calles de OpenStreetMap y escribe
`backend/data/red-cali.json`. Luego arranca el servidor con `SMARTTRAFFIC_RED=backend/data/red-cali.json` o, ya con
el servidor en marcha, entra como administrador a **Editor de red → Importar red** y sube ese archivo.
Los accidentes (datos abiertos de la Alcaldía, CSV o GeoJSON con latitud y longitud) se suben en el mismo lugar.

## 2. La pantalla en Netlify

1. En Netlify: **Add new site → Import an existing project →** GitHub → el repositorio `smarttraffic-cali`.
2. Rama de producción: `main`. Netlify lee `netlify.toml` (carpeta `frontend`, comando y carpeta de salida ya están).
3. **Site configuration → Environment variables:** crea `VITE_API_URL` con la dirección HTTPS del servidor
   (sin `/` al final). Puedes darle un valor distinto a cada contexto: *Production* (rama `main`) y
   *Branch deploys* (rama `desarrollo`), para que cada rama hable con su propio servidor.
4. **Site configuration → Build & deploy → Branches and deploy contexts:** activa *Branch deploys* y agrega `desarrollo`.
5. Despliega. Si falta `VITE_API_URL`, la compilación se detiene con un mensaje que lo explica (a propósito).
6. Copia las direcciones del sitio y de la rama a `SMARTTRAFFIC_CORS` en el servidor.

## Si la pantalla dice «No se pudo conectar con el servidor»

El mensaje indica la dirección a la que intentó conectarse. Revisa en este orden:

1. **¿Hay un servidor?** Abre `DIRECCIÓN/api/salud` en el navegador (con la dirección de `VITE_API_URL`). Debe mostrar
   `{"ok":true,"version":"1.0.0"}`. Si no responde, el servidor está apagado, la dirección está mal escrita o es un
   valor de relleno. Netlify solo aloja la pantalla.
2. **¿Autoriza a la pantalla?** Si el paso 1 funciona pero el ingreso falla, abre las herramientas del navegador
   (F12 → Consola). Un mensaje «blocked by CORS policy» significa que la dirección de la pantalla no está en
   `SMARTTRAFFIC_CORS` del servidor, o está mal escrita (sin `/` al final, con `https://`, sin espacios).
3. **¿Cambiaste `VITE_API_URL`?** Es una variable de *compilación*: hay que volver a desplegar la pantalla en
   Netlify (*Trigger deploy*) para que el cambio se aplique.
4. **¿Es HTTPS?** Una página HTTPS no puede llamar a un servidor HTTP: el navegador lo bloquea.
5. **¿Neon estaba suspendida?** La primera petición tras una pausa puede tardar unos segundos; reintenta.

## Seguridad

- Las contraseñas se guardan con PBKDF2 (600.000 iteraciones); las sesiones son JWT firmados con HS256 y duran 8 horas.
- Tras 5 intentos fallidos por correo (o 30 por dirección IP) el ingreso se bloquea 10 minutos.
- Cada orden de operación queda en la tabla `auditoria`.
- No pongas contraseñas ni claves en el repositorio: van en variables de entorno.

## Lo que no está verificado

El `Dockerfile` y el `docker-compose.yml` no se pudieron ejecutar donde se desarrolló (no había Docker). La
compilación del servidor, las pruebas y la pantalla sí se verifican en cada subida con GitHub Actions.
