#!/usr/bin/env bash
# Construye las imágenes de la tienda ficticia. Necesita internet; los entornos, no.
set -euo pipefail
cd "$(dirname "$0")"
docker build -q -t triage/certs certs
for imagen in web app db sonda; do
  docker build -q -t "triage/$imagen" -f "$imagen/Dockerfile" .
done
