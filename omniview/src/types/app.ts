export type AppType = 'engine' | 'store'

export interface AppConfig {
  id: string
  name: string
  host: string
  port: number
  enabled: boolean
  type?: AppType
}

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

export interface AppConnectionState {
  status: ConnectionStatus
  lastError?: string
  lastConnected?: number
}
