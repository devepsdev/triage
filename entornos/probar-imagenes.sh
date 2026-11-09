#!/usr/bin/env bash
# Levanta la tienda ficticia con los límites reales y comprueba que funciona dentro de ellos.
# Solo crea y borra recursos con prefijo triage-prueba. Sale con 0 si todo va bien.
set -u
export MSYS_NO_PATHCONV=1   # Git Bash no debe reescribir las rutas de los contenedores

P=triage-prueba
fallos=0
ok()    { echo "  ok    $1"; }
fallo() { echo "  FALLO $1"; fallos=$((fallos + 1)); }

limpiar() {
  docker rm -f $P-web $P-app $P-db $P-sonda >/dev/null 2>&1
  docker network rm $P-red >/dev/null 2>&1
}
trap limpiar EXIT
limpiar

for imagen in web app db sonda; do
  docker image inspect triage/$imagen >/dev/null 2>&1 || { echo "Falta la imagen triage/$imagen"; exit 1; }
done

docker network create --internal -o com.docker.network.bridge.gateway_mode_ipv4=isolated $P-red >/dev/null || exit 1

comun=(--detach --network $P-red --read-only --cap-drop=ALL --security-opt no-new-privileges
       --cgroup-parent=triage.slice --restart=no --log-opt max-size=2m --label triage.env=prueba)

inicio=$(date +%s)
docker run "${comun[@]}" --name $P-db --network-alias db --user 70:70 --memory=256m --memory-swap=256m --cpus=0.5 --pids-limit=128 \
  --tmpfs /var/lib/postgresql/data:rw,size=96m,uid=70,gid=70,mode=0700 --tmpfs /var/run/postgresql:rw,size=1m,uid=70,gid=70 \
  --tmpfs /tmp:rw,size=8m,uid=70,gid=70 triage/db >/dev/null || exit 1
docker run "${comun[@]}" --name $P-app --network-alias app --user 1000:1000 --memory=128m --memory-swap=128m --cpus=0.25 --pids-limit=64 \
  --tmpfs /var/log/app:rw,size=16m,uid=1000,gid=1000 --tmpfs /tmp:rw,size=4m,uid=1000,gid=1000 triage/app >/dev/null || exit 1
docker run "${comun[@]}" --name $P-web --network-alias web --user 101:101 --memory=64m --memory-swap=64m --cpus=0.25 --pids-limit=64 \
  --tmpfs /etc/nginx/conf.d:rw,size=1m,uid=101,gid=101 --tmpfs /run/tls:rw,size=1m,uid=101,gid=101 --tmpfs /tmp:rw,size=8m,uid=101,gid=101 \
  triage/web >/dev/null || exit 1
docker run "${comun[@]}" --name $P-sonda --user 1000:1000 --memory=64m --memory-swap=64m --cpus=0.25 --pids-limit=32 \
  --tmpfs /tmp:rw,size=1m,uid=1000,gid=1000 triage/sonda >/dev/null || exit 1

sonda() { docker exec $P-sonda "$@"; }

echo "Esperando a la tienda (máximo 60 s)..."
cuerpo=""
for _ in $(seq 1 60); do
  cuerpo=$(sonda curl -fsS --max-time 3 https://web/api/pedidos 2>/dev/null) && break
  cuerpo=""
  sleep 1
done
echo "Arranque hasta primera respuesta: $(($(date +%s) - inicio)) s"

echo "$cuerpo" | grep -q '"producto"' && ok "HTTPS /api/pedidos devuelve filas" || fallo "HTTPS /api/pedidos no devuelve filas: $cuerpo"
[ "$(sonda curl -s -o /dev/null -w '%{http_code}' http://web/salud)" = 200 ] && ok "HTTP /salud da 200" || fallo "HTTP /salud"
[ "$(sonda curl -s -o /dev/null -w '%{http_code}' https://web/)" = 200 ] && ok "HTTPS / da 200" || fallo "HTTPS /"
[ "$(sonda curl -s -o /dev/null -w '%{http_code}' https://web/demo/)" = 200 ] && ok "HTTPS /demo/ da 200" || fallo "HTTPS /demo/"
sonda pg_isready -q -U tienda -d tienda -h db && ok "Postgres acepta conexiones" || fallo "Postgres"

# Lo que escribe la app acaba en el volumen limitado
docker exec $P-app sh -c 'test -s /var/log/app/app.log' && ok "la app escribe en /var/log/app/app.log" || fallo "la app no escribe su log"
sonda curl -s -o /dev/null -A "agente-de-prueba-123" https://web/api/pedidos
docker logs $P-app 2>&1 | grep -q "agente-de-prueba-123" && ok "el User-Agent llega a la salida estándar" || fallo "User-Agent no registrado"

for c in web app db sonda; do
  docker exec $P-$c sh -c 'touch /fuera-de-tmpfs' 2>/dev/null && fallo "$c: la raíz admite escritura" || ok "$c: raíz de solo lectura"
  [ "$(docker exec $P-$c id -u)" != 0 ] && ok "$c: no es root" || fallo "$c: se ejecuta como root"
done

sleep 20   # deja correr las comprobaciones de salud
for c in web app db; do
  estado=$(docker inspect -f '{{.State.Health.Status}}' $P-$c)
  [ "$estado" = healthy ] && ok "$c: salud $estado" || fallo "$c: salud $estado"
done

procesos=$(docker stats --no-stream --format '{{.PIDs}}' $P-web)
[ "$procesos" -le 8 ] && ok "web: $procesos procesos, no depende de las CPU del anfitrión" || fallo "web: $procesos procesos"

echo; echo "Consumo en reposo:"
docker stats --no-stream --format '  {{.Name}}  {{.MemUsage}}  {{.CPUPerc}}  procesos={{.PIDs}}' $P-web $P-app $P-db $P-sonda
echo; echo "Tamaño de las imágenes:"
for imagen in web app db sonda; do
  echo "  triage/$imagen  $(( $(docker image inspect -f '{{.Size}}' triage/$imagen) / 1048576 )) MB"
done

echo
[ "$fallos" = 0 ] && { echo "CORRECTO"; exit 0; }
echo "HAY $fallos FALLOS"; docker logs --tail 15 $P-app 2>&1 | sed 's/^/  app: /'; docker logs --tail 15 $P-web 2>&1 | sed 's/^/  web: /'
exit 1
