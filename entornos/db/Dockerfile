FROM postgres:16-alpine@sha256:aa6cb33cf4c19c765cefcf48d96176258800870a9a1e930ad126200f9d825139
ENV POSTGRES_USER=tienda POSTGRES_PASSWORD=tienda-ficticia POSTGRES_DB=tienda
COPY db/semilla.sql /docker-entrypoint-initdb.d/semilla.sql
HEALTHCHECK --interval=5s --timeout=3s --retries=3 --start-period=10s CMD pg_isready -q -U tienda -d tienda -h 127.0.0.1 || exit 1
CMD ["postgres", "-c", "shared_buffers=16MB", "-c", "max_connections=20", "-c", "fsync=off", "-c", "synchronous_commit=off", "-c", "full_page_writes=off"]
