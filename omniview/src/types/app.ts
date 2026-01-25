export interface AppConfig {
  id: string
  name: string
  host: string
  port: number
  enabled: boolean
}

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

export interface AppConnectionState {
  status: ConnectionStatus
  lastError?: string
  lastConnected?: number
}
