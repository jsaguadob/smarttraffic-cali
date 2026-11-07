#!/usr/bin/env bash
# Compila el backend de SmartTraffic. Solo requiere un JDK 17 o superior.
set -euo pipefail
cd "$(dirname "$0")/backend"
rm -rf out/main && mkdir -p out/main
javac --release 17 -encoding UTF-8 -Xlint:all -d out/main $(find src/main/java -name '*.java')
[ -d src/main/resources ] && cp -r src/main/resources/. out/main/ || true
echo "✔ Compilado en backend/out/main"
