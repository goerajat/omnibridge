const API_BASE = '/api/sessions'

export interface Session {
  sessionId: string
  sessionName: string
  senderCompId: string
  targetCompId: string
  role: string
  state: string
  connected: boolean
  loggedOn: boolean
  outgoingSeqNum: number
  expectedIncomingSeqNum: number
  host: string
  port: number
  heartbeatInterval: number
}

export interface ActionResponse {
  success: boolean
  message: string
  sessionId: string
  timestamp: string
  data?: Record<string, unknown>
}

export async function fetchSessions(): Promise<Session[]> {
  const response = await fetch(API_BASE)
  if (!response.ok) {
    throw new Error('Failed to fetch sessions')
  }
  return response.json()
}

export async function fetchSession(sessionId: string): Promise<Session> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}`)
  if (!response.ok) {
    throw new Error('Failed to fetch session')
  }
  return response.json()
}

export async function connectSession(sessionId: string): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/connect`, {
    method: 'POST',
  })
  return response.json()
}

export async function disconnectSession(sessionId: string): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/disconnect`, {
    method: 'POST',
  })
  return response.json()
}

export async function logoutSession(sessionId: string, reason?: string): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/logout`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason }),
  })
  return response.json()
}

export async function resetSequence(sessionId: string): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/reset-sequence`, {
    method: 'POST',
  })
  return response.json()
}

export async function setOutgoingSeq(sessionId: string, seqNum: number): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/set-outgoing-seq`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ seqNum }),
  })
  return response.json()
}

export async function setIncomingSeq(sessionId: string, seqNum: number): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/set-incoming-seq`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ seqNum }),
  })
  return response.json()
}

export async function sendTestRequest(sessionId: string): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/send-test-request`, {
    method: 'POST',
  })
  return response.json()
}

export async function triggerEod(sessionId: string): Promise<ActionResponse> {
  const response = await fetch(`${API_BASE}/${encodeURIComponent(sessionId)}/trigger-eod`, {
    method: 'POST',
  })
  return response.json()
}
