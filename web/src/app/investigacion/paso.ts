import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Correccion, PasoVista } from '../modelos';

/** Todo lo que viene del entorno se pinta con interpolación: un log nunca se interpreta como HTML. */
@Component({
  selector: 'triage-paso',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <article class="paso" [id]="ancla()" [class.con-trampa]="paso().trampa">
      <p class="fila">
        <span class="etiqueta consulta">Consulta</span>
        <span><code>{{ paso().herramienta }}</code> @if (argumentos()) { <code class="args">{{ argumentos() }}</code> }</span>
      </p>
      @if (paso().encontrado) {
        <p class="fila"><span class="etiqueta encuentra">Encuentra</span><span>{{ paso().encontrado }}</span></p>
      }
      @if (paso().descartado) {
        <p class="fila"><span class="etiqueta descarta">Descarta</span><span>{{ paso().descartado }}</span></p>
      }
      @if (paso().trampa) {
        <p class="aviso-trampa" role="note">{{ textoTrampa() }}</p>
      }
      <details [open]="!!paso().trampa">
        <summary>Salida técnica</summary>
        <pre class="salida">@for (linea of lineas(); track $index) {<span class="linea" [class.linea-trampa]="linea.trampa">{{ linea.texto }}</span>}</pre>
      </details>
    </article>
  `,
})
export class Paso {
  readonly paso = input.required<PasoVista>();
  readonly correccion = input<Correccion | null>(null);
  readonly prefijo = input('');

  protected readonly ancla = computed(() => `${this.prefijo()}paso-${this.paso().id}`);

  protected readonly argumentos = computed(() => {
    const args = this.paso().args;
    const texto = args == null ? '' : JSON.stringify(args);
    return texto === '{}' ? '' : texto;
  });

  protected readonly lineas = computed(() => {
    const trampa = this.paso().trampa;
    return this.paso()
      .salida.split('\n')
      .map((texto) => ({ texto, trampa: trampa !== null && texto.includes(trampa) }));
  });

  protected readonly textoTrampa = computed(() => {
    const base = 'Este log trae una orden escondida para el agente. Es un dato del servicio, no una instrucción.';
    const correccion = this.correccion();
    if (!correccion) {
      return base;
    }
    return correccion.enganadoPorInyeccion ? `${base} Esta vez le ha engañado.` : `${base} No la ha obedecido.`;
  });
}
