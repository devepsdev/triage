import { vi } from 'vitest';
import { EventoTriage, vistaDe } from '../modelos';
import { eventosDirecto, eventosGrabacion, Grabacion, reproducir } from './cliente-eventos';

const GRABACION: Grabacion = {
  id: 'g1',
  fecha: '2026-11-09T10:00:00Z',
  modelo: 'm',
  caso: 'A1_BD_CAIDA',
  variante: 1,
  eventos: [
    { ms: 0, tipo: 'estado', datos: { estado: 'PREPARANDO' } },
    { ms: 500, tipo: 'alerta', datos: { texto: 'caída' } },
    { ms: 30_500, tipo: 'fin', datos: { estado: 'DESTRUIDA', motivo: 'sin_remedio' } },
  ],
};

class FuenteFalsa {
  readonly oyentes = new Map<string, (m: unknown) => void>();
  readonly CLOSED = 2;
  readyState = 1;
  cerrada = false;
  onerror: (() => void) | null = null;
  addEventListener(tipo: string, oyente: (m: unknown) => void) {
    this.oyentes.set(tipo, oyente);
  }
  close() {
    this.cerrada = true;
  }
  emitir(tipo: string, datos: unknown) {
    this.oyentes.get(tipo)!({ data: JSON.stringify(datos) });
  }
}

describe('eventos en directo', () => {
  it('abre con el token, convierte los eventos y termina en el fin', () => {
    const fuente = new FuenteFalsa();
    const urls: string[] = [];
    const recibidos: EventoTriage[] = [];
    let terminado = false;
    eventosDirecto('i1', 'tok', (url) => {
      urls.push(url);
      return fuente as unknown as EventSource;
    }).subscribe({ next: (e) => recibidos.push(e), complete: () => (terminado = true) });

    fuente.emitir('estado', { estado: 'INVESTIGANDO' });
    fuente.emitir('fin', { estado: 'DESTRUIDA', motivo: 'x' });

    expect(urls).toEqual(['/api/investigaciones/i1/eventos?token=tok']);
    expect(recibidos.map((e) => e.tipo)).toEqual(['estado', 'fin']);
    expect(terminado).toBe(true);
    expect(fuente.cerrada).toBe(true);
  });

  it('un corte con reconexión no es un error; un cierre definitivo sí', () => {
    const fuente = new FuenteFalsa();
    let error = false;
    eventosDirecto('i', 't', () => fuente as unknown as EventSource).subscribe({ error: () => (error = true) });

    fuente.readyState = 0;
    fuente.onerror!();
    expect(error).toBe(false);

    fuente.readyState = 2;
    fuente.onerror!();
    expect(error).toBe(true);
  });
});

describe('grabaciones', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('reproduce en orden y acorta las esperas largas sin saltarse nada', async () => {
    const recibidos: string[] = [];
    let terminado = false;
    reproducir(GRABACION, { pausaMaxima: 1000 }).subscribe({ next: (e) => recibidos.push(e.tipo), complete: () => (terminado = true) });

    expect(recibidos).toEqual(['estado']);
    await vi.advanceTimersByTimeAsync(499);
    expect(recibidos).toEqual(['estado']);
    await vi.advanceTimersByTimeAsync(1);
    expect(recibidos).toEqual(['estado', 'alerta']);
    await vi.advanceTimersByTimeAsync(1000);
    expect(recibidos).toEqual(['estado', 'alerta', 'fin']);
    expect(terminado).toBe(true);
  });

  it('solo pide el archivo de la grabación', async () => {
    const pedir = vi.fn(async () => new Response(JSON.stringify(GRABACION)));
    const recibidos: string[] = [];

    eventosGrabacion('/grabaciones/g1.json', { sinPausas: true }, pedir as unknown as typeof fetch).subscribe((e) => recibidos.push(e.tipo));
    await vi.advanceTimersByTimeAsync(10);

    expect(pedir).toHaveBeenCalledTimes(1);
    expect(pedir).toHaveBeenCalledWith('/grabaciones/g1.json');
    expect(recibidos).toEqual(['estado', 'alerta', 'fin']);
  });

  it('al dejar de mirarla no sigue emitiendo', async () => {
    const recibidos: string[] = [];
    const s = reproducir(GRABACION, { pausaMaxima: 1000 }).subscribe((e) => recibidos.push(e.tipo));

    s.unsubscribe();
    await vi.advanceTimersByTimeAsync(5000);

    expect(recibidos).toEqual(['estado']);
  });
});

describe('vista', () => {
  it('el estado final manda sobre el último estado intermedio', () => {
    const vista = vistaDe([
      { tipo: 'estado', datos: { estado: 'INVESTIGANDO' } },
      { tipo: 'fin', datos: { estado: 'AGOTADA', motivo: 'tiempo' } },
    ]);

    expect(vista.estado).toBe('AGOTADA');
  });
});
