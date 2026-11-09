import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { Grabacion } from '../investigacion/cliente-eventos';
import { FranjaBateria, InformeBateria } from './franja-bateria';
import { Hero } from './hero';
import { Reproductor } from './reproductor';

async function pintar<T>(fixture: ComponentFixture<T>, entradas: Record<string, unknown>): Promise<HTMLElement> {
  for (const [nombre, valor] of Object.entries(entradas)) {
    fixture.componentRef.setInput(nombre, valor);
  }
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

const GRABACION: Grabacion = {
  id: 'g1',
  fecha: '2026-11-09T10:00:00Z',
  modelo: 'mistral-small-2603',
  caso: 'A1_BD_CAIDA',
  variante: 1,
  eventos: [
    { ms: 0, tipo: 'estado', datos: { estado: 'INVESTIGANDO' } },
    { ms: 10, tipo: 'alerta', datos: { texto: 'GET /api/pedidos responde 5xx' } },
  ],
};

describe('Hero', () => {
  it('tiene el titular exacto y las tecnologías', async () => {
    const el = await pintar(TestBed.createComponent(Hero), {});

    expect(el.querySelector('h1')?.textContent).toBe('Un agente de IA investiga averías de servidor en directo. Solo lee: arreglar lo apruebas tú');
    expect([...el.querySelectorAll('.chips li')].map((c) => c.textContent)).toEqual(['Spring Boot 4', 'Java 25', 'Angular 21', 'MCP', 'Docker']);
    expect(el.querySelector('a[href="#casos"]')?.textContent).toBe('Provoca una avería');
    expect(el.querySelector('a[href*="github.com"]')).not.toBeNull();
  });

  it('sin grabaciones no se inventa ninguna', async () => {
    const el = await pintar(TestBed.createComponent(Hero), {});

    expect(el.querySelector('triage-reproductor')).toBeNull();
    expect(el.textContent).toContain('pendientes de medir');
  });
});

describe('Reproductor', () => {
  afterEach(() => vi.unstubAllGlobals());

  function servir(datos: unknown) {
    const pedir = vi.fn(async () => new Response(JSON.stringify(datos)));
    vi.stubGlobal('fetch', pedir);
    return pedir;
  }

  it('arranca sola y se etiqueta con su fecha', async () => {
    servir(GRABACION);
    const el = await pintar(TestBed.createComponent(Reproductor), { archivo: 'grabaciones/g1.json', pausaMaxima: 0 });

    await vi.waitFor(() => expect(el.textContent).toContain('GET /api/pedidos responde 5xx'));
    expect(el.querySelector('.sello')?.textContent).toContain('Grabación real · 9 nov');
  });

  it('con movimiento reducido no hay esperas: se ve todo de golpe', async () => {
    servir({ ...GRABACION, eventos: [GRABACION.eventos[0], { ...GRABACION.eventos[1], ms: 60_000 }] });
    vi.stubGlobal('matchMedia', () => ({ matches: true }));

    const el = await pintar(TestBed.createComponent(Reproductor), { archivo: 'grabaciones/g1.json', pausaMaxima: 60_000 });

    await vi.waitFor(() => expect(el.textContent).toContain('GET /api/pedidos'));
  });

  it('no llama a la API', async () => {
    const pedir = servir(GRABACION);
    const el = await pintar(TestBed.createComponent(Reproductor), { archivo: 'grabaciones/g1.json', pausaMaxima: 0 });

    await vi.waitFor(() => expect(el.textContent).toContain('GET /api/pedidos'));
    expect(pedir.mock.calls.map((c: unknown[]) => c[0])).toEqual(['grabaciones/g1.json']);
  });

  it('un ejemplo de desarrollo nunca se presenta como real', async () => {
    servir({ ...GRABACION, ejemplo: true });
    const el = await pintar(TestBed.createComponent(Reproductor), { archivo: 'grabaciones/e.json', pausaMaxima: 0 });

    await vi.waitFor(() => expect(el.querySelector('.sello')?.textContent).toContain('Ejemplo de desarrollo'));
    expect(el.querySelector('.sello')?.textContent).not.toContain('Grabación real');
  });

  it('en una grabación no se puede aprobar', async () => {
    servir({ ...GRABACION, eventos: [{ ms: 0, tipo: 'propuesta', datos: { remedio: 'arrancar_bd', plazoSegundos: 90 } }] });
    const el = await pintar(TestBed.createComponent(Reproductor), { archivo: 'grabaciones/g1.json', pausaMaxima: 0 });

    await vi.waitFor(() => expect(el.querySelector('.aprobacion')).not.toBeNull());
    expect([...el.querySelectorAll('.aprobacion button')]).toEqual([]);
  });
});

describe('Franja de la batería', () => {
  const RESUMEN = { no_concluyente: 1, averias_negadas: 0, falsas_como_averia: 1, intervalo95: [0.58, 0.92] as [number, number] };
  const INFORME: InformeBateria = {
    fecha: '2026-11-09T10:00:00Z',
    versionModelo: 'mistral-small-2603',
    repeticiones: 20,
    completa: true,
    global: { ...RESUMEN, total: 20, aciertos: 16, inyeccion_engano: 2 },
    casos: [{ ...RESUMEN, caso: 'A2_DISCO_LLENO', total: 20, aciertos: 16, inyeccion_engano: 2 }],
    fallos: [{ caso: 'A2_DISCO_LLENO', variante: 1, repeticion: 7, tipoError: 'causa', enganado: true, grabacion: 'g-7' }],
  };

  it('muestra aciertos, margen, inyección y fallos con enlace a su repetición', async () => {
    const fixture = TestBed.createComponent(FranjaBateria);
    const el = await pintar(fixture, { datos: INFORME });
    const pedidos: string[] = [];
    fixture.componentInstance.verFallo.subscribe((g) => pedidos.push(g));

    expect(el.querySelector('.franja-resumen')?.textContent).toContain('16');
    expect(el.querySelector('.franja-resumen')?.textContent).toContain('58–92 %');
    expect(el.textContent).toContain('La inyección le engañó 2 de 20 veces');
    expect(el.querySelector('summary')?.textContent).toContain('1 fallos');
    expect(el.querySelector('.fallos')?.textContent).toContain('repetición 7');
    el.querySelector<HTMLButtonElement>('.fallos button')!.click();
    expect(pedidos).toEqual(['g-7']);
  });

  it('avisa si la batería quedó incompleta', async () => {
    const el = await pintar(TestBed.createComponent(FranjaBateria), { datos: { ...INFORME, completa: false } });

    expect(el.textContent).toContain('Batería incompleta');
  });

  it('sin batería real no inventa cifras', async () => {
    const el = await pintar(TestBed.createComponent(FranjaBateria), { datos: { pendiente: true } });

    expect(el.textContent).toContain('Pendiente de medir');
    expect(el.querySelector('section')?.textContent).not.toMatch(/\d/);
  });
});
