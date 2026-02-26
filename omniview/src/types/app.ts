export type AppType = 'engine' | 'store'

export interface AppConfig {
  id: string
  name: string
  host: string
  port: number
  enabled: boolean
  type?: AppType
}

export interface TrackedOrder {
  clOrdId: string
  orderId: number
  symbol: string
  side: string
  orderType: string
  qty: number
  price: number
  state: string
  sessionId: string
  createTime: number
  filledQty: number
  leavesQty: number
  avgFillPrice: number
  messages: MessageEvent[]
}

export interface MessageEvent {
  timestamp: number
  direction: 'SENT' | 'RECEIVED'
  msgType: string
  msgTypeName: string
  fields: Record<string, string>
  rawMessage: string
}

export interface OrderCapabilities {
  canSendOrders: boolean
  canManageOrders: boolean
}

export type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

export interface AppConnectionState {
  status: ConnectionStatus
  lastError?: string
  lastConnected?: number
}
