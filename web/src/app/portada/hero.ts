import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { Foco } from './foco';
import { Reproductor } from './reproductor';

export const TITULAR = 'Un agente de IA investiga averías de servidor en directo. Solo lee: arreglar lo apruebas tú';
export const REPOSITORIO = 'https://github.com/devepsdev/triage';

const LATIDO = 'h90 l8 -5 l8 5 h22 l5 5 l9 -32 l10 40 l6 -13 h22 q10 -10 20 0 h40';

@Component({
  selector: 'triage-hero',
  imports: [Reproductor, Foco],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="hero" triageFoco>
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
    <div class="monitor" aria-hidden="true">
      <svg viewBox="0 0 1600 64" preserveAspectRatio="xMidYMid slice">
        <path class="monitor-base" [attr.d]="latidos" />
        <path class="monitor-pulso" [attr.d]="latidos" pathLength="1" />
      </svg>
    </div>
  `,
})
export class Hero {
  readonly archivo = input<string | null>(null);

  protected readonly titular = TITULAR;
  protected readonly latidos = 'M0 34 ' + Array(7).fill(LATIDO).join(' ');
  protected readonly repositorio = REPOSITORIO;
  protected readonly chips = ['Spring Boot 4', 'Java 25', 'Angular 21', 'MCP', 'Docker'];
}
