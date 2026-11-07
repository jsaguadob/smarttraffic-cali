#!/usr/bin/env bash
# Compila y ejecuta las pruebas unitarias (JUnit 5, incluido en backend/lib/test).
set -euo pipefail
"$(dirname "$0")/build.sh" >/dev/null
cd "$(dirname "$0")/backend"
rm -rf out/test && mkdir -p out/test
CP="out/main:lib/test/junit-console.jar:lib/h2.jar"
javac --release 17 -encoding UTF-8 -cp "$CP" -d out/test $(find src/test/java -name '*.java')
java -jar lib/test/junit-console.jar execute --class-path "out/main:out/test:lib/h2.jar:lib/postgresql.jar" --scan-class-path out/test \
  --details=summary --disable-banner "$@"
