export type ProtocolType = 'FIX' | 'OUCH'

export type SessionState =
  | 'CREATED'
  | 'CONNECTING'
  | 'CONNECTED'
  | 'LOGGED_ON'
  | 'LOGGED_OUT'
  | 'DISCONNECTED'
  | 'RECONNECTING'
  | 'ERROR'

export interface Session {
  sessionId: string
  sessionName: string
  protocolType: ProtocolType
  state: SessionState
  connected: boolean
  loggedOn: boolean
  enabled: boolean
}

export interface SessionStats {
  total: number
  connected: number
  loggedOn: number
  enabled: number
  byProtocol: {
    FIX: { total: number; connected: number; loggedOn: number }
    OUCH: { total: number; connected: number; loggedOn: number }
  }
}

// WebSocket message types
export type WebSocketMessageType =
  | 'INITIAL_STATE'
  | 'STATE_CHANGE'
  | 'SESSION_REGISTERED'
  | 'SESSION_UNREGISTERED'
  | 'STATS_UPDATE'

export interface WebSocketMessage {
  type: WebSocketMessageType
  payload: unknown
}

export interface InitialStatePayload {
  sessions: Session[]
  stats: SessionStats
}

export interface StateChangePayload {
  sessionId: string
  state: SessionState
  connected: boolean
  loggedOn: boolean
}

export interface SessionRegisteredPayload {
  session: Session
}

export interface SessionUnregisteredPayload {
  sessionId: string
}

export interface StatsUpdatePayload {
  stats: SessionStats
}
