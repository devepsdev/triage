import { Directive, ElementRef, OnInit, inject, input, numberAttribute } from '@angular/core';

/** Pasa la posición del cursor al CSS para el foco de luz y, si se indican grados, para inclinar el elemento. */
@Directive({ selector: '[triageFoco]' })
export class Foco implements OnInit {
  private readonly elemento = inject<ElementRef<HTMLElement>>(ElementRef);
  readonly grados = input(0, { alias: 'triageFoco', transform: (v: unknown) => numberAttribute(v, 0) });

  ngOnInit(): void {
    const puedeMoverse = window.matchMedia?.('(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)').matches ?? false;
    if (!puedeMoverse) {
      return;
    }
    const el = this.elemento.nativeElement;
    let pendiente = 0;
    el.addEventListener(
      'pointermove',
      (evento) => {
        cancelAnimationFrame(pendiente);
        pendiente = requestAnimationFrame(() => {
          const caja = el.getBoundingClientRect();
          const x = evento.clientX - caja.left;
          const y = evento.clientY - caja.top;
          el.style.setProperty('--x', `${x}px`);
          el.style.setProperty('--y', `${y}px`);
          el.style.setProperty('--rx', `${(0.5 - y / caja.height) * this.grados()}deg`);
          el.style.setProperty('--ry', `${(x / caja.width - 0.5) * this.grados()}deg`);
        });
      },
      { passive: true },
    );
    el.addEventListener('pointerleave', () => {
      cancelAnimationFrame(pendiente);
      el.style.setProperty('--rx', '0deg');
      el.style.setProperty('--ry', '0deg');
    });
  }
}
