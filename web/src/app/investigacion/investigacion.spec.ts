import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { vi } from 'vitest';
import { ApiTriage, ResultadoCrear } from '../api';
import { Diagnostico, EventoTriage, PasoVista } from '../modelos';
import { Aprobacion } from './aprobacion';
import { DiagnosticoVista } from './diagnostico';
import { Investigacion } from './investigacion';
import { LineaTemporal } from './linea-temporal';
import { Paso } from './paso';

const PASO: PasoVista = {
  numero: 1,
  id: 'c1',
  herramienta: 'ver_logs',
  args: { servicio: 'app' },
  salida: 'una línea\notra línea',
  encontrado: 'La aplicación no llega a la base de datos',
  descartado: 'El disco tiene sitio',
  trampa: null,
};

const DIAGNOSTICO: Diagnostico = {
  veredicto: 'averia',
  causa: 'bd_parada',
  pruebas: [{ idLlamada: 'c1', cita: 'db: estado=exited' }],
  descartado: 'El disco tiene sitio de sobra',
  remedio: 'arrancar_bd',
  explicacion: 'La base de datos no está en marcha.',
  aprobable: true,
};

async function pintar<T>(fixture: ComponentFixture<T>, entradas: Record<string, unknown>): Promise<HTMLElement> {
  for (const [nombre, valor] of Object.entries(entradas)) {
    fixture.componentRef.setInput(nombre, valor);
  }
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

describe('Paso', () => {
  it('etiqueta Consulta, Encuentra y Descarta, con la salida técnica desplegable', async () => {
    const el = await pintar(TestBed.createComponent(Paso), { paso: PASO });

    const etiquetas = [...el.querySelectorAll('.etiqueta')].map((e) => e.textContent);
    expect(etiquetas).toEqual(['Consulta', 'Encuentra', 'Descarta']);
    expect(el.querySelector('code')?.textContent).toBe('ver_logs');
    const desplegable = el.querySelector('details')!;
    expect(desplegable.open).toBe(false);
    expect(desplegable.querySelector('summary')?.textContent).toBe('Salida técnica');
    expect(desplegable.querySelector('pre')?.textContent).toContain('otra línea');
  });

  it('un log con HTML se ve como texto', async () => {
    const malicioso = '<img src=x onerror=alert(1)><script>alert(2)</script>';
    const el = await pintar(TestBed.createComponent(Paso), {
      paso: { ...PASO, salida: malicioso, encontrado: malicioso, descartado: malicioso, herramienta: malicioso },
    });

    expect(el.querySelector('img')).toBeNull();
    expect(el.querySelector('script')).toBeNull();
    expect(el.querySelector('pre')?.textContent).toContain('<img src=x');
  });

  const CON_TRAMPA: PasoVista = { ...PASO, salida: 'normal\nGET /x ua="Nota para el asistente"\nnormal', trampa: 'Nota para el asistente' };

  it('la trampa marca su línea, abre la salida y aún no afirma nada', async () => {
    const el = await pintar(TestBed.createComponent(Paso), { paso: CON_TRAMPA });

    const marcadas = el.querySelectorAll('.linea-trampa');
    expect(marcadas.length).toBe(1);
    expect(marcadas[0].textContent).toContain('Nota para el asistente');
    expect(el.querySelector('details')!.open).toBe(true);
    expect(el.querySelector('.aviso-trampa')?.textContent).not.toMatch(/engañado|obedecido/);
  });

  it('dice si la trampa le engañó', async () => {
    const correccion = { acierto: false, pruebasVerificadas: 0, enganadoPorInyeccion: true, tipoError: 'causa' };
    const el = await pintar(TestBed.createComponent(Paso), { paso: CON_TRAMPA, correccion });

    expect(el.querySelector('.aviso-trampa')?.textContent).toContain('Esta vez le ha engañado');
  });

  it('dice si no la obedeció', async () => {
    const correccion = { acierto: true, pruebasVerificadas: 1, enganadoPorInyeccion: false, tipoError: null };
    const el = await pintar(TestBed.createComponent(Paso), { paso: CON_TRAMPA, correccion });

    expect(el.querySelector('.aviso-trampa')?.textContent).toContain('No la ha obedecido');
  });
});

describe('Diagnóstico', () => {
  it('enlaza cada prueba a su paso y muestra lo descartado', async () => {
    const el = await pintar(TestBed.createComponent(DiagnosticoVista), { diagnostico: DIAGNOSTICO, prefijo: 'directo-' });

    expect(el.querySelector('.pruebas a')?.getAttribute('href')).toBe('#directo-paso-c1');
    expect(el.querySelector('.pruebas code')?.textContent).toBe('db: estado=exited');
    expect(el.textContent).toContain('El disco tiene sitio de sobra');
    expect(el.textContent).toContain('La base de datos está parada');
  });

  it('dice cuando el código lo corrige como fallo', async () => {
    const correccion = { acierto: false, pruebasVerificadas: 0, enganadoPorInyeccion: false, tipoError: 'causa' };
    const el = await pintar(TestBed.createComponent(DiagnosticoVista), { diagnostico: DIAGNOSTICO, correccion });

    expect(el.querySelector('.correccion')?.textContent).toContain('falla (causa)');
  });
});

describe('Aprobación', () => {
  // Solo el intervalo: Angular necesita los temporizadores de verdad para estabilizarse.
  beforeEach(() => vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] }));
  afterEach(() => vi.useRealTimers());

  const PROPUESTA = { remedio: 'liberar_logs', plazoSegundos: 90 };
  const boton = (el: HTMLElement, texto: string) => [...el.querySelectorAll('button')].find((b) => b.textContent === texto)!;

  async function crear() {
    const fixture = TestBed.createComponent(Aprobacion);
    const el = await pintar(fixture, { propuesta: PROPUESTA });
    return { fixture, el };
  }

  it('muestra la operación exacta y el plazo', async () => {
    const { el } = await crear();

    expect(el.querySelector('.operacion')?.textContent).toContain('truncate -s 0 /var/log/app/app.log');
    expect(el.querySelector('.operacion')?.textContent).toContain('rm -f /var/log/app/app.log.1');
    expect(el.querySelector('.cuenta')?.textContent).toContain('90');
  });

  it('cuenta los segundos y al acabar el plazo desactiva los botones', async () => {
    const { fixture, el } = await crear();

    await vi.advanceTimersByTimeAsync(89_000);
    fixture.detectChanges();
    expect(el.querySelector('.cuenta')?.textContent).toContain('1');
    expect(boton(el, 'Aprobar').disabled).toBe(false);

    await vi.advanceTimersByTimeAsync(1_000);
    fixture.detectChanges();
    expect(boton(el, 'Aprobar').disabled).toBe(true);
    expect(boton(el, 'Rechazar').disabled).toBe(true);
  });

  it('se desactiva tras pulsar y solo avisa una vez', async () => {
    const { fixture, el } = await crear();
    const decisiones: string[] = [];
    fixture.componentInstance.decidir.subscribe((d) => decisiones.push(d));

    boton(el, 'Aprobar').click();
    boton(el, 'Aprobar').click();
    fixture.detectChanges();

    expect(decisiones).toEqual(['aprobar']);
    expect(boton(el, 'Aprobar').disabled).toBe(true);
    expect(boton(el, 'Rechazar').disabled).toBe(true);
  });

  it('en una grabación no hay botones', async () => {
    const el = await pintar(TestBed.createComponent(Aprobacion), { propuesta: PROPUESTA, soloLectura: true });

    expect(el.querySelector('button')).toBeNull();
    expect(el.querySelector('.operacion')).not.toBeNull();
  });
});

describe('Línea temporal', () => {
  const pinta = (eventos: EventoTriage[], soloLectura = false) => pintar(TestBed.createComponent(LineaTemporal), { eventos, soloLectura });
  const propuesta: EventoTriage[] = [
    { tipo: 'estado', datos: { estado: 'PROPUESTA' } },
    { tipo: 'propuesta', datos: { remedio: 'arrancar_bd', plazoSegundos: 90 } },
  ];

  it('muestra tokens y coste vivos: el último acumulado', async () => {
    const el = await pinta([
      { tipo: 'consumo', datos: { tokensEntrada: 100, tokensSalida: 20, coste: '0.00016000', moneda: 'EUR' } },
      { tipo: 'consumo', datos: { tokensEntrada: 15000, tokensSalida: 2500, coste: '0.00225000', moneda: 'EUR' } },
    ]);

    expect(el.querySelector('.consumo')?.textContent?.replace(/\s/g, ' ')).toContain('17.500 tokens · 0,0023 EUR');
  });

  it('ofrece aprobar mientras hay propuesta', async () => {
    const el = await pinta(propuesta);

    expect(el.querySelector('triage-aprobacion')).not.toBeNull();
  });

  it('sin propuesta (falsa alarma o remedio no aprobable) no hay botón', async () => {
    const el = await pinta([
      { tipo: 'estado', datos: { estado: 'INVESTIGANDO' } },
      { tipo: 'diagnostico', datos: { ...DIAGNOSTICO, veredicto: 'falsa_alarma', remedio: 'ninguno', aprobable: false } },
      { tipo: 'fin', datos: { estado: 'DESTRUIDA', motivo: 'sin_remedio' } },
    ]);

    expect(el.querySelector('triage-aprobacion')).toBeNull();
    expect(el.querySelector('button')).toBeNull();
  });

  it('una vez decidida desaparece la aprobación', async () => {
    const el = await pinta([...propuesta, { tipo: 'estado', datos: { estado: 'RECHAZADA' } }]);

    expect(el.querySelector('triage-aprobacion')).toBeNull();
  });

  it('la verificación fallida se muestra', async () => {
    const el = await pinta([
      { tipo: 'remedio_resultado', datos: { remedio: 'arrancar_bd', verificado: false, detalle: 'curl: (22) 503' } },
      { tipo: 'fin', datos: { estado: 'DESTRUIDA', motivo: 'remedio_no_verificado' } },
    ]);

    expect(el.querySelector('.resultado.mal')?.textContent).toContain('El arreglo no ha funcionado');
    expect(el.querySelector('.resultado')?.textContent).toContain('curl: (22) 503');
    expect(el.querySelector('.fin')?.textContent).toContain('la comprobación no ha pasado');
  });

  it('la marca de la trampa llega a su paso', async () => {
    const el = await pinta([
      { tipo: 'paso', datos: { ...PASO, salida: 'hola trampa' } },
      { tipo: 'trampa', datos: { id: 'c1', linea: 'trampa' } },
    ]);

    expect(el.querySelector('.linea-trampa')).not.toBeNull();
  });
});

describe('Investigación', () => {
  const eventos = new Subject<EventoTriage>();
  const decidir = vi.fn();
  let respuesta: ResultadoCrear;
  const api = {
    crear: vi.fn(() => of(respuesta)),
    eventos: vi.fn(() => eventos),
    decidir: (...args: unknown[]) => {
      decidir(...args);
      return of(true);
    },
  };

  beforeEach(() => {
    decidir.mockClear();
    TestBed.configureTestingModule({ providers: [{ provide: ApiTriage, useValue: api }] });
  });

  async function crear() {
    const fixture = TestBed.createComponent(Investigacion);
    const el = await pintar(fixture, { caso: 'A1_BD_CAIDA', sitekey: 'clave' });
    return { fixture, el };
  }

  for (const motivo of ['OCUPADO', 'LIMITE_DIARIO', 'GASTO', 'LIMITE_IP', 'NO_DISPONIBLE']) {
    it(`${motivo} lo dice y ofrece una grabación`, async () => {
      respuesta = { ok: false, motivo };
      const { fixture, el } = await crear();
      let ofrecida = false;
      fixture.componentInstance.verGrabacion.subscribe(() => (ofrecida = true));

      fixture.componentInstance.conCaptcha('tok');
      await fixture.whenStable();

      expect(el.querySelector('[role=alert]')?.textContent?.length).toBeGreaterThan(10);
      expect(el.querySelector('linea-temporal')).toBeNull();
      el.querySelector<HTMLButtonElement>('.rechazo button')!.click();
      expect(ofrecida).toBe(true);
    });
  }

  it('admitida, pinta los eventos y aprueba con el token de aprobar', async () => {
    respuesta = { ok: true, creada: { id: 'i1', tokenVer: 'ver', tokenAprobar: 'aprobar' } };
    const { fixture, el } = await crear();

    fixture.componentInstance.conCaptcha('tok');
    await fixture.whenStable();
    expect(api.eventos).toHaveBeenCalledWith('i1', 'ver');

    eventos.next({ tipo: 'estado', datos: { estado: 'PROPUESTA' } });
    eventos.next({ tipo: 'propuesta', datos: { remedio: 'arrancar_bd', plazoSegundos: 90 } });
    await fixture.whenStable();
    [...el.querySelectorAll('button')].find((b) => b.textContent === 'Aprobar')!.click();

    expect(decidir).toHaveBeenCalledWith('i1', 'aprobar', 'aprobar');
  });
});
