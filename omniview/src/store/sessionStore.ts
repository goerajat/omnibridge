import { create } from 'zustand'
import type {
  Session,
  SessionStats,
  ConnectionStatus,
  StateChangePayload,
} from '../types'

interface SessionState {
  // Sessions by app ID
  sessionsByApp: Map<string, Session[]>

  // Stats by app ID
  statsByApp: Map<string, SessionStats>

  // Connection status by app ID
  connectionStatus: Map<string, ConnectionStatus>

  // Last error by app ID
  lastError: Map<string, string>

  // Actions
  setConnectionStatus: (appId: string, status: ConnectionStatus, error?: string) => void
  initializeSessions: (appId: string, sessions: Session[], stats: SessionStats) => void
  updateSessionState: (appId: string, payload: StateChangePayload) => void
  addSession: (appId: string, session: Session) => void
  removeSession: (appId: string, sessionId: string) => void
  updateStats: (appId: string, stats: SessionStats) => void
  clearAppData: (appId: string) => void

  // Selectors
  getSessions: (appId: string) => Session[]
  getStats: (appId: string) => SessionStats | undefined
  getConnectionStatus: (appId: string) => ConnectionStatus
  getLastError: (appId: string) => string | undefined
}

const defaultStats: SessionStats = {
  total: 0,
  connected: 0,
  loggedOn: 0,
  enabled: 0,
  byProtocol: {
    FIX: { total: 0, connected: 0, loggedOn: 0 },
    OUCH: { total: 0, connected: 0, loggedOn: 0 },
  },
}

function computeStats(sessions: Session[]): SessionStats {
  const stats: SessionStats = {
    total: sessions.length,
    connected: 0,
    loggedOn: 0,
    enabled: 0,
    byProtocol: {
      FIX: { total: 0, connected: 0, loggedOn: 0 },
      OUCH: { total: 0, connected: 0, loggedOn: 0 },
    },
  }

  for (const session of sessions) {
    if (session.connected) stats.connected++
    if (session.loggedOn) stats.loggedOn++
    if (session.enabled) stats.enabled++

    const protocol = session.protocolType
    if (protocol === 'FIX' || protocol === 'OUCH') {
      stats.byProtocol[protocol].total++
      if (session.connected) stats.byProtocol[protocol].connected++
      if (session.loggedOn) stats.byProtocol[protocol].loggedOn++
    }
  }

  return stats
}

export const useSessionStore = create<SessionState>((set, get) => ({
  sessionsByApp: new Map(),
  statsByApp: new Map(),
  connectionStatus: new Map(),
  lastError: new Map(),

  setConnectionStatus: (appId, status, error) => {
    set((state) => {
      const newConnectionStatus = new Map(state.connectionStatus)
      newConnectionStatus.set(appId, status)

      const newLastError = new Map(state.lastError)
      if (error) {
        newLastError.set(appId, error)
      } else {
        newLastError.delete(appId)
      }

      return { connectionStatus: newConnectionStatus, lastError: newLastError }
    })
  },

  initializeSessions: (appId, sessions, stats) => {
    set((state) => {
      const newSessionsByApp = new Map(state.sessionsByApp)
      const newStatsByApp = new Map(state.statsByApp)

      newSessionsByApp.set(appId, sessions)
      newStatsByApp.set(appId, stats)

      return { sessionsByApp: newSessionsByApp, statsByApp: newStatsByApp }
    })
  },

  updateSessionState: (appId, payload) => {
    set((state) => {
      const sessions = state.sessionsByApp.get(appId)
      if (!sessions) return state

      const newSessions = sessions.map((session) =>
        session.sessionId === payload.sessionId
          ? {
              ...session,
              state: payload.state,
              connected: payload.connected,
              loggedOn: payload.loggedOn,
            }
          : session
      )

      const newSessionsByApp = new Map(state.sessionsByApp)
      newSessionsByApp.set(appId, newSessions)

      const newStatsByApp = new Map(state.statsByApp)
      newStatsByApp.set(appId, computeStats(newSessions))

      return { sessionsByApp: newSessionsByApp, statsByApp: newStatsByApp }
    })
  },

  addSession: (appId, session) => {
    set((state) => {
      const sessions = state.sessionsByApp.get(appId) || []
      const newSessions = [...sessions, session]

      const newSessionsByApp = new Map(state.sessionsByApp)
      newSessionsByApp.set(appId, newSessions)

      const newStatsByApp = new Map(state.statsByApp)
      newStatsByApp.set(appId, computeStats(newSessions))

      return { sessionsByApp: newSessionsByApp, statsByApp: newStatsByApp }
    })
  },

  removeSession: (appId, sessionId) => {
    set((state) => {
      const sessions = state.sessionsByApp.get(appId)
      if (!sessions) return state

      const newSessions = sessions.filter((s) => s.sessionId !== sessionId)

      const newSessionsByApp = new Map(state.sessionsByApp)
      newSessionsByApp.set(appId, newSessions)

      const newStatsByApp = new Map(state.statsByApp)
      newStatsByApp.set(appId, computeStats(newSessions))

      return { sessionsByApp: newSessionsByApp, statsByApp: newStatsByApp }
    })
  },

  updateStats: (appId, stats) => {
    set((state) => {
      const newStatsByApp = new Map(state.statsByApp)
      newStatsByApp.set(appId, stats)
      return { statsByApp: newStatsByApp }
    })
  },

  clearAppData: (appId) => {
    set((state) => {
      const newSessionsByApp = new Map(state.sessionsByApp)
      const newStatsByApp = new Map(state.statsByApp)
      const newConnectionStatus = new Map(state.connectionStatus)
      const newLastError = new Map(state.lastError)

      newSessionsByApp.delete(appId)
      newStatsByApp.delete(appId)
      newConnectionStatus.delete(appId)
      newLastError.delete(appId)

      return {
        sessionsByApp: newSessionsByApp,
        statsByApp: newStatsByApp,
        connectionStatus: newConnectionStatus,
        lastError: newLastError,
      }
    })
  },

  getSessions: (appId) => get().sessionsByApp.get(appId) || [],

  getStats: (appId) => get().statsByApp.get(appId) || defaultStats,

  getConnectionStatus: (appId) =>
    get().connectionStatus.get(appId) || 'disconnected',

  getLastError: (appId) => get().lastError.get(appId),
}))
