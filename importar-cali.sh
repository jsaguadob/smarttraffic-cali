#!/usr/bin/env bash
# Descarga el mapa REAL de Cali (OpenStreetMap) y lo convierte en la red de SmartTraffic.
# Requiere internet. Ejemplos:
#   ./importar-cali.sh                                            # centro de Cali
#   ./importar-cali.sh --bbox 3.40,-76.56,3.46,-76.50             # otra zona (sur,oeste,norte,este)
#   ./importar-cali.sh --semaforos semaforos.csv                  # además, marca los semáforos de los datos abiertos
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
java -cp backend/out/main smarttraffic.datos.ImportarCali "$@"
echo
echo "Ahora reinicia con:  SMARTTRAFFIC_RED=backend/data/red-cali.json ./run.sh   (la primera vez que se carga)"
