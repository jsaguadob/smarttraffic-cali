export type Rol = 'ADMINISTRADOR' | 'OPERADOR' | 'ANALISTA';

export interface Usuario {
  id: number;
  nombre: string;
  correo: string;
  rol: Rol;
  activo?: boolean;
}

export interface NodoRed {
  id: string;
  nombre: string;
  x: number;
  y: number;
  lat: number | null;
  lon: number | null;
  tipo: 'INTERIOR' | 'ENTRADA_SALIDA' | 'BASE_EMERGENCIAS';
  semaforizada: boolean;
  servicios: string[];
}

export interface ViaRed {
  id: string;
  desde: string;
  hacia: string;
  nombre: string;
  longitud: number;
  velocidad: number;
  clase: string;
  manual: boolean;
  puntos: number[];
}

export interface Red {
  nombre: string;
  origen: { lat: number; lon: number } | null;
  intersecciones: NodoRed[];
  vias: ViaRed[];
}

export interface Kpi {
  activos: number;
  enEspera: number;
  velocidadKmh: number;
  congestionadas: number;
  intersecciones: number;
  semaforos: number;
  emergencias: number;
  incidentes: number;
  completados: number;
  viajeMedio: number;
  esperaMedia: number;
  respuestaMedia: number;
  reruteos: number;
  generados: number;
}

export interface EmergenciaVida {
  id: string;
  tipo: number;
  via: number;
  pos: number;
  vel: number;
  atendiendo: boolean;
  ruta: number[];
}

export interface IncidenteVivo {
  id: string;
  via: number;
  pos: number;
  gravedad: number;
  estado: 'ACTIVO' | 'EN_ATENCION' | 'RESUELTO';
  restante: number;
}

export interface EventoSim {
  t: number;
  tipo: string;
  msg: string;
}

export interface RankingVia {
  via: number;
  nombre: string;
  puntaje: number;
  vehiculos: number;
}

/** Estado de la simulación tal como llega del servidor (los vehículos van como arreglos para ocupar poco). */
export interface Instantanea {
  corriendo: boolean;
  velocidad: number;
  semilla: number;
  t: number;
  modo: 'FIJO' | 'INTELIGENTE';
  reruteo: boolean;
  vpm: number;
  kpi: Kpi;
  /** [número, índice de vía, posición (m), velocidad (m/s), tipo] */
  v: number[][];
  e: EmergenciaVida[];
  /** [índice de intersección, fase (0-5), grupo manual (-1 = automático)] */
  s: number[][];
  /** [índice de vía, nivel (0-3), vehículos, bloqueada (0/1)] */
  c: number[][];
  i: IncidenteVivo[];
  rk: RankingVia[];
  ev: EventoSim[];
}

export const TIPOS_VEHICULO = ['Auto', 'Motocicleta', 'Bus', 'Ambulancia', 'Bomberos', 'Policía'] as const;

export interface PuntoCritico {
  id: string;
  nombre: string;
  lat: number;
  lon: number;
  accidentes: number;
  puntaje: number;
}

export interface Criticos {
  total: number;
  asignados: number;
  fueraDeRed: number;
  ranking: PuntoCritico[];
  calor: number[][];
}

export interface Muestra {
  t: number;
  velocidadKmh: number;
  congestionadas: number;
  activos: number;
  viajeMedio: number;
  esperaMedia: number;
}

export interface EscenarioResultado {
  id: number;
  nombre: string;
  generados: number;
  completados: number;
  viajeMedio: number;
  viajeMedioAjustado: number;
  esperaMedia: number;
  velocidadMedia: number;
  viasCongestionadasMedia: number;
  respuestaMedia: number;
  emergenciasAtendidas: number;
  reruteos: number;
  serie: Muestra[];
}

export interface Comparacion {
  semilla: number;
  vehiculosPorMinuto: number;
  duracion: number;
  escenarios: EscenarioResultado[];
}

export interface FilaHistorial {
  id: number;
  tipo: string;
  modo: string;
  semilla: number;
  vehiculosPorMinuto: number;
  duracion: number;
  creado: string;
  usuario: string | null;
}

export interface ResultadoEdicion {
  simulable: boolean;
  problemas: string[];
  id: string | null;
}
