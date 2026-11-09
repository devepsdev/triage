# Despliegue de Triage

Todo se hace desde este PC con `despliegue/desplegar.sh`, en Git Bash. Sin `--aplicar` solo lista lo que haría.

```bash
bash despliegue/desplegar.sh
```

## Qué queda en el VPS

| Pieza | Dónde | Usuario | Acceso |
|---|---|---|---|
| Gestor de entornos | `triage-gestor.service`, `/opt/triage/gestor` | `triage-gestor` | El único con grupo `docker`. Sin red |
| MCP de solo lectura | `triage-mcp.service`, `127.0.0.1:8098` | `triage-mcp` | Solo `lectura.sock` y red local |
| API | `triage-api.service`, `127.0.0.1:8097` | `triage-api` | Solo `control.sock`, MySQL e internet (modelo y Turnstile); redes privadas vetadas |
| Barrido | `triage-barrido.timer`, cada 5 min | `triage-gestor` | Solo recursos caducados con etiqueta `triage.env`. Nunca llama al modelo |
| Contenedores | `triage.slice` | n/d | Techo común: `MemoryMax=1G`, `CPUQuota=200%` |
| Web | `/var/www/triage`, `/etc/nginx/sites-available/triage.deveps.dev` | `www-data` | Sin `access_log` |
| Claves | `/etc/triage/api.env` (600 root) | n/d | Revisar solo con `sudo cut -d= -f1 /etc/triage/api.env` |
| Sockets | `/run/triage-gestor/{control,lectura}.sock` (660) | n/d | Grupos `triage-api` y `triage-mcp` |

Los límites de memoria de cada servicio (`MemoryMax` y `-Xmx`) son valores iniciales: están **pendientes de medir** en el VPS.

## Orden de puesta en marcha

Cada paso con servidor o con gasto necesita un «sí» propio.

1. **Enrique:** crear el DNS `triage.deveps.dev`, el widget de Turnstile para ese dominio y una clave de Mistral exclusiva, y confirmar los precios de `mistral-small-2603` y `mistral-medium-2604` en la consola de Mistral.
2. Añadir la migración `V2__precios.sql` con esos precios (sin ella la API no arranca) y hacer commit.
3. `bash despliegue/desplegar.sh --aplicar claves`, escritas por Enrique en su terminal, sin que se vean.
4. `bash despliegue/desplegar.sh --aplicar instalar`. Construye las imágenes, pasa la prueba de aislamiento y solo entonces arranca los servicios.
5. Comprobar que `triage-mcp` no puede abrir el socket de control (debe dar «Permiso denegado»):

   ```bash
   /c/Windows/System32/OpenSSH/ssh.exe vps 'sudo -u triage-mcp test -w /run/triage-gestor/control.sock && echo FALLO || echo correcto'
   ```

6. Medir sin modelo, investigaciones reales y batería (ver abajo). Después exportar las grabaciones reales y retirar `web/public/grabaciones/ejemplo-desarrollo.json`.
7. `bash despliegue/desplegar.sh --aplicar certificado`.
8. `bash despliegue/desplegar.sh --aplicar abrir`. Se niega si quedan datos de ejemplo.

## Batería

Gasta dinero real: nunca se lanza sola. `--confirmo-coste` es el máximo aceptado y la batería se para al alcanzarlo.

```bash
/c/Windows/System32/OpenSSH/ssh.exe vps 'sudo systemd-run --uid=triage-api --gid=triage-api --working-directory=/var/lib/triage-api -p EnvironmentFile=/etc/triage/api.env -p Environment=TRIAGE_HUECO=/run/triage-api/hueco.lock -p Environment=TRIAGE_CONTROL_SOCK=/run/triage-gestor/control.sock --wait --pipe /usr/bin/java -Xmx256m -jar /opt/triage/api/triage-api.jar --bateria --modelo=mistral-small-2603 --repeticiones=20 --confirmo-coste=IMPORTE'
```

Comparte el hueco con la web, así que no coincide con una investigación del público. Los resultados quedan en `/var/lib/triage-api/bateria/resultados/`; se copian a `bateria/resultados/` del repositorio.

## Volver atrás

- **Quitar la web y parar todo:** `bash despliegue/desplegar.sh --aplicar cerrar`.
- **Versión anterior:** cada despliegue guarda copia en `/var/backups/triage/<fecha>/` (`opt-triage.tgz`, `unidades.tgz`, `triage.sql.gz`, `web.tgz`, `nginx.conf`). Para restaurar el código:

  ```bash
  /c/Windows/System32/OpenSSH/ssh.exe vps 'sudo systemctl stop triage-api triage-mcp triage-gestor && sudo tar -xzf /var/backups/triage/FECHA/opt-triage.tgz -C /opt && sudo systemctl start triage-gestor triage-mcp triage-api'
  ```

  Las migraciones de Flyway no se deshacen solas: si la versión nueva cambió la base, hay que restaurar también `triage.sql.gz`.
- **Claves:** no están en las copias; siguen en `/etc/triage/api.env`.
