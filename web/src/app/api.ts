import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';
import { eventosDirecto, FuenteEventos } from './investigacion/cliente-eventos';

export interface EstadoPublico {
  disponible: boolean;
  motivo: string | null;
  sitekey: string;
  casos: string[];
  contador: { total: number; aciertos: number };
}

export interface Creada {
  id: string;
  tokenVer: string;
  tokenAprobar: string;
}

export type ResultadoCrear = { ok: true; creada: Creada } | { ok: false; motivo: string };

@Injectable({ providedIn: 'root' })
export class ApiTriage {
  private readonly http = inject(HttpClient);

  estado(): Observable<EstadoPublico> {
    return this.http.get<EstadoPublico>('/api/estado');
  }

  crear(caso: string, captcha: string): Observable<ResultadoCrear> {
    return this.http.post<Creada>('/api/investigaciones', { caso, captcha }).pipe(
      map((creada): ResultadoCrear => ({ ok: true, creada })),
      catchError((error: HttpErrorResponse) => of<ResultadoCrear>({ ok: false, motivo: error.error?.motivo ?? 'NO_DISPONIBLE' })),
    );
  }

  /** false si ya no se puede decidir: fuera de plazo o ya decidida. */
  decidir(id: string, accion: 'aprobar' | 'rechazar', token: string): Observable<boolean> {
    return this.http.post(`/api/investigaciones/${encodeURIComponent(id)}/${accion}`, { token }).pipe(
      map(() => true),
      catchError(() => of(false)),
    );
  }

  eventos(id: string, token: string): FuenteEventos {
    return eventosDirecto(id, token);
  }
}
