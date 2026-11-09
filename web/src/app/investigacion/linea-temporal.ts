import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { ESTADOS, EventoTriage, FINALES, vistaDe } from '../modelos';
import { Accion, Aprobacion } from './aprobacion';
import { DiagnosticoVista } from './diagnostico';
import { Paso } from './paso';

/** Lo mismo para una investigación en directo y para una grabación: solo cambia de dónde llegan los eventos. */
@Component({
  selector: 'linea-temporal',
  imports: [Paso, DiagnosticoVista, Aprobacion],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="cabecera-linea">
      <p class="estado" role="status" [class.vivo]="!vista().fin">{{ estado() }}</p>
      @if (vista().consumo; as c) {
        <p class="consumo">{{ tokens() }} tokens · {{ coste() }} {{ c.moneda }}</p>
      }
    </div>

    @if (vista().alerta; as alerta) {
      <p class="alerta"><span class="etiqueta alarma">Alerta</span><span>{{ alerta }}</span></p>
    }

    <ol class="pasos">
      @for (paso of vista().pasos; track paso.id) {
        <li><triage-paso [paso]="paso" [correccion]="vista().correccion" [prefijo]="prefijo()" /></li>
      }
    </ol>

    @if (vista().diagnostico; as d) {
      <triage-diagnostico [diagnostico]="d" [correccion]="vista().correccion" [prefijo]="prefijo()" />
    }

    @if (mostrarAprobacion()) {
      <triage-aprobacion [propuesta]="vista().propuesta!" [soloLectura]="soloLectura()" (decidir)="decidir.emit($event)" />
    }

    @if (vista().resultado; as r) {
      <p class="resultado" [class.mal]="!r.verificado" role="status">
        {{ r.verificado ? 'Comprobado: la tienda vuelve a responder.' : 'El arreglo no ha funcionado.' }}
        @if (r.detalle) { <code>{{ r.detalle }}</code> }
      </p>
    }

    @if (vista().fin; as f) {
      <p class="fin">{{ finales[f.motivo] ?? f.motivo }} El entorno ya no existe.</p>
    }
  `,
})
export class LineaTemporal {
  readonly eventos = input.required<readonly EventoTriage[]>();
  readonly soloLectura = input(false);
  readonly prefijo = input('');
  readonly decidir = output<Accion>();

  protected readonly finales = FINALES;
  protected readonly vista = computed(() => vistaDe(this.eventos()));
  protected readonly estado = computed(() => {
    const estado = this.vista().estado;
    return estado ? (ESTADOS[estado] ?? estado) : 'Preparando';
  });
  protected readonly tokens = computed(() => {
    const c = this.vista().consumo;
    return c ? (c.tokensEntrada + c.tokensSalida).toLocaleString('es-ES') : '';
  });
  protected readonly coste = computed(() => {
    const c = this.vista().consumo;
    return c ? Number(c.coste).toLocaleString('es-ES', { minimumFractionDigits: 4, maximumFractionDigits: 4 }) : '';
  });
  protected readonly mostrarAprobacion = computed(() => {
    const v = this.vista();
    return v.propuesta !== null && (this.soloLectura() || v.estado === 'PROPUESTA');
  });
}
