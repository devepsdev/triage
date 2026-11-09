#!/bin/sh
# CA local, un certificado válido y dos ya caducados. `openssl ca` permite fijar fechas en el pasado.
set -eu
D=$1
T=/tmp/ca
mkdir -p "$D" "$T/nuevos"
touch "$T/indice"
echo 1000 > "$T/serie"
ahora=$(date -u +%s)
fecha() { date -u -d "@$((ahora + $1 * 86400))" +%y%m%d%H%M%SZ; }

cat > "$T/ca.cnf" <<CNF
[ca]
default_ca = local
[local]
database = $T/indice
new_certs_dir = $T/nuevos
serial = $T/serie
certificate = $D/ca.crt
private_key = $T/ca.key
default_md = sha256
policy = politica
unique_subject = no
[politica]
commonName = supplied
[ext_ca]
basicConstraints = critical,CA:TRUE
keyUsage = critical,keyCertSign,cRLSign
[ext_web]
basicConstraints = CA:FALSE
keyUsage = critical,digitalSignature
extendedKeyUsage = serverAuth
subjectAltName = DNS:web,DNS:tienda.interna
CNF

openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=CA de la tienda" -keyout "$T/ca.key" -out "$T/ca.csr" 2>/dev/null
openssl ca -batch -notext -selfsign -config "$T/ca.cnf" -extensions ext_ca -startdate "$(fecha -1000)" -enddate "$(fecha 7300)" \
  -keyfile "$T/ca.key" -in "$T/ca.csr" -out "$D/ca.crt" 2>/dev/null

emitir() {
  openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -subj "/CN=tienda.interna" -keyout "$D/$1.key" -out "$T/$1.csr" 2>/dev/null
  openssl ca -batch -notext -config "$T/ca.cnf" -extensions ext_web -startdate "$(fecha "$2")" -enddate "$(fecha "$3")" -in "$T/$1.csr" -out "$D/$1.crt" 2>/dev/null
}
emitir valido -30 3650
emitir caducado-3 -100 -3
emitir caducado-200 -300 -200
chmod 644 "$D"/*
rm -rf "$T"
