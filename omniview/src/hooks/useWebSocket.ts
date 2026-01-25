import { useEffect, useRef, useCallback } from 'react'
import type { AppConfig, WebSocketMessage } from '../types'
import { useSessionStore } from '../store/sessionStore'

interface UseWebSocketOptions {
  app: AppConfig
  onMessage?: (message: WebSocketMessage) => void
  enabled?: boolean
}

const RECONNECT_DELAYS = [1000, 2000, 4000, 8000, 16000, 30000]

export function useWebSocket({ app, onMessage, enabled = true }: UseWebSocketOptions) {
  const wsRef = useRef<WebSocket | null>(null)
  const reconnectAttemptRef = useRef(0)
  const reconnectTimeoutRef = useRef<ReturnType<typeof setTimeout>>()
  const mountedRef = useRef(true)

  const {
    setConnectionStatus,
    initializeSessions,
    updateSessionState,
    addSession,
    removeSession,
    updateStats,
  } = useSessionStore()

  const handleMessage = useCallback(
    (event: MessageEvent) => {
      try {
        const message: WebSocketMessage = JSON.parse(event.data)

        switch (message.type) {
          case 'INITIAL_STATE': {
            const payload = message.payload as {
              sessions: Parameters<typeof initializeSessions>[1]
              stats: Parameters<typeof initializeSessions>[2]
            }
            initializeSessions(app.id, payload.sessions, payload.stats)
            break
          }
          case 'STATE_CHANGE': {
            const payload = message.payload as Parameters<typeof updateSessionState>[1]
            updateSessionState(app.id, payload)
            break
          }
          case 'SESSION_REGISTERED': {
            const payload = message.payload as { session: Parameters<typeof addSession>[1] }
            addSession(app.id, payload.session)
            break
          }
          case 'SESSION_UNREGISTERED': {
            const payload = message.payload as { sessionId: string }
            removeSession(app.id, payload.sessionId)
            break
          }
          case 'STATS_UPDATE': {
            const payload = message.payload as { stats: Parameters<typeof updateStats>[1] }
            updateStats(app.id, payload.stats)
            break
          }
        }

        onMessage?.(message)
      } catch (error) {
        console.error('Failed to parse WebSocket message:', error)
      }
    },
    [app.id, initializeSessions, updateSessionState, addSession, removeSession, updateStats, onMessage]
  )

  const connect = useCallback(() => {
    if (!mountedRef.current || !enabled) return

    const url = `ws://${app.host}:${app.port}/ws/sessions`

    setConnectionStatus(app.id, 'connecting')

    try {
      const ws = new WebSocket(url)
      wsRef.current = ws

      ws.onopen = () => {
        if (!mountedRef.current) {
          ws.close()
          return
        }
        reconnectAttemptRef.current = 0
        setConnectionStatus(app.id, 'connected')
      }

      ws.onmessage = handleMessage

      ws.onerror = () => {
        if (!mountedRef.current) return
        setConnectionStatus(app.id, 'error', 'WebSocket error')
      }

      ws.onclose = () => {
        if (!mountedRef.current) return

        setConnectionStatus(app.id, 'disconnected')
        wsRef.current = null

        // Schedule reconnection
        if (enabled) {
          const delay = RECONNECT_DELAYS[
            Math.min(reconnectAttemptRef.current, RECONNECT_DELAYS.length - 1)
          ]
          reconnectAttemptRef.current++

          reconnectTimeoutRef.current = setTimeout(() => {
            if (mountedRef.current && enabled) {
              connect()
            }
          }, delay)
        }
      }
    } catch (error) {
      setConnectionStatus(app.id, 'error', String(error))
    }
  }, [app.id, app.host, app.port, enabled, handleMessage, setConnectionStatus])

  const disconnect = useCallback(() => {
    if (reconnectTimeoutRef.current) {
      clearTimeout(reconnectTimeoutRef.current)
      reconnectTimeoutRef.current = undefined
    }
    if (wsRef.current) {
      wsRef.current.close()
      wsRef.current = null
    }
  }, [])

  useEffect(() => {
    mountedRef.current = true

    if (enabled) {
      connect()
    }

    return () => {
      mountedRef.current = false
      disconnect()
    }
  }, [connect, disconnect, enabled])

  // Reconnect when app config changes
  useEffect(() => {
    if (enabled && wsRef.current) {
      disconnect()
      reconnectAttemptRef.current = 0
      connect()
    }
  }, [app.host, app.port, connect, disconnect, enabled])

  return {
    disconnect,
    reconnect: () => {
      disconnect()
      reconnectAttemptRef.current = 0
      connect()
    },
  }
}
