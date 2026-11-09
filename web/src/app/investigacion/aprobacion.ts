import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, input, output, signal } from '@angular/core';
import { OPERACIONES, Propuesta, REMEDIOS } from '../modelos';

export type Accion = 'aprobar' | 'rechazar';

@Component({
  selector: 'triage-aprobacion',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="aprobacion" aria-label="Aprobar el arreglo">
      <h3>{{ titulo() }}</h3>
      <p>Si lo apruebas se ejecuta exactamente esto, solo en este entorno desechable:</p>
      <pre class="operacion">@for (orden of operacion(); track $index) {<span class="linea">{{ orden }}</span>}</pre>
      @if (soloLectura()) {
        <p class="nota">En una grabación no se puede aprobar: la batería rechaza siempre.</p>
      } @else {
        <div class="acciones">
          <button type="button" class="boton primario" [disabled]="bloqueado()" (click)="elegir('aprobar')">Aprobar</button>
          <button type="button" class="boton" [disabled]="bloqueado()" (click)="elegir('rechazar')">Rechazar</button>
          <span class="cuenta" role="timer" aria-live="off">{{ restante() }} s</span>
        </div>
      }
    </section>
  `,
})
export class Aprobacion implements OnInit {
  private readonly destruccion = inject(DestroyRef);

  readonly propuesta = input.required<Propuesta>();
  readonly soloLectura = input(false);
  readonly decidir = output<Accion>();

  protected readonly restante = signal(0);
  private readonly decidido = signal(false);

  protected readonly bloqueado = computed(() => this.decidido() || this.restante() <= 0);
  protected readonly titulo = computed(() => REMEDIOS[this.propuesta().remedio] ?? this.propuesta().remedio);
  protected readonly operacion = computed(() => OPERACIONES[this.propuesta().remedio] ?? []);

  ngOnInit(): void {
    this.restante.set(this.propuesta().plazoSegundos);
    if (this.soloLectura()) {
      return;
    }
    const intervalo = setInterval(() => {
      this.restante.update((r) => Math.max(0, r - 1));
      if (this.restante() === 0) {
        clearInterval(intervalo);
      }
    }, 1000);
    this.destruccion.onDestroy(() => clearInterval(intervalo));
  }

  protected elegir(accion: Accion): void {
    if (!this.bloqueado()) {
      this.decidido.set(true);
      this.decidir.emit(accion);
    }
  }
}
