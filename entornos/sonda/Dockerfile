FROM alpine:3.22@sha256:49340b346798875b5592e5d420241f2f9cfba5f47e62ebb1446a1b34734e898f
RUN apk add --no-cache curl openssl postgresql16-client ca-certificates
COPY --from=triage/certs:latest /certs/ca.crt /usr/local/share/ca-certificates/tienda-ca.crt
RUN update-ca-certificates
USER 1000:1000
CMD ["tail", "-f", "/dev/null"]
