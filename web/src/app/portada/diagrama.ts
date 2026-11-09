import { ChangeDetectionStrategy, Component } from '@angular/core';

@Component({
  selector: 'triage-diagrama',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="diagrama" aria-labelledby="titulo-diagrama">
      <h2 id="titulo-diagrama">Por qué el agente no puede arreglar</h2>
      <p class="ayuda">No es una norma que el modelo deba respetar. La herramienta para escribir no existe en su lado.</p>
      <ol class="piezas">
        <li class="pieza">
          <h3>triage-api</h3>
          <p class="tecnologia">Spring Boot 4 · Java 25</p>
          <p>Lleva el bucle del agente, los topes y los estados. Es la única que puede pedir un arreglo, y solo después de que una persona lo apruebe.</p>
          <p class="enlace-pieza">Herramientas por MCP</p>
        </li>
        <li class="pieza">
          <h3>triage-mcp</h3>
          <p class="tecnologia">Servidor MCP</p>
          <p>Once herramientas cerradas y de solo lectura. El modelo no puede pedir lo que no está en la lista.</p>
          <p class="enlace-pieza">Socket de lectura</p>
        </li>
        <li class="pieza">
          <h3>triage-gestor</h3>
          <p class="tecnologia">Java sin dependencias</p>
          <p>El único con acceso a Docker, con operaciones fijas. Arreglar va por otro socket al que el servidor MCP no tiene permiso de entrar.</p>
        </li>
      </ol>
      <p class="corte"><span>De triage-mcp a Docker no hay camino: el socket de control solo admite a triage-api.</span></p>
    </section>
  `,
})
export class Diagrama {}
