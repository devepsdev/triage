#!/bin/bash
# Comprueba que un entorno de Triage no llega a ningún sitio fuera de su propia red.
# Termina con error si responde algún destino prohibido, o si la sonda NO llega a la app (el control positivo).
# Uso: IP_PUBLICA=<ip del host> ./prueba-aislamiento.sh     (la IP es opcional en local)
set -u
export MSYS_NO_PATHCONV=1

ID=aislamiento$$
RED=t$ID-red
SONDA=t$ID-sonda
APP=t$ID-app
CADUCA=$(($(date +%s) + 600))
PUERTOS="80 443 3306 3573 5432 8000 8096"
ETIQUETAS="--label triage.env=$ID --label triage.caduca=$CADUCA"
DUROS="--detach --pull=never --network $RED --read-only --cap-drop=ALL --security-opt no-new-privileges --restart=no $ETIQUETAS"
FALLOS=0

limpiar() {
  docker rm -f "$SONDA" "$APP" >/dev/null 2>&1
  docker network rm "$RED" >/dev/null 2>&1
}
trap limpiar EXIT

docker network create --internal -o com.docker.network.bridge.gateway_mode_ipv4=isolated $ETIQUETAS "$RED" >/dev/null || exit 2
docker run $DUROS --name "$APP" --network-alias app --user 1000:1000 --memory=128m --memory-swap=128m --cpus=0.25 --pids-limit=64 \
  --tmpfs /var/log/app:rw,size=16m,uid=1000,gid=1000 --tmpfs /tmp:rw,size=4m,uid=1000,gid=1000 triage/app >/dev/null || exit 2
docker run $DUROS --name "$SONDA" --network-alias sonda --user 1000:1000 --memory=64m --memory-swap=64m --cpus=0.25 --pids-limit=32 \
  --tmpfs /tmp:rw,size=1m,uid=1000,gid=1000 triage/sonda >/dev/null || exit 2

conecta() {
  docker exec "$SONDA" curl -sS -v --connect-timeout 2 -m 3 "telnet://$1:$2" 2>&1 </dev/null | grep -q "Connected to"
}

prohibido() {
  if conecta "$1" "$2"; then
    printf '%-34s %s\n' "$1:$2" "RESPONDE  <-- FALLO"
    FALLOS=$((FALLOS + 1))
  else
    printf '%-34s %s\n' "$1:$2" "bloqueado"
  fi
}

printf '%-34s %s\n' "DESTINO" "RESULTADO"

# Control positivo: si esto no funciona, todos los «bloqueado» de abajo no demuestran nada.
for i in 1 2 3 4 5 6 7 8 9 10; do
  docker exec "$SONDA" curl -fsS -m 2 http://app:8080/salud >/dev/null 2>&1 && break
  sleep 1
done
if docker exec "$SONDA" curl -fsS -m 2 http://app:8080/salud >/dev/null 2>&1; then
  printf '%-34s %s\n' "app:8080 (misma red)" "llega, como debe"
else
  printf '%-34s %s\n' "app:8080 (misma red)" "NO LLEGA  <-- FALLO"
  FALLOS=$((FALLOS + 1))
fi

prohibido 8.8.8.8 53
prohibido 1.1.1.1 443
prohibido 169.254.169.254 80

docker exec "$SONDA" curl -sS -m 4 -o /dev/null http://example.com/ >/dev/null 2>&1 </dev/null
CODIGO=$?
if [ "$CODIGO" = 6 ] || [ "$CODIGO" = 28 ]; then
  printf '%-34s %s\n' "DNS de example.com" "bloqueado"
else
  printf '%-34s %s\n' "DNS de example.com" "RESUELVE (curl $CODIGO)  <-- FALLO"
  FALLOS=$((FALLOS + 1))
fi

if docker exec "$APP" wget -q -T 2 -O /dev/null http://1.1.1.1/ >/dev/null 2>&1 </dev/null; then
  printf '%-34s %s\n' "1.1.1.1:80 desde app" "RESPONDE  <-- FALLO"
  FALLOS=$((FALLOS + 1))
else
  printf '%-34s %s\n' "1.1.1.1:80 desde app" "bloqueado"
fi

if [ -n "${IP_PUBLICA:-}" ]; then
  for puerto in $PUERTOS; do prohibido "$IP_PUBLICA" "$puerto"; done
else
  printf '%-34s %s\n' "IP pública del host" "SIN PROBAR (falta IP_PUBLICA)"
fi

for pasarela in $(docker network ls -q | xargs docker network inspect -f '{{range .IPAM.Config}}{{.Gateway}} {{end}}' | tr ' ' '\n' | grep '\.' | sort -u); do
  for puerto in $PUERTOS; do prohibido "$pasarela" "$puerto"; done
done

IP_FACIALY=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}' facialy_db 2>/dev/null | awk '{print $1}')
if [ -n "$IP_FACIALY" ]; then
  prohibido "$IP_FACIALY" 5432
else
  printf '%-34s %s\n' "facialy_db" "no existe en esta máquina"
fi

for puerto in $PUERTOS; do prohibido host.docker.internal "$puerto"; done

echo
case "$(docker info -f '{{.OperatingSystem}}')" in
  *"Docker Desktop"*) echo "AVISO: esto es Docker Desktop. NO vale como prueba definitiva; la definitiva se hace en el VPS." ;;
esac

if [ "$FALLOS" -gt 0 ]; then
  echo "RESULTADO: $FALLOS fallos."
  exit 1
fi
echo "RESULTADO: aislado."
