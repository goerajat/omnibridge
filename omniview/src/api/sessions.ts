import type { AppConfig, Session, SessionStats } from '../types'

function buildUrl(app: AppConfig, path: string): string {
  return `http://${app.host}:${app.port}${path}`
}

export async function fetchSessions(app: AppConfig): Promise<Session[]> {
  const response = await fetch(buildUrl(app, '/api/sessions'))
  if (!response.ok) {
    throw new Error(`Failed to fetch sessions: ${response.statusText}`)
  }
  return response.json()
}

export async function fetchSessionStats(app: AppConfig): Promise<SessionStats> {
  const response = await fetch(buildUrl(app, '/api/sessions/stats'))
  if (!response.ok) {
    throw new Error(`Failed to fetch session stats: ${response.statusText}`)
  }
  return response.json()
}

export async function enableSession(
  app: AppConfig,
  sessionId: string
): Promise<void> {
  const response = await fetch(
    buildUrl(app, `/api/sessions/${encodeURIComponent(sessionId)}/enable`),
    { method: 'POST' }
  )
  if (!response.ok) {
    throw new Error(`Failed to enable session: ${response.statusText}`)
  }
}

export async function disableSession(
  app: AppConfig,
  sessionId: string
): Promise<void> {
  const response = await fetch(
    buildUrl(app, `/api/sessions/${encodeURIComponent(sessionId)}/disable`),
    { method: 'POST' }
  )
  if (!response.ok) {
    throw new Error(`Failed to disable session: ${response.statusText}`)
  }
}

export async function testConnection(app: AppConfig): Promise<boolean> {
  try {
    const response = await fetch(buildUrl(app, '/api/sessions/stats'), {
      method: 'GET',
      signal: AbortSignal.timeout(5000),
    })
    return response.ok
  } catch {
    return false
  }
}
