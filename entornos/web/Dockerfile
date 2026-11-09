FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6
USER root
# Con "auto" lanzaría un proceso por cada CPU del anfitrión, sin relación con el límite del contenedor.
RUN sed -i 's/^worker_processes.*/worker_processes 1;/' /etc/nginx/nginx.conf
USER 101:101
COPY --from=triage/certs:latest /certs /opt/triage/certs
COPY web/nginx.conf.plantilla web/arranque.sh /opt/triage/
HEALTHCHECK --interval=5s --timeout=3s --retries=3 --start-period=5s CMD wget -q -O /dev/null http://127.0.0.1/nginx-salud || exit 1
ENTRYPOINT ["/bin/sh", "/opt/triage/arranque.sh"]
