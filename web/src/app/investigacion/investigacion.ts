import { ChangeDetectionStrategy, Component, DestroyRef, inject, input, output, signal } from '@angular/core';
import { Subscription } from 'rxjs';
import { ApiTriage, Creada } from '../api';
import { EventoTriage, NOMBRES_CASO } from '../modelos';
import { Accion } from './aprobacion';
import { Captcha } from './captcha';
import { LineaTemporal } from './linea-temporal';

const RECHAZOS: Record<string, string> = {
  OCUPADO: 'Ahora mismo hay otra investigación en marcha. Solo se hace una a la vez.',
  LIMITE_IP: 'Ya has lanzado las investigaciones de hoy desde esta conexión.',
  LIMITE_DIARIO: 'Por hoy se han acabado las investigaciones en directo.',
  GASTO: 'Se ha llegado al tope de gasto de hoy.',
  NO_DISPONIBLE: 'El directo no está disponible en este momento.',
  CAPTCHA: 'No se ha podido comprobar que eres una persona.',
  CASO_INVALIDO: 'Ese caso no existe.',
  CONEXION: 'Se ha perdido la conexión con la investigación.',
};

@Component({
  selector: 'triage-investigacion',
  imports: [Captcha, LineaTemporal],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 class="titulo-caso">{{ nombres[caso()] ?? caso() }}</h2>

    @if (rechazo(); as motivo) {
      <div class="rechazo" role="alert">
        <p>{{ textos[motivo] ?? textos['NO_DISPONIBLE'] }}</p>
        <button type="button" class="boton primario" (click)="verGrabacion.emit()">Ver una grabación real</button>
      </div>
    } @else if (creada()) {
      <linea-temporal [eventos]="eventos()" prefijo="directo-" (decidir)="decidir($event)" />
      @if (decisionTardia()) {
        <p class="nota" role="alert">Ya no se puede decidir: se ha acabado el plazo.</p>
      }
    } @else {
      <p class="ayuda">Antes de levantar los contenedores, una comprobación rápida.</p>
      <triage-captcha [sitekey]="sitekey()" (token)="conCaptcha($event)" (fallo)="rechazo.set('CAPTCHA')" />
    }
  `,
})
export class Investigacion {
  private readonly api = inject(ApiTriage);
  private suscripcion?: Subscription;

  readonly caso = input.required<string>();
  readonly sitekey = input.required<string>();
  readonly verGrabacion = output<void>();

  protected readonly nombres = NOMBRES_CASO;
  protected readonly textos = RECHAZOS;
  protected readonly creada = signal<Creada | null>(null);
  protected readonly eventos = signal<readonly EventoTriage[]>([]);
  protected readonly rechazo = signal<string | null>(null);
  protected readonly decisionTardia = signal(false);

  constructor() {
    inject(DestroyRef).onDestroy(() => this.suscripcion?.unsubscribe());
  }

  conCaptcha(token: string): void {
    this.api.crear(this.caso(), token).subscribe((resultado) => {
      if (!resultado.ok) {
        this.rechazo.set(resultado.motivo);
        return;
      }
      this.creada.set(resultado.creada);
      this.suscripcion = this.api.eventos(resultado.creada.id, resultado.creada.tokenVer).subscribe({
        next: (evento) => this.eventos.update((lista) => [...lista, evento]),
        error: () => this.rechazo.set('CONEXION'),
      });
    });
  }

  protected decidir(accion: Accion): void {
    const creada = this.creada();
    if (creada) {
      this.api.decidir(creada.id, accion, creada.tokenAprobar).subscribe((aceptada) => this.decisionTardia.set(!aceptada));
    }
  }
}
