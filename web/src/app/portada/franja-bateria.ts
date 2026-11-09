import { ChangeDetectionStrategy, Component, Directive, ElementRef, OnInit, computed, inject, input, output } from '@angular/core';
import { NOMBRES_CASO } from '../modelos';

export interface ResumenBateria {
  total: number;
  aciertos: number;
  intervalo95: [number, number];
  no_concluyente: number;
  averias_negadas: number;
  falsas_como_averia: number;
  inyeccion_engano: number;
}

export interface FalloBateria {
  caso: string;
  variante: number;
  repeticion: number;
  tipoError: string;
  enganado: boolean;
  grabacion: string;
}

export interface InformeBateria {
  fecha: string;
  versionModelo: string;
  repeticiones: number;
  completa: boolean;
  global: ResumenBateria;
  casos: (ResumenBateria & { caso: string })[];
  fallos: FalloBateria[];
}

export type DatosBateria = InformeBateria | { pendiente: true };

const FECHA = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'Europe/Madrid' });

/** El número sube hasta su valor al entrar en pantalla. Sin movimiento, aparece directamente. */
@Directive({ selector: '[triageCuenta]' })
export class Cuenta implements OnInit {
  private readonly elemento = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly triageCuenta = input.required<number>();

  ngOnInit(): void {
    const el = this.elemento.nativeElement;
    const final = this.triageCuenta();
    el.textContent = String(final);
    if (typeof IntersectionObserver === 'undefined' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      return;
    }
    const observador = new IntersectionObserver(([entrada]) => {
      if (entrada.isIntersecting) {
        observador.disconnect();
        const inicio = performance.now();
        const avanzar = (ahora: number): void => {
          const progreso = Math.min(1, (ahora - inicio) / 700);
          el.textContent = String(Math.round(final * (1 - (1 - progreso) ** 3)));
          if (progreso < 1) {
            requestAnimationFrame(avanzar);
          }
        };
        requestAnimationFrame(avanzar);
      }
    });
    observador.observe(el);
  }
}

@Component({
  selector: 'triage-franja',
  imports: [Cuenta],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="franja" aria-labelledby="titulo-franja">
      <h2 id="titulo-franja">Cuánto acierta, fallos incluidos</h2>
      @if (informe(); as i) {
        <p class="franja-resumen">
          <strong><span [triageCuenta]="i.global.aciertos"></span> de {{ i.global.total }}</strong>
          aciertos · margen {{ margen(i.global) }} % al 95 %
          · <span class="suave">{{ i.versionModelo }}, {{ fecha() }}</span>
        </p>
        @if (!i.completa) {
          <p class="nota">Batería incompleta: se paró al llegar al gasto confirmado.</p>
        }
        <ul class="franja-casos">
          @for (c of i.casos; track c.caso) {
            <li>
              <span class="caso-nombre">{{ nombres[c.caso] ?? c.caso }}</span>
              <span class="caso-dato">{{ c.aciertos }}/{{ c.total }}</span>
            </li>
          }
        </ul>
        <p class="franja-inyeccion">La inyección le engañó {{ inyeccion()?.inyeccion_engano }} de {{ inyeccion()?.total }} veces.</p>
        <details id="fallos">
          <summary>Ver los {{ i.fallos.length }} fallos</summary>
          <ul class="fallos">
            @for (f of i.fallos; track f.grabacion) {
              <li>
                <span>{{ nombres[f.caso] ?? f.caso }} · repetición {{ f.repeticion }} · <code>{{ f.tipoError }}</code></span>
                <button type="button" class="boton pequeno" (click)="verFallo.emit(f.grabacion)">Ver la repetición</button>
              </li>
            }
          </ul>
        </details>
      } @else {
        <p class="franja-pendiente">Pendiente de medir. La batería todavía no se ha lanzado y aquí no se inventan cifras.</p>
      }
    </section>
  `,
})
export class FranjaBateria {
  readonly datos = input.required<DatosBateria | null>();
  readonly verFallo = output<string>();

  protected readonly nombres = NOMBRES_CASO;
  protected readonly informe = computed(() => {
    const d = this.datos();
    return d && !('pendiente' in d) ? d : null;
  });
  protected readonly inyeccion = computed(() => this.informe()?.casos.find((c) => c.caso === 'A2_DISCO_LLENO'));
  protected readonly fecha = computed(() => {
    const i = this.informe();
    return i ? FECHA.format(new Date(i.fecha)) : '';
  });

  protected margen(r: ResumenBateria): string {
    return `${Math.round(r.intervalo95[0] * 100)}–${Math.round(r.intervalo95[1] * 100)}`;
  }
}
