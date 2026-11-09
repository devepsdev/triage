#!/bin/sh
# La raíz es de solo lectura: lo que puede cambiar durante una avería vive en tmpfs y se copia aquí.
set -eu
cp /opt/triage/nginx.conf.plantilla /etc/nginx/conf.d/tienda.conf
cp /opt/triage/certs/valido.crt /run/tls/actual.crt
cp /opt/triage/certs/valido.key /run/tls/actual.key
exec nginx -g 'daemon off;'
