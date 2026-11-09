import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Reproductor } from './reproductor';

export const TITULAR = 'Un agente de IA investiga averías de servidor en directo. Solo lee: arreglar lo apruebas tú';
export const REPOSITORIO = 'https://github.com/devepsdev/triage';

@Component({
  selector: 'triage-hero',
  imports: [Reproductor],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="hero">
      <div class="hero-texto">
        <p class="marca">Triage</p>
        <h1>{{ titular }}</h1>
        <ul class="chips" aria-label="Tecnologías">
          @for (chip of chips; track chip) {
            <li>{{ chip }}</li>
          }
        </ul>
        <div class="acciones">
          <a class="boton primario" href="#casos">Provoca una avería</a>
          <a class="boton" [href]="repositorio" rel="noopener">Código en GitHub</a>
        </div>
      </div>
      <div class="hero-grabacion" id="grabacion">
        @if (archivo(); as a) {
          <triage-reproductor [archivo]="a" />
        } @else {
          <p class="nota">Las grabaciones están pendientes de medir.</p>
        }
      </div>
    </header>
  `,
})
export class Hero {
  readonly archivo = input<string | null>(null);

  protected readonly titular = TITULAR;
  protected readonly repositorio = REPOSITORIO;
  protected readonly chips = ['Spring Boot 4', 'Java 25', 'Angular 21', 'MCP', 'Docker'];
}
