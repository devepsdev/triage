import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterRenderEffect, computed, effect, inject, input, signal, viewChild } from '@angular/core';
import { Subscription } from 'rxjs';
import { EventoTriage } from '../modelos';
import { Grabacion, leerGrabacion, reproducir } from '../investigacion/cliente-eventos';
import { LineaTemporal } from '../investigacion/linea-temporal';

const FECHA = new Intl.DateTimeFormat('es-ES', { day: 'numeric', month: 'short', timeZone: 'Europe/Madrid' });

function sinMovimiento(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false;
}

/** Repite eventos reales con la misma interfaz que el directo y dice siempre qué es lo que se está viendo. */
@Component({
  selector: 'triage-reproductor',
  imports: [LineaTemporal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="reproductor">
      <p class="sello" [class.ejemplo]="grabacion()?.ejemplo">{{ sello() }}</p>
      <div class="pantalla" #pantalla tabindex="0" role="group" aria-label="Investigación grabada">
        @if (fallo()) {
          <p class="nota">Esta grabación no se ha podido cargar.</p>
        } @else {
          <linea-temporal [eventos]="eventos()" [soloLectura]="true" prefijo="grabacion-" />
        }
      </div>
      @if (terminada()) {
        <button type="button" class="boton pequeno" (click)="empezar()">Volver a ver</button>
      }
    </div>
  `,
})
export class Reproductor {
  private readonly pantalla = viewChild.required<ElementRef<HTMLElement>>('pantalla');
  private suscripcion?: Subscription;

  readonly archivo = input.required<string>();
  readonly pausaMaxima = input(1200);

  protected readonly grabacion = signal<(Grabacion & { ejemplo?: boolean }) | null>(null);
  protected readonly eventos = signal<readonly EventoTriage[]>([]);
  protected readonly terminada = signal(false);
  protected readonly fallo = signal(false);

  protected readonly sello = computed(() => {
    const g = this.grabacion();
    if (!g) {
      return 'Cargando grabación';
    }
    return g.ejemplo ? 'Ejemplo de desarrollo · no ha ocurrido' : `Grabación real · ${FECHA.format(new Date(g.fecha))} · ${g.modelo}`;
  });

  constructor() {
    inject(DestroyRef).onDestroy(() => this.suscripcion?.unsubscribe());
    effect(() => this.cargar(this.archivo()));
    // Solo se mueve el interior del panel: la página no salta mientras alguien la lee.
    afterRenderEffect(() => {
      this.eventos();
      const el = this.pantalla().nativeElement;
      el.scrollTop = el.scrollHeight;
    });
  }

  private cargar(archivo: string): void {
    this.suscripcion?.unsubscribe();
    this.grabacion.set(null);
    this.eventos.set([]);
    this.terminada.set(false);
    this.fallo.set(false);
    leerGrabacion(archivo).then(
      (g) => {
        if (archivo === this.archivo()) {
          this.grabacion.set(g);
          this.empezar();
        }
      },
      () => this.fallo.set(true),
    );
  }

  protected empezar(): void {
    const g = this.grabacion();
    if (!g) {
      return;
    }
    this.suscripcion?.unsubscribe();
    this.eventos.set([]);
    this.terminada.set(false);
    this.suscripcion = reproducir(g, { pausaMaxima: this.pausaMaxima(), sinPausas: sinMovimiento() }).subscribe({
      next: (e) => this.eventos.update((lista) => [...lista, e]),
      complete: () => this.terminada.set(true),
    });
  }
}
