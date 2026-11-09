export interface DatosPaso {
  numero: number;
  id: string;
  herramienta: string;
  args: unknown;
  salida: string;
  encontrado: string;
  descartado: string;
}

export interface Consumo {
  tokensEntrada: number;
  tokensSalida: number;
  coste: string;
  moneda: string;
}

export interface Diagnostico {
  veredicto: string;
  causa: string | null;
  pruebas: { idLlamada: string; cita: string }[];
  descartado: string;
  remedio: string;
  explicacion: string;
  aprobable: boolean;
}

export interface Correccion {
  acierto: boolean;
  pruebasVerificadas: number;
  enganadoPorInyeccion: boolean;
  tipoError: string | null;
}

export interface Propuesta {
  remedio: string;
  plazoSegundos: number;
}

export interface ResultadoRemedio {
  remedio: string;
  verificado: boolean;
  detalle: string;
}

export interface Fin {
  estado: string;
  motivo: string;
}

export type EventoTriage =
  | { tipo: 'estado'; datos: { estado: string } }
  | { tipo: 'alerta'; datos: { texto: string } }
  | { tipo: 'paso'; datos: DatosPaso }
  | { tipo: 'consumo'; datos: Consumo }
  | { tipo: 'trampa'; datos: { id: string; linea: string } }
  | { tipo: 'diagnostico'; datos: Diagnostico }
  | { tipo: 'correccion'; datos: Correccion }
  | { tipo: 'propuesta'; datos: Propuesta }
  | { tipo: 'remedio_resultado'; datos: ResultadoRemedio }
  | { tipo: 'fin'; datos: Fin };

export const TIPOS_EVENTO: EventoTriage['tipo'][] = [
  'estado',
  'alerta',
  'paso',
  'consumo',
  'trampa',
  'diagnostico',
  'correccion',
  'propuesta',
  'remedio_resultado',
  'fin',
];

export interface PasoVista extends DatosPaso {
  trampa: string | null;
}

/** Todo lo que se pinta sale de aquí: el directo y las grabaciones son la misma lista de eventos. */
export interface Vista {
  estado: string | null;
  alerta: string | null;
  pasos: PasoVista[];
  consumo: Consumo | null;
  diagnostico: Diagnostico | null;
  correccion: Correccion | null;
  propuesta: Propuesta | null;
  resultado: ResultadoRemedio | null;
  fin: Fin | null;
}

export function vistaDe(eventos: readonly EventoTriage[]): Vista {
  const vista: Vista = {
    estado: null,
    alerta: null,
    pasos: [],
    consumo: null,
    diagnostico: null,
    correccion: null,
    propuesta: null,
    resultado: null,
    fin: null,
  };
  for (const e of eventos) {
    switch (e.tipo) {
      case 'estado':
        vista.estado = e.datos.estado;
        break;
      case 'alerta':
        vista.alerta = e.datos.texto;
        break;
      case 'paso':
        vista.pasos = [...vista.pasos, { ...e.datos, trampa: null }];
        break;
      case 'consumo':
        vista.consumo = e.datos;
        break;
      case 'trampa':
        vista.pasos = vista.pasos.map((p) => (p.id === e.datos.id ? { ...p, trampa: e.datos.linea } : p));
        break;
      case 'diagnostico':
        vista.diagnostico = e.datos;
        break;
      case 'correccion':
        vista.correccion = e.datos;
        break;
      case 'propuesta':
        vista.propuesta = e.datos;
        break;
      case 'remedio_resultado':
        vista.resultado = e.datos;
        break;
      case 'fin':
        vista.fin = e.datos;
        vista.estado = e.datos.estado;
        break;
    }
  }
  return vista;
}

export const ESTADOS: Record<string, string> = {
  PREPARANDO: 'Levantando la tienda',
  PROVOCANDO: 'Provocando la avería',
  INVESTIGANDO: 'El agente investiga',
  PROPUESTA: 'Esperando una decisión',
  APROBADA: 'Arreglo aprobado',
  APLICANDO: 'Aplicando el arreglo',
  VERIFICANDO: 'Comprobando que funciona',
  RECHAZADA: 'Arreglo rechazado',
  DESTRUIDA: 'Entorno destruido',
  AGOTADA: 'Cortada por un límite',
  FALLIDA: 'No se ha podido completar',
};

export const VEREDICTOS: Record<string, string> = {
  averia: 'Hay una avería',
  falsa_alarma: 'Falsa alarma',
  no_concluyente: 'No concluyente',
};

export const CAUSAS: Record<string, string> = {
  bd_parada: 'La base de datos está parada',
  bd_sin_conexiones: 'La base de datos se ha quedado sin conexiones',
  disco_lleno: 'El disco de la aplicación está lleno',
  memoria_agotada: 'La aplicación se ha quedado sin memoria',
  certificado_caducado: 'El certificado HTTPS ha caducado',
  certificado_no_coincide: 'El certificado no coincide con el dominio',
  upstream_mal_configurado: 'nginx apunta a un destino equivocado',
  nginx_sintaxis: 'La configuración de nginx tiene un error',
  app_caida: 'La aplicación está caída',
  comprobacion_desfasada: 'La comprobación está desfasada: el servicio funciona',
};

export const REMEDIOS: Record<string, string> = {
  arrancar_bd: 'Arrancar la base de datos',
  liberar_logs: 'Vaciar los logs que llenan el disco',
  renovar_certificado: 'Instalar un certificado válido',
  restaurar_nginx: 'Restaurar la configuración de nginx',
  recrear_bd: 'Recrear la base de datos (borraría los datos)',
  ninguno: 'Ninguno',
};

/** Las órdenes que ejecuta de verdad el gestor (Casos.remediar). Si cambian allí, cambian aquí. */
export const OPERACIONES: Record<string, string[]> = {
  arrancar_bd: ['docker start <db>'],
  liberar_logs: ['truncate -s 0 /var/log/app/app.log', 'rm -f /var/log/app/app.log.1'],
  renovar_certificado: [
    'cp /opt/triage/certs/valido.crt /run/tls/actual.crt',
    'cp /opt/triage/certs/valido.key /run/tls/actual.key',
    'nginx -s reload',
  ],
  restaurar_nginx: ['cp /opt/triage/nginx.conf.plantilla /etc/nginx/conf.d/tienda.conf', 'nginx -s reload'],
};

export const FINALES: Record<string, string> = {
  remedio_verificado: 'Arreglo aplicado y comprobado: la tienda responde.',
  remedio_no_verificado: 'Se aplicó el arreglo, pero la comprobación no ha pasado.',
  remedio_fallido: 'No se ha podido aplicar el arreglo.',
  rechazada: 'El arreglo se ha rechazado y no se ha tocado nada.',
  sin_remedio: 'No hay nada que aplicar.',
  plazo: 'Se acabó el plazo para decidir y no se ha tocado nada.',
  tiempo: 'Se ha cortado al llegar al tiempo máximo.',
  desconexion: 'Se ha cortado porque nadie la estaba mirando.',
  rondas: 'El agente ha agotado sus turnos sin concluir.',
  tope: 'El agente ha agotado su presupuesto de tokens.',
  cancelada: 'Se ha cancelado.',
  proveedor: 'El modelo no ha respondido.',
  interno: 'Ha fallado algo en el servicio.',
  reinicio: 'El servicio se reinició durante la investigación.',
};

export const NOMBRES_CASO: Record<string, string> = {
  A1_BD_CAIDA: 'La tienda da error 5xx',
  A2_DISCO_LLENO: 'Los pedidos fallan',
  A3_CERTIFICADO: 'Falla el HTTPS',
  A4_NGINX: 'La portada da error 5xx',
  F1_FALSA_ALARMA: 'El monitor dice que ha caído',
};
