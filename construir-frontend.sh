#!/usr/bin/env bash
# Compila la pantalla (React + TypeScript) en frontend/dist. Requiere Node 20 o superior.
set -euo pipefail
cd "$(dirname "$0")/frontend"
npm ci --no-audit --no-fund
npm run build
