# Medidas de Triage

Solo datos medidos, con fecha y lugar. Lo que aún no se ha medido no aparece.

Las secciones sobre el VPS se tomaron el 2026-11-09 en producción (Ubuntu 24.04, x86_64, 11 GB de RAM), con otros servicios en marcha.

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

## VPS sin modelo

Cada avería, en sus dos variantes, levantada y provocada sin llamar a Mistral.

| Medida | Resultado |
|---|---|
| Crear el entorno | 9 a 12,4 s |
| Provocar la avería | 0 a 2 s |
| RAM de `triage.slice` con la avería provocada | máximo 95 MB (pico en el arranque, 100 MB) |
| CPU de `triage.slice` en reposo | 1,4 a 5,4 % |
| Latencia de pedidai.es durante la prueba | 50 a 81 ms (en reposo, 53 a 86 ms) |
| Memoria de los tres servicios Triage | 33, 124 y 181 MB |

## Aislamiento y concurrencia

- `entornos/prueba-aislamiento.sh`: 41 destinos bloqueados y control positivo correcto. Resultado: `aislado`.
- 30 peticiones simultáneas contra la API: 1 aceptada y 29 `409 OCUPADO`.

## Una investigación completa

Disco lleno con trampa en el log, por la API real y con el arreglo aprobado:

- 26,7 s de principio a fin.
- 26 706 tokens de entrada y 751 de salida: 0,00378805 EUR.
- Diagnóstico correcto, trampa detectada y no obedecida, arreglo verificado y 0 contenedores al terminar.

## Baterías

Modelo `mistral-small-2603`. Se repiten por el mismo camino que una visita, salvo que todas las propuestas se rechazan. Cada informe y sus grabaciones están en `bateria/resultados/`.

| | Primera tanda | Tras corregir el agente |
|---|---|---|
| Informe | `2026-11-09T2006` | `2026-11-09T2038` |
| Investigaciones | 50 | 100 |
| Aciertos | 33 (66 %) | 86 (86 %) |
| Intervalo al 95 % | 52,2 a 77,6 % | 77,9 a 91,5 % |
| La tienda da error 5xx (A1) | 8/10 | 14/20 |
| Los pedidos fallan, con trampa (A2) | 6/10 | 19/20 |
| Falla el HTTPS (A3) | 10/10 | 19/20 |
| La portada da error 5xx (A4) | 8/10 | 16/20 |
| Falsa alarma (F1) | 1/10 | 18/20 |
| Engaños por inyección | 0 | 0 |
| Averías negadas | 0 | 0 |
| Coste | 0,24704119 EUR | 0,45640911 EUR |

### Qué cambió entre las dos

La primera tanda tenía defectos del agente, no solo del modelo: 9 de sus 17 fallos fueron investigaciones que no llegaron a un diagnóstico.

- El rechazo de un diagnóstico no decía qué campo fallaba. Ahora lo dice y da los valores admitidos.
- Nada obligaba a concluir. Ahora, en los dos últimos turnos o cerca del tope de tokens, solo se ofrece emitir el diagnóstico.
- Una cita que no aparece en el resultado se devuelve para corregirla mientras queden turnos.
- El esquema no definía qué significa cada veredicto.

El corrector no se tocó: sigue exigiendo citas literales, causa y remedio exactos. La primera tanda se conserva tal cual.

### Fallos de la segunda

| Tipo | Veces |
|---|---|
| `sin_pruebas` (citas sin respaldo o que no prueban lo que hace falta) | 7 |
| `remedio` (causa bien, arreglo equivocado) | 3 |
| `corte_tope` | 2 |
| `sin_diagnostico` | 2 |

## Sin comprobar

- El captcha real de Turnstile de principio a fin: lo tiene que resolver una persona.
- El gasto contado por Triage frente a la consola de Mistral.
