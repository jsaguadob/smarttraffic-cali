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
| `SMARTTRAFFIC_DB_URL`, `_DB_USER`, `_DB_PASSWORD` | Base de datos. Por defecto un archivo H2 local (solo desarrollo). En producción, PostgreSQL: `jdbc:postgresql://servidor:5432/smarttraffic`. |
| `SMARTTRAFFIC_JWT_SECRET` | Clave de las sesiones (mín. 32 caracteres). Sin ella se genera una al azar en cada arranque. |
| `SMARTTRAFFIC_ADMIN_CORREO`, `SMARTTRAFFIC_ADMIN_CLAVE` | Primer administrador. Si no pones la clave se genera una y se imprime **una sola vez** en el registro. |
| `SMARTTRAFFIC_CORS` | Pantallas autorizadas, separadas por coma. Admite `*` dentro de la dirección (p. ej. `https://*--sitio.netlify.app` para las ramas). Un `*` solo se ignora a propósito. |
| `SMARTTRAFFIC_RED` | Archivo JSON con el mapa real importado; se carga una vez, cuando la base está vacía. |
| `SMARTTRAFFIC_DEMO=1` | Crea tres usuarios de prueba con contraseña conocida. **Nunca en un servidor público.** |

Usa HTTPS delante del servidor (el navegador bloquea llamadas HTTP desde una página HTTPS). Los servicios en la nube
lo dan de forma automática.

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

## Seguridad

- Las contraseñas se guardan con PBKDF2 (600.000 iteraciones); las sesiones son JWT firmados con HS256 y duran 8 horas.
- Tras 5 intentos fallidos por correo (o 30 por dirección IP) el ingreso se bloquea 10 minutos.
- Cada orden de operación queda en la tabla `auditoria`.
- No pongas contraseñas ni claves en el repositorio: van en variables de entorno.

## Lo que no está verificado

El `Dockerfile` y el `docker-compose.yml` no se pudieron ejecutar donde se desarrolló (no había Docker). La
compilación del servidor, las pruebas y la pantalla sí se verifican en cada subida con GitHub Actions.
