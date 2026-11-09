import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CAUSAS, Correccion, Diagnostico, REMEDIOS, VEREDICTOS } from '../modelos';

@Component({
  selector: 'triage-diagnostico',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="diagnostico" aria-label="Diagnóstico">
      <h3 class="veredicto" [attr.data-veredicto]="diagnostico().veredicto">{{ veredicto() }}</h3>
      @if (causa()) {
        <p class="causa">{{ causa() }}</p>
      }
      <p class="explicacion">{{ diagnostico().explicacion }}</p>

      @if (diagnostico().pruebas.length) {
        <h4>Pruebas</h4>
        <ul class="pruebas">
          @for (prueba of diagnostico().pruebas; track $index) {
            <li><a [href]="'#' + prefijo() + 'paso-' + prueba.idLlamada">Paso {{ numero(prueba.idLlamada) }}</a> <code>{{ prueba.cita }}</code></li>
          }
        </ul>
      }
      @if (diagnostico().descartado) {
        <h4>Descartado</h4>
        <p>{{ diagnostico().descartado }}</p>
      }
      @if (diagnostico().remedio !== 'ninguno') {
        <h4>Arreglo que propone</h4>
        <p>{{ remedio() }}</p>
      }
      @if (correccion(); as c) {
        <p class="correccion" [class.mal]="!c.acierto" role="status">
          {{ c.acierto ? 'Corregido por código: acierta.' : 'Corregido por código: falla (' + (c.tipoError ?? 'error') + ').' }}
        </p>
      }
    </section>
  `,
})
export class DiagnosticoVista {
  readonly diagnostico = input.required<Diagnostico>();
  readonly correccion = input<Correccion | null>(null);
  readonly prefijo = input('');

  protected numero(idLlamada: string): string {
    return idLlamada.replace(/^c/, '');
  }

  protected readonly veredicto = computed(() => VEREDICTOS[this.diagnostico().veredicto] ?? this.diagnostico().veredicto);
  protected readonly causa = computed(() => {
    const causa = this.diagnostico().causa;
    return causa ? (CAUSAS[causa] ?? causa) : null;
  });
  protected readonly remedio = computed(() => REMEDIOS[this.diagnostico().remedio] ?? this.diagnostico().remedio);
}
