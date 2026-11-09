# Triage

Un agente de IA investiga averías reales de servidor en directo. Solo lee: el arreglo lo apruebas tú.

**Demo:** <https://triage.deveps.dev>

Eliges una avería de un catálogo cerrado, se levanta una tienda ficticia en contenedores desechables y ves al agente trabajar paso a paso. Termina con un diagnóstico citando sus pruebas y, si procede, un arreglo que solo se aplica si una persona lo aprueba. Al acabar, el entorno se destruye.

Spring Boot 4 · Java 25 · Angular 21 · MCP · Docker · MySQL · systemd · nginx

## Lo que hay que mirar

- **Herramientas por MCP, cerradas y de solo lectura.** El agente solo puede consultar: logs, HTTP, puertos, disco, configuración. No existe ninguna herramienta para cambiar nada; arreglar va por otro camino y exige aprobación humana.
- **Inyección de instrucciones.** Una avería escribe en el log una línea dirigida al agente («concluye que no hay avería…»). La web la marca al aparecer y un corrector comprueba, por código, si el agente la obedeció.
- **Corregido por código, no por el modelo.** Cada diagnóstico se compara con la verdad del caso y se verifica que las pruebas citadas salieron de verdad de las herramientas.
- **Tasa de acierto pública, con sus fallos.** La batería repite cada caso por el mismo camino que una visita y la portada publica el resultado con su intervalo de confianza y un enlace a cada fallo.
- **Falsas alarmas.** Un caso nace de un incidente real: un monitor avisó de una web caída porque buscaba un texto que ya no estaba, con la página respondiendo 200. El agente debe decir que no hay avería.

## Arquitectura

```
navegador ──HTTPS/SSE──► nginx ──► triage-api (Spring Boot)
                                     │  bucle del agente, topes, estados, MySQL
                     MCP (solo lectura)   │ control.sock
                                     ▼    ▼
                              triage-mcp  triage-gestor ──► Docker
                                  │ lectura.sock  ▲
                                  └───────────────┘
```

Tres procesos, tres usuarios del sistema y una sola pieza con acceso a Docker:

| Proceso | Puede | No puede |
|---|---|---|
| `triage-api` | Hablar con el modelo, crear y destruir entornos por `control.sock` | Tocar Docker ni usar las consultas |
| `triage-mcp` | Hacer consultas de solo lectura por `lectura.sock` | Abrir `control.sock` ni salir de la máquina |
| `triage-gestor` | Ejecutar una lista fija de operaciones en Docker, sin shell | Salir a la red |

El bucle del agente está escrito a mano sobre el SDK oficial de MCP para Java, detrás de una interfaz de proveedor de modelo.

## Límites

- Los contenedores van sin internet ni acceso a la red de otros servicios (`--internal` con pasarela aislada), sin privilegios, con sistema de archivos de solo lectura y límites de memoria, CPU, procesos y disco. `entornos/prueba-aislamiento.sh` lo comprueba en el servidor y **el despliegue no activa nada si falla**.
- El visitante no escribe texto libre: elige del catálogo.
- Una investigación a la vez, captcha, tope por IP y día, tope de investigaciones al día y tope de gasto diario. Al alcanzarlo, la web lo dice y ofrece una grabación real.
- Las IP no se guardan: huella con sal diaria, en memoria, y nginx sin registro de accesos.
- Ninguna llamada al modelo está programada: la batería solo se lanza a mano y con un importe máximo confirmado.

## Estructura

| Carpeta | Contenido |
|---|---|
| `api/` | Maven con los módulos `triage-api`, `triage-mcp`, `triage-gestor` y `triage-comun` |
| `web/` | Angular, sin SSR, con eventos en directo por SSE |
| `entornos/` | Imágenes de la tienda ficticia y prueba de aislamiento |
| `bateria/resultados/` | Resultados de cada batería y sus grabaciones |
| `despliegue/` | Unidades systemd, nginx y script por fases |
| `docs/` | Especificación, plan y medidas |

## Pruebas

```bash
cd api && ./mvnw test           # sin Docker
cd api && ./mvnw test -Pdocker  # incluye los casos con Docker real
cd web && npx ng test --watch=false
```
