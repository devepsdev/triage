import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, OnInit, inject, input, output } from '@angular/core';

interface Turnstile {
  render(elemento: HTMLElement, opciones: Record<string, unknown>): string;
  remove(id: string): void;
}

declare global {
  interface Window {
    turnstile?: Turnstile;
  }
}

const URL_TURNSTILE = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
let carga: Promise<void> | undefined;

function cargarScript(): Promise<void> {
  carga ??= new Promise((resolver, rechazar) => {
    const script = document.createElement('script');
    script.src = URL_TURNSTILE;
    script.async = true;
    script.onload = () => resolver();
    script.onerror = () => rechazar(new Error('captcha'));
    document.head.appendChild(script);
  });
  return carga;
}

/** Solo se carga al elegir un caso: quien solo mira la portada no habla con Cloudflare. */
@Component({
  selector: 'triage-captcha',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: '',
})
export class Captcha implements OnInit, OnDestroy {
  private readonly elemento = inject<ElementRef<HTMLElement>>(ElementRef);
  private widget: string | undefined;
  private destruido = false;

  readonly sitekey = input.required<string>();
  readonly token = output<string>();
  readonly fallo = output<void>();

  ngOnInit(): void {
    cargarScript().then(
      () => {
        if (!this.destruido && window.turnstile) {
          this.widget = window.turnstile.render(this.elemento.nativeElement, {
            sitekey: this.sitekey(),
            language: 'es',
            callback: (token: string) => this.token.emit(token),
            'error-callback': () => this.fallo.emit(),
          });
        }
      },
      () => this.fallo.emit(),
    );
  }

  ngOnDestroy(): void {
    this.destruido = true;
    if (this.widget) {
      window.turnstile?.remove(this.widget);
    }
  }
}
