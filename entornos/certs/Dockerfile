# Certificados de la CA local de la tienda ficticia. No protegen nada real.
FROM alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
RUN apk add --no-cache openssl
COPY generar-certs.sh /generar-certs.sh
RUN sh /generar-certs.sh /certs
