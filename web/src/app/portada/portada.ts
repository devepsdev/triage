import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, ElementRef, afterNextRender, Injector, inject, signal, viewChild } from '@angular/core';
import { ApiTriage, EstadoPublico } from '../api';
import { Investigacion, RECHAZOS } from '../investigacion/investigacion';
import { Diagrama } from './diagrama';
import { DatosBateria, FranjaBateria } from './franja-bateria';
import { Hero } from './hero';
import { TarjetasCasos } from './tarjetas-casos';

export interface EntradaIndice {
  archivo: string;
  titulo: string;
}

@Component({
  selector: 'triage-portada',
  imports: [Hero, FranjaBateria, TarjetasCasos, Diagrama, Investigacion],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <main>
      <triage-hero [archivo]="archivo()" />

      @if (indice().length > 1) {
        <nav class="grabaciones" aria-label="Otras grabaciones">
          @for (g of indice(); track g.archivo) {
            <button type="button" class="boton pequeno" [attr.aria-pressed]="g.archivo === archivo()" (click)="ver(g.archivo)">{{ g.titulo }}</button>
          }
        </nav>
      }

      <triage-franja [datos]="bateria()" (verFallo)="ver('grabaciones/' + $event + '.json')" />
      <triage-tarjetas (elegir)="elegir($event)" />

      <div #panel class="panel-investigacion">
        @if (caso(); as c) {
          @if (estado()?.disponible) {
            <triage-investigacion [caso]="c" [sitekey]="estado()!.sitekey" (verGrabacion)="irAGrabacion()" />
          } @else {
            <div class="rechazo" role="alert">
              <p>{{ textos[estado()?.motivo ?? 'NO_DISPONIBLE'] ?? textos['NO_DISPONIBLE'] }}</p>
              <button type="button" class="boton primario" (click)="irAGrabacion()">Ver una grabación real</button>
            </div>
          }
        }
      </div>

      <triage-diagrama />
    </main>
    <footer class="pie">
      <p>Un proyecto de <a href="https://portfolio.deveps.dev" rel="noopener">Enrique · DevEps</a>.</p>
    </footer>
  `,
})
export class Portada {
  private readonly http = inject(HttpClient);
  private readonly api = inject(ApiTriage);
  private readonly injector = inject(Injector);
  private readonly panel = viewChild.required<ElementRef<HTMLElement>>('panel');

  protected readonly textos = RECHAZOS;
  protected readonly archivo = signal<string | null>(null);
  protected readonly indice = signal<EntradaIndice[]>([]);
  protected readonly bateria = signal<DatosBateria | null>(null);
  protected readonly estado = signal<EstadoPublico | null>(null);
  protected readonly caso = signal<string | null>(null);

  constructor() {
    this.http.get<EntradaIndice[]>('grabaciones/indice.json').subscribe({
      next: (lista) => {
        this.indice.set(lista);
        this.archivo.set(lista[0]?.archivo ?? null);
      },
      error: () => this.archivo.set(null),
    });
    this.http.get<DatosBateria>('bateria.json').subscribe({ next: (d) => this.bateria.set(d), error: () => this.bateria.set({ pendiente: true }) });
    this.api.estado().subscribe({ next: (e) => this.estado.set(e), error: () => this.estado.set(null) });
  }

  protected elegir(caso: string): void {
    this.caso.set(caso);
    afterNextRender(() => this.panel().nativeElement.scrollIntoView({ block: 'start' }), { injector: this.injector });
  }

  protected ver(archivo: string): void {
    this.archivo.set(archivo);
    this.irAGrabacion();
  }

  protected irAGrabacion(): void {
    document.getElementById('grabacion')?.scrollIntoView({ block: 'start' });
  }
}
