import { useEffect, useRef } from 'react'
import { useAppStore } from '../store/appStore'
import { useSessionStore } from '../store/sessionStore'
import type { AppConfig, WebSocketMessage } from '../types'

const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000, 30000]
const HEARTBEAT_INTERVAL = 30000 // Send ping every 30 seconds

interface ConnectionState {
  ws: WebSocket | null
  reconnectAttempt: number
  reconnectTimeout: ReturnType<typeof setTimeout> | null
  heartbeatInterval: ReturnType<typeof setInterval> | null
}

export function useAppConnections() {
  const apps = useAppStore((state) => state.apps)
  const connectionsRef = useRef<Map<string, ConnectionState>>(new Map())
  const mountedRef = useRef(true)

  const {
    setConnectionStatus,
    initializeSessions,
    updateSessionState,
    addSession,
    removeSession,
    updateStats,
    clearAppData,
  } = useSessionStore()

  useEffect(() => {
    mountedRef.current = true

    const enabledApps = apps.filter((app) => app.enabled)
    const currentAppIds = new Set(enabledApps.map((app) => app.id))

    // Close connections for removed or disabled apps
    connectionsRef.current.forEach((conn, appId) => {
      if (!currentAppIds.has(appId)) {
        if (conn.heartbeatInterval) {
          clearInterval(conn.heartbeatInterval)
        }
        if (conn.reconnectTimeout) {
          clearTimeout(conn.reconnectTimeout)
        }
        if (conn.ws) {
          conn.ws.close()
        }
        connectionsRef.current.delete(appId)
        clearAppData(appId)
      }
    })

    // Create or update connections for enabled apps
    enabledApps.forEach((app) => {
      const existingConn = connectionsRef.current.get(app.id)

      if (!existingConn) {
        connectToApp(app)
      } else if (existingConn.ws && existingConn.ws.readyState === WebSocket.CLOSED) {
        connectToApp(app)
      }
    })

    function connectToApp(app: AppConfig) {
      if (!mountedRef.current) return

      const url = `ws://${app.host}:${app.port}/ws/sessions`
      setConnectionStatus(app.id, 'connecting')

      try {
        const ws = new WebSocket(url)
        const connState: ConnectionState = {
          ws,
          reconnectAttempt: connectionsRef.current.get(app.id)?.reconnectAttempt || 0,
          reconnectTimeout: null,
          heartbeatInterval: null,
        }
        connectionsRef.current.set(app.id, connState)

        ws.onopen = () => {
          if (!mountedRef.current) {
            ws.close()
            return
          }
          connState.reconnectAttempt = 0
          setConnectionStatus(app.id, 'connected')

          // Start heartbeat to keep connection alive
          connState.heartbeatInterval = setInterval(() => {
            if (ws.readyState === WebSocket.OPEN) {
              ws.send('ping')
            }
          }, HEARTBEAT_INTERVAL)
        }

        ws.onmessage = (event: MessageEvent) => {
          handleMessage(app.id, event)
        }

        ws.onerror = () => {
          if (!mountedRef.current) return
          setConnectionStatus(app.id, 'error', 'WebSocket error')
        }

        ws.onclose = () => {
          // Clear heartbeat interval
          if (connState.heartbeatInterval) {
            clearInterval(connState.heartbeatInterval)
            connState.heartbeatInterval = null
          }

          if (!mountedRef.current) return

          const currentApp = apps.find((a) => a.id === app.id)
          if (!currentApp?.enabled) {
            connectionsRef.current.delete(app.id)
            return
          }

          setConnectionStatus(app.id, 'disconnected')
          connState.ws = null

          // Schedule reconnection
          const delay = RECONNECT_DELAYS[
            Math.min(connState.reconnectAttempt, RECONNECT_DELAYS.length - 1)
          ]
          connState.reconnectAttempt++

          connState.reconnectTimeout = setTimeout(() => {
            if (mountedRef.current) {
              const latestApp = apps.find((a) => a.id === app.id)
              if (latestApp?.enabled) {
                connectToApp(latestApp)
              }
            }
          }, delay)
        }
      } catch (error) {
        setConnectionStatus(app.id, 'error', String(error))
      }
    }

    function handleMessage(appId: string, event: MessageEvent) {
      try {
        const message: WebSocketMessage = JSON.parse(event.data)

        switch (message.type) {
          case 'INITIAL_STATE': {
            const payload = message.payload as {
              sessions: Parameters<typeof initializeSessions>[1]
              stats: Parameters<typeof initializeSessions>[2]
            }
            initializeSessions(appId, payload.sessions, payload.stats)
            break
          }
          case 'STATE_CHANGE': {
            const payload = message.payload as Parameters<typeof updateSessionState>[1]
            updateSessionState(appId, payload)
            break
          }
          case 'SESSION_REGISTERED': {
            const payload = message.payload as { session: Parameters<typeof addSession>[1] }
            addSession(appId, payload.session)
            break
          }
          case 'SESSION_UNREGISTERED': {
            const payload = message.payload as { sessionId: string }
            removeSession(appId, payload.sessionId)
            break
          }
          case 'STATS_UPDATE': {
            const payload = message.payload as { stats: Parameters<typeof updateStats>[1] }
            updateStats(appId, payload.stats)
            break
          }
        }
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    }

    return () => {
      mountedRef.current = false
      connectionsRef.current.forEach((conn) => {
        if (conn.heartbeatInterval) {
          clearInterval(conn.heartbeatInterval)
        }
        if (conn.reconnectTimeout) {
          clearTimeout(conn.reconnectTimeout)
        }
        if (conn.ws) {
          conn.ws.close()
        }
      })
      connectionsRef.current.clear()
    }
  }, [
    apps,
    setConnectionStatus,
    initializeSessions,
    updateSessionState,
    addSession,
    removeSession,
    updateStats,
    clearAppData,
  ])
}
