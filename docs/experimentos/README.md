# Experimentos reproducibles

Programas que producen los números del documento del proyecto (capítulo 7). No forman parte del servidor.

| Archivo | Qué hace | Resultado guardado |
|---|---|---|
| `Experimento.java` | Compara semáforos de tiempo fijo y adaptativos en la red de demostración: 5 cargas (30, 60, 90, 150 y 220 vehículos por minuto) × 10 semillas (100 a 109), 600 s simulados cada una, más un escenario con dos accidentes para medir la respuesta de las emergencias | `resultados.json` |
| `Escala.java` | Mide el costo de un paso de simulación, de planificar una ruta y de A* frente a Dijkstra en una red grande (784 intersecciones, 3.024 tramos) | `escala.txt` |

```bash
./build.sh                                                   # compila el servidor
javac --release 17 -cp backend/out/main -d /tmp/exp docs/experimentos/*.java
java -cp backend/out/main:/tmp/exp Experimento > resultados.json
node frontend/e2e/red-grande.cjs > /tmp/red-grande.json      # genera la red grande
java -cp backend/out/main:/tmp/exp Escala /tmp/red-grande.json
```

Los resultados son de un **modelo** sobre una red de demostración ficticia: sirven para comparar estrategias entre sí y no como
medición de las calles de Cali. Los tiempos de `escala.txt` dependen del computador donde se ejecutan.
