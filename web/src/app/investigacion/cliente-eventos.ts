import { Observable } from 'rxjs';
import { EventoTriage, TIPOS_EVENTO } from '../modelos';

export type FuenteEventos = Observable<EventoTriage>;

export interface EventoGrabado {
  ms: number;
  tipo: EventoTriage['tipo'];
  datos: unknown;
}

export interface Grabacion {
  id: string;
  fecha: string;
  modelo: string;
  caso: string;
  variante: number;
  eventos: EventoGrabado[];
}

/** Eventos de una investigación real. EventSource se reconecta solo y el servidor sigue desde el último recibido. */
export function eventosDirecto(id: string, token: string, abrir: (url: string) => EventSource = (url) => new EventSource(url)): FuenteEventos {
  return new Observable<EventoTriage>((suscriptor) => {
    const fuente = abrir(`/api/investigaciones/${encodeURIComponent(id)}/eventos?token=${encodeURIComponent(token)}`);
    for (const tipo of TIPOS_EVENTO) {
      fuente.addEventListener(tipo, (mensaje) => {
        suscriptor.next({ tipo, datos: JSON.parse((mensaje as MessageEvent<string>).data) } as EventoTriage);
        if (tipo === 'fin') {
          fuente.close();
          suscriptor.complete();
        }
      });
    }
    fuente.onerror = () => {
      // Mientras esté reconectando no pasa nada; cerrado definitivo quiere decir que la investigación ya no existe.
      if (fuente.readyState === fuente.CLOSED) {
        suscriptor.error(new Error('conexion'));
      }
    };
    return () => fuente.close();
  });
}

export async function leerGrabacion(url: string, pedir: typeof fetch = fetch): Promise<Grabacion> {
  const respuesta = await pedir(url);
  if (!respuesta.ok) {
    throw new Error('grabacion');
  }
  return respuesta.json();
}

export interface OpcionesReproduccion {
  /** Las esperas al modelo se acortan para que se pueda ver; el orden y lo que pasó no se tocan. */
  pausaMaxima?: number;
  sinPausas?: boolean;
}

export function reproducir(grabacion: Grabacion, opciones: OpcionesReproduccion = {}): FuenteEventos {
  const pausaMaxima = opciones.sinPausas ? 0 : (opciones.pausaMaxima ?? 1200);
  return new Observable<EventoTriage>((suscriptor) => {
    let temporizador: ReturnType<typeof setTimeout> | undefined;
    const emitir = (i: number): void => {
      const actual = grabacion.eventos[i];
      suscriptor.next({ tipo: actual.tipo, datos: actual.datos } as EventoTriage);
      const siguiente = grabacion.eventos[i + 1];
      if (!siguiente) {
        suscriptor.complete();
        return;
      }
      temporizador = setTimeout(() => emitir(i + 1), Math.min(pausaMaxima, Math.max(0, siguiente.ms - actual.ms)));
    };
    if (grabacion.eventos.length > 0) {
      emitir(0);
    } else {
      suscriptor.complete();
    }
    return () => clearTimeout(temporizador);
  });
}

export function eventosGrabacion(url: string, opciones: OpcionesReproduccion = {}, pedir: typeof fetch = fetch): FuenteEventos {
  return new Observable<EventoTriage>((suscriptor) => {
    let cancelada = false;
    let interna: { unsubscribe(): void } | undefined;
    leerGrabacion(url, pedir).then(
      (grabacion) => {
        if (!cancelada) {
          interna = reproducir(grabacion, opciones).subscribe(suscriptor);
        }
      },
      (error) => suscriptor.error(error),
    );
    return () => {
      cancelada = true;
      interna?.unsubscribe();
    };
  });
}
