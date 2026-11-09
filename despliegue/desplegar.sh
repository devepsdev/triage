#!/usr/bin/env bash
# Instala Triage en el VPS, por fases. Sin --aplicar solo enseña lo que haría: no compila, no se conecta y no toca nada.
#
# Desde Git Bash:
#   bash despliegue/desplegar.sh                     lista todas las fases, sin hacer nada
#   bash despliegue/desplegar.sh --dry-run abrir     lista una fase, sin hacer nada
#   bash despliegue/desplegar.sh --aplicar instalar  servicios sin abrir la web; no activa nada si falla el aislamiento
#   bash despliegue/desplegar.sh --aplicar certificado
#   bash despliegue/desplegar.sh --aplicar abrir     publica la web y el nginx
#   bash despliegue/desplegar.sh --aplicar cerrar    retira la web y para los servicios
# Los scripts remotos van entre comillas simples a propósito: sus variables se expanden en el VPS.
# shellcheck disable=SC2016
set -euo pipefail
cd "$(dirname "$0")/.."

DOMINIO=triage.deveps.dev
HOST="${HOST_VPS:-vps}"
# El ssh de Git Bash no usa el agente de Windows.
SSH="${SSH:-/c/Windows/System32/OpenSSH/ssh.exe}"
FECHA=$(date +%Y%m%d-%H%M%S)
COPIAS=/var/backups/triage/$FECHA
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Java/jdk-25.0.4.1}"

MODO=simulacro
FASE=""
for argumento in "$@"; do
  case "$argumento" in
    --dry-run) MODO=simulacro ;;
    --aplicar) MODO=real ;;
    instalar | certificado | abrir | cerrar) FASE=$argumento ;;
    *) echo "Argumento desconocido: $argumento" >&2; exit 2 ;;
  esac
done
if [ "$MODO" = real ] && [ -z "$FASE" ]; then
  echo "Con --aplicar hay que decir la fase: un permiso vale para una sola." >&2
  exit 2
fi

TEMPORAL=""
trap '[ -z "$TEMPORAL" ] || rm -rf "$TEMPORAL"' EXIT

titulo() { printf '\n== %s ==\n' "$1"; }
parar() { printf '\nPARADO: %s\n' "$1" >&2; exit 1; }
ssh_vps() { MSYS_NO_PATHCONV=1 "$SSH" "$HOST" "set -euo pipefail; $1"; }

# Condición previa: en real detiene; en simulacro avisa de lo que aún falta.
exigir() {
  local descripcion=$1
  shift
  if [ "$MODO" = simulacro ]; then
    if "$@"; then printf '  [listo]    %s\n' "$descripcion"; else printf '  [FALTA]    %s\n' "$descripcion"; fi
  elif ! "$@"; then
    parar "$descripcion"
  fi
}

local_() {
  local descripcion=$1
  shift
  if [ "$MODO" = simulacro ]; then printf '  [este PC]  %s\n' "$descripcion"; return; fi
  printf -- '-> %s\n' "$descripcion"
  "$@"
}

remoto() {
  if [ "$MODO" = simulacro ]; then printf '  [VPS]      %s\n' "$1"; return; fi
  printf -- '-> %s\n' "$1"
  ssh_vps "$2"
}

arbol_limpio() { [ -z "$(git status --porcelain)" ]; }
hay_precios() { compgen -G 'api/triage-api/src/main/resources/db/migration/V2__*.sql' >/dev/null; }
sin_ejemplos() {
  [ ! -e web/public/grabaciones/ejemplo-desarrollo.json ] && ! grep -rqE '"ejemplo"[[:space:]]*:[[:space:]]*true|ejemplo-desarrollo' web/public/grabaciones
}

compilar_api() { (cd api && ./mvnw -q package); }
compilar_web() { (cd web && NG_CLI_ANALYTICS=false npx ng test --watch=false && NG_CLI_ANALYTICS=false npx ng build); }

preparar_paquete() {
  TEMPORAL=$(mktemp -d)
  mkdir -p "$TEMPORAL/api" "$TEMPORAL/mcp" "$TEMPORAL/gestor" "$TEMPORAL/unidades"
  cp api/triage-api/target/triage-api-0.1.0.jar "$TEMPORAL/api/triage-api.jar"
  cp api/triage-mcp/target/triage-mcp-0.1.0.jar "$TEMPORAL/mcp/triage-mcp.jar"
  cp api/triage-gestor/target/triage-gestor-0.1.0.jar "$TEMPORAL/gestor/triage-gestor.jar"
  cp api/triage-comun/target/triage-comun-0.1.0.jar "$TEMPORAL/gestor/triage-comun.jar"
  cp despliegue/*.service despliegue/*.timer despliegue/*.slice "$TEMPORAL/unidades/"
  # Lo versionado, con los saltos de línea de Linux.
  git archive HEAD entornos | tar -x -C "$TEMPORAL"
}

subir_paquete() {
  tar -czf - -C "$TEMPORAL" . | ssh_vps '
    sudo rm -rf /opt/triage/.nuevo
    sudo mkdir -p /opt/triage/.nuevo
    sudo tar -xzf - -C /opt/triage/.nuevo --no-same-owner'
}

fase_instalar() {
  titulo "Instalar los servicios (la web sigue sin publicarse)"
  exigir "Árbol de git sin cambios pendientes: se instala exactamente lo versionado" arbol_limpio
  exigir "Migración V2 con el precio del modelo (sin ella la API no arranca)" hay_precios

  local_ "Compilar y pasar las pruebas de la API (Maven)" compilar_api
  local_ "Preparar el paquete: 4 jar, unidades e imágenes" preparar_paquete
  remoto "Copia de seguridad en $COPIAS (código, unidades y base; sin las claves)" "
    sudo install -d -m 700 '$COPIAS'
    if [ -d /opt/triage ]; then sudo tar -czf '$COPIAS/opt-triage.tgz' --exclude=.nuevo -C /opt triage; fi
    if compgen -G '/etc/systemd/system/triage*' >/dev/null; then sudo sh -c \"tar -czf '$COPIAS/unidades.tgz' /etc/systemd/system/triage*\"; fi
    if sudo mysql -N -e 'SHOW DATABASES' | grep -qx triage; then sudo sh -c \"mysqldump --single-transaction triage | gzip > '$COPIAS/triage.sql.gz'\"; fi"
  remoto "Crear los usuarios triage-api, triage-mcp y triage-gestor (solo el gestor entra en el grupo docker, desde su unidad)" '
    for usuario in triage-api triage-mcp triage-gestor; do
      id -u "$usuario" >/dev/null 2>&1 || sudo useradd --system --user-group --no-create-home --shell /usr/sbin/nologin "$usuario"
    done'
  remoto "Crear la base triage y su usuario, con contraseña generada allí que nunca sale del VPS" "
    sudo install -d -m 700 /etc/triage
    sudo touch /etc/triage/api.env
    sudo chmod 600 /etc/triage/api.env
    if ! sudo grep -q '^TRIAGE_DB_PASSWORD=' /etc/triage/api.env; then
      clave=\$(openssl rand -hex 24)
      printf \"CREATE DATABASE IF NOT EXISTS triage CHARACTER SET utf8mb4; CREATE USER IF NOT EXISTS 'triage'@'127.0.0.1' IDENTIFIED BY '%s'; ALTER USER 'triage'@'127.0.0.1' IDENTIFIED BY '%s'; GRANT ALL PRIVILEGES ON triage.* TO 'triage'@'127.0.0.1';\" \"\$clave\" \"\$clave\" | sudo mysql
      printf 'TRIAGE_DB_PASSWORD=%s\n' \"\$clave\" | sudo tee -a /etc/triage/api.env >/dev/null
    fi"
  local_ "Subir el paquete a /opt/triage/.nuevo (sin activarlo)" subir_paquete
  remoto "Instalar triage.slice y las unidades de systemd (sin arrancarlas)" '
    for archivo in /opt/triage/.nuevo/unidades/*; do
      tr -d "\r" < "$archivo" | sudo tee "/etc/systemd/system/$(basename "$archivo")" >/dev/null
    done
    sudo systemctl daemon-reload'
  remoto "Construir las imágenes de la tienda ficticia" 'bash /opt/triage/.nuevo/entornos/construir.sh'

  if [ "$MODO" = simulacro ]; then
    printf '  [VPS]      Prueba de aislamiento: si algo responde, se detiene aquí y NO se activa nada\n'
  else
    printf -- '-> Prueba de aislamiento (si falla no se activa nada)\n'
    ssh_vps '
      IP_PUBLICA=$(ip -4 -o route get 1.1.1.1 | sed -n "s/.* src \([0-9.]*\).*/\1/p")
      [ -n "$IP_PUBLICA" ] || { echo "No se ha podido saber la IP pública"; exit 1; }
      IP_PUBLICA=$IP_PUBLICA bash /opt/triage/.nuevo/entornos/prueba-aislamiento.sh' ||
      parar "la prueba de aislamiento ha fallado. No se ha activado nada; el paquete sigue en /opt/triage/.nuevo."
  fi

  remoto "Activar el código nuevo y arrancar gestor, MCP, barrido y API" '
    sudo systemctl stop triage-api triage-mcp triage-gestor 2>/dev/null || true
    for carpeta in api mcp gestor entornos; do
      sudo rm -rf "/opt/triage/$carpeta"
      sudo mv "/opt/triage/.nuevo/$carpeta" "/opt/triage/$carpeta"
    done
    sudo rm -rf /opt/triage/.nuevo
    sudo chown root:root /opt/triage
    sudo chmod 755 /opt/triage
    for modulo in api mcp gestor; do
      sudo chown -R "root:triage-$modulo" "/opt/triage/$modulo"
      sudo chmod -R u=rwX,g=rX,o= "/opt/triage/$modulo"
    done
    sudo chown -R root:docker /opt/triage/entornos
    sudo chmod -R u=rwX,g=rX,o= /opt/triage/entornos
    sudo systemctl enable --now triage-gestor triage-mcp triage-barrido.timer triage-api'
  remoto "Comprobar que la API responde en local" '
    for intento in $(seq 1 40); do
      if curl -fsS -o /dev/null http://127.0.0.1:8097/api/estado; then echo "API en marcha"; exit 0; fi
      sleep 2
    done
    echo "ERROR: la API no responde"
    sudo journalctl -u triage-api -n 40 --no-pager
    exit 1'
}

fase_certificado() {
  titulo "Certificado"
  remoto "Comprobar que $DOMINIO resuelve a este servidor y pedir el certificado sin tocar otros sitios" "
    getent hosts '$DOMINIO' >/dev/null || { echo 'El DNS de $DOMINIO aún no existe'; exit 1; }
    sudo certbot certonly --nginx --non-interactive --keep-until-expiring -d '$DOMINIO'"
}

subir_web() {
  tar -czf - -C web/dist/web/browser . | ssh_vps '
    sudo rm -rf /var/www/.triage-nuevo
    sudo mkdir -p /var/www/.triage-nuevo
    sudo tar -xzf - -C /var/www/.triage-nuevo --no-same-owner'
}

subir_nginx() {
  tr -d '\r' < despliegue/nginx-triage.conf | ssh_vps 'sudo tee /etc/nginx/sites-available/.triage-nuevo >/dev/null'
}

fase_abrir() {
  titulo "Abrir al público"
  exigir "Árbol de git sin cambios pendientes" arbol_limpio
  exigir "Sin datos de ejemplo: solo grabaciones reales" sin_ejemplos

  local_ "Pasar las pruebas y compilar la web" compilar_web
  remoto "Comprobar servicios en marcha y certificado (sin ellos no se publica)" "
    systemctl is-active --quiet triage-api triage-mcp triage-gestor
    sudo test -e '/etc/letsencrypt/live/$DOMINIO/fullchain.pem'"
  remoto "Copia de seguridad de la web y del nginx en $COPIAS" "
    sudo install -d -m 700 '$COPIAS'
    if [ -d /var/www/triage ]; then sudo tar -czf '$COPIAS/web.tgz' -C /var/www triage; fi
    if [ -e /etc/nginx/sites-available/$DOMINIO ]; then sudo cp -a /etc/nginx/sites-available/$DOMINIO '$COPIAS/nginx.conf'; fi"
  local_ "Subir la web compilada" subir_web
  local_ "Subir la configuración de nginx" subir_nginx
  remoto "Publicar la web y activar el nginx; si nginx -t falla, se deja como estaba" "
    sudo chown -R root:www-data /var/www/.triage-nuevo
    sudo chmod -R u=rwX,g=rX,o= /var/www/.triage-nuevo
    sudo rm -rf /var/www/triage
    sudo mv /var/www/.triage-nuevo /var/www/triage
    disponible=/etc/nginx/sites-available/$DOMINIO
    activo=/etc/nginx/sites-enabled/$DOMINIO
    anterior=\$(sudo mktemp)
    sudo test -e \"\$disponible\" && sudo cp -a \"\$disponible\" \"\$anterior\" || sudo rm -f \"\$anterior\"
    sudo mv /etc/nginx/sites-available/.triage-nuevo \"\$disponible\"
    sudo ln -sfn \"\$disponible\" \"\$activo\"
    if ! sudo nginx -t; then
      if sudo test -e \"\$anterior\"; then sudo mv \"\$anterior\" \"\$disponible\"; else sudo rm -f \"\$activo\" \"\$disponible\"; fi
      echo 'nginx -t ha fallado: configuración restaurada'
      exit 1
    fi
    sudo rm -f \"\$anterior\"
    sudo systemctl reload nginx"
  remoto "Comprobar la web publicada" "
    curl -fsS -o /dev/null 'https://$DOMINIO/'
    curl -fsS -o /dev/null 'https://$DOMINIO/api/estado'
    echo 'https://$DOMINIO responde'"
}

fase_cerrar() {
  titulo "Cerrar (la web deja de verse; los entornos los borra el barrido)"
  remoto "Retirar el sitio de nginx" "
    sudo rm -f /etc/nginx/sites-enabled/$DOMINIO
    sudo nginx -t
    sudo systemctl reload nginx"
  remoto "Parar API, MCP y gestor (el temporizador de barrido sigue activo)" '
    sudo systemctl disable --now triage-api triage-mcp triage-gestor'
}

if [ "$MODO" = simulacro ]; then
  echo "SIMULACRO: no se compila, no se conecta con el VPS y no se cambia nada."
fi

if [ -n "$FASE" ]; then
  "fase_$FASE"
else
  fase_instalar
  fase_certificado
  fase_abrir
  fase_cerrar
fi

if [ "$MODO" = simulacro ]; then
  printf '\nSIMULACRO terminado: no se ha tocado nada.\n'
fi
