import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import { useAppStore } from '../store/appStore'
import { useSessionStore, type SessionWithApp } from '../store/sessionStore'

export function DisconnectedSessionsWidget() {
  const apps = useAppStore((state) => state.apps)
  const getAllDisconnectedSessions = useSessionStore(
    (state) => state.getAllDisconnectedSessions
  )

  const appNames = useMemo(() => {
    const map = new Map<string, string>()
    for (const app of apps) {
      map.set(app.id, app.name)
    }
    return map
  }, [apps])

  const disconnectedSessions = getAllDisconnectedSessions(appNames)

  // Group by app for better display
  const sessionsByApp = useMemo(() => {
    const grouped = new Map<string, SessionWithApp[]>()
    for (const session of disconnectedSessions) {
      const existing = grouped.get(session.appId) || []
      existing.push(session)
      grouped.set(session.appId, existing)
    }
    return grouped
  }, [disconnectedSessions])

  if (disconnectedSessions.length === 0) {
    return (
      <div className="bg-gray-800 rounded-lg border border-gray-700 p-4">
        <div className="flex items-center gap-2 mb-3">
          <div className="w-3 h-3 rounded-full bg-green-500"></div>
          <h3 className="text-sm font-semibold text-gray-300">
            Disconnected Sessions
          </h3>
        </div>
        <p className="text-sm text-gray-500">All sessions are connected</p>
      </div>
    )
  }

  return (
    <div className="bg-gray-800 rounded-lg border border-red-900/50 p-4">
      <div className="flex items-center gap-2 mb-3">
        <div className="w-3 h-3 rounded-full bg-red-500 animate-pulse"></div>
        <h3 className="text-sm font-semibold text-gray-300">
          Disconnected Sessions
        </h3>
        <span className="ml-auto px-2 py-0.5 bg-red-900/50 text-red-300 rounded text-xs font-medium">
          {disconnectedSessions.length}
        </span>
      </div>

      <div className="space-y-3 max-h-64 overflow-y-auto">
        {Array.from(sessionsByApp.entries()).map(([appId, sessions]) => (
          <div key={appId} className="border-t border-gray-700 pt-2 first:border-t-0 first:pt-0">
            <Link
              to={`/app/${appId}`}
              className="text-xs text-blue-400 hover:text-blue-300 font-medium"
            >
              {sessions[0].appName}
            </Link>
            <div className="mt-1 space-y-1">
              {sessions.map((session) => (
                <div
                  key={session.sessionId}
                  className="flex items-center justify-between text-xs"
                >
                  <span className="text-gray-300 truncate max-w-[180px]" title={session.sessionName}>
                    {session.sessionName}
                  </span>
                  <div className="flex items-center gap-2">
                    <span className="text-gray-500">{session.protocolType}</span>
                    <span className={`px-1.5 py-0.5 rounded text-xs ${
                      session.state === 'DISCONNECTED'
                        ? 'bg-red-900/50 text-red-300'
                        : session.state === 'RECONNECTING'
                        ? 'bg-yellow-900/50 text-yellow-300'
                        : session.state === 'ERROR'
                        ? 'bg-red-900/50 text-red-300'
                        : 'bg-gray-700 text-gray-400'
                    }`}>
                      {session.state}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
