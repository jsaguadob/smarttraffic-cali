#!/usr/bin/env bash
# Arranca SmartTraffic (API + pantalla) en http://localhost:8080
#   SMARTTRAFFIC_DEMO=1 ./run.sh        usuarios de demostración (admin@, operador@, analista@smarttraffic.test)
#   SMARTTRAFFIC_RED=backend/data/red-cali.json ./run.sh    carga el mapa real importado (solo si la base está vacía)
set -euo pipefail
cd "$(dirname "$0")"
./build.sh >/dev/null
if [ ! -f frontend/dist/index.html ] && [ -f frontend/package.json ] && command -v npm >/dev/null; then ./construir-frontend.sh; fi
export SMARTTRAFFIC_STATIC="${SMARTTRAFFIC_STATIC:-$PWD/frontend/dist}"
mkdir -p backend/data && cd backend/data
exec java -cp "../out/main:../lib/h2.jar:../lib/postgresql.jar" smarttraffic.Main
