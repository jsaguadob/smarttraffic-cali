# SmartTraffic

Centro de control de movilidad urbana de código abierto, hecho en **Java**, sobre el **mapa real de Cali
(OpenStreetMap)** y **datos abiertos** de la ciudad. Proyecto académico de Estructuras de Datos.

> No controla semáforos ni sensores reales. Los vehículos animados son una **estimación del flujo** sobre
> calles reales; los incidentes y emergencias los registra el operador.

## Compilar y probar (solo requiere un JDK 17+)

```bash
./build.sh    # compila backend/src/main/java en backend/out/main
./test.sh     # ejecuta las pruebas con JUnit 5
```

## Estructuras de datos propias (`backend/src/main/java/smarttraffic/estructuras`)

Lista doblemente enlazada · cola FIFO · cola de prioridad (montículo) · tabla hash · árbol AVL · grafo dirigido.
Cada una está probada contra la biblioteca estándar de Java.

## Licencia

Código bajo licencia **MIT** (ver `LICENSE`). Componentes y datos de terceros: `THIRD_PARTY_NOTICES.md`.
