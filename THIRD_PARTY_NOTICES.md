# Componentes de terceros y licencias

SmartTraffic usa **solo software y datos de código abierto**. No depende de servicios propietarios
(Google Maps, TomTom, HERE, etc.). Este archivo lista lo que incluye o requiere el proyecto.

## Software

| Componente | Uso | Licencia | Dónde está |
|---|---|---|---|
| OpenJDK 17+ | Compilar y ejecutar el backend | GPL‑2.0 con *Classpath Exception* | Lo instala cada persona (https://openjdk.org) |
| H2 Database 2.2.220 | Base de datos embebida para desarrollo y pruebas | MPL‑2.0 (o EPL‑1.0) | `backend/lib/h2.jar` |
| PostgreSQL JDBC 42.7.2 | Conexión a PostgreSQL en producción | BSD‑2‑Clause | `backend/lib/postgresql.jar` |
| JUnit Platform Console 1.9.1 (incluye JUnit Jupiter, opentest4j, picocli, univocity‑parsers) | Pruebas unitarias | EPL‑2.0 y Apache‑2.0 | `backend/lib/test/junit-console.jar` |
| PostgreSQL 16 | Base de datos en producción | PostgreSQL License (tipo BSD/MIT) | Contenedor oficial o instalación propia |

Los `.jar` incluidos son los empaquetados por Ubuntu 24.04 (`libh2-java`, `libpostgresql-jdbc-java`,
`junit5`). Se incluyen para poder compilar y probar sin descargar nada; pueden reemplazarse por las
mismas versiones de Maven Central. Cada uno conserva su licencia original (los archivos de licencia
vienen dentro de los propios `.jar`).

## Datos

| Fuente | Uso | Licencia | Obligación |
|---|---|---|---|
| OpenStreetMap | Red de calles de Cali (geometría, sentidos, velocidades, semáforos) | ODbL 1.0 | Mostrar «© colaboradores de OpenStreetMap»; si se publica una base de datos derivada, mantenerla bajo ODbL |
| Datos Abiertos de Cali (datos.cali.gov.co) | Intersecciones semaforizadas, accidentalidad | **Por verificar por conjunto de datos** | Citar la fuente. Revisar la licencia indicada en cada conjunto antes de publicar |

La red de demostración `RedCali` es **ficticia** (inventada por el equipo) y no contiene datos de terceros.

## Servicios externos que usa la aplicación en funcionamiento

| Servicio | Para qué | Condiciones |
|---|---|---|
| Teselas de OpenStreetMap (`tile.openstreetmap.org`) | Mapa base que se ve debajo de las calles | [Política de uso de teselas](https://operations.osmfoundation.org/policies/tiles/): uso moderado, atribución, y que el navegador envíe de qué sitio viene la petición. Para mucho tráfico hay que usar otro proveedor (variable `VITE_TILES_URL`). |
| Nominatim (`nominatim.openstreetmap.org`) | Buscar lugares por nombre | [Política de uso](https://operations.osmfoundation.org/policies/nominatim/): máximo una consulta por segundo, identificarse con un User-Agent, sin autocompletar. El servidor lo cumple (límite global, caché, solo al pulsar «Buscar»). |
| API Overpass (`overpass-api.de`) | Descargar las calles de una zona | Uso compartido y moderado: no repetir la descarga sin necesidad. |

Los datos de los tres son © colaboradores de OpenStreetMap, licencia ODbL.
