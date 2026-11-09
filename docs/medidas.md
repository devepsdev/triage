# Medidas de Triage

Solo datos medidos, con fecha y lugar. Lo que aún no se ha medido no aparece.

## Herramientas de Mistral (2026-11-09, desde el PC)

Comando: `ComprobarHerramientas`, una petición por versión con una función de prueba y `tool_choice=any`.

| Versión pedida | Versión devuelta | ¿Llamó a la herramienta? | Tokens entrada | Tokens salida |
|---|---|---|---|---|
| `mistral-small-2603` | `mistral-small-2603` | Sí | 102 | 15 |
| `mistral-medium-2604` | `mistral-medium-2604` | Sí | 102 | 15 |

## Imágenes y consumo (2026-11-09, Docker Desktop en el PC)

Referencia local. Las cifras definitivas se miden en el VPS. Arranque de los cuatro contenedores hasta la primera respuesta correcta: 4 s.

| Contenedor | RAM en reposo | Techo | Procesos | Imagen |
|---|---|---|---|---|
| `web` | 3,2 MiB | 64 MB | 2 | 77 MB |
| `app` | 18,2 MiB | 128 MB | 1 | 76 MB |
| `db` | 59,8 MiB | 256 MB | 6 | 399 MB |
| `sonda` | 0,4 MiB | 64 MB | 1 | 28 MB |
