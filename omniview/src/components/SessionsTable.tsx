import { useState } from 'react'
import type { Session, AppConfig, ProtocolType } from '../types'
import { SessionStatusIndicator } from './StatusIndicator'
import { enableSession, disableSession } from '../api/sessions'

interface SessionsTableProps {
  sessions: Session[]
  app: AppConfig
  onRefresh?: () => void
}

export function SessionsTable({ sessions, app, onRefresh }: SessionsTableProps) {
  const [protocolFilter, setProtocolFilter] = useState<ProtocolType | 'ALL'>('ALL')
  const [actionInProgress, setActionInProgress] = useState<string | null>(null)

  const filteredSessions = sessions.filter((session) =>
    protocolFilter === 'ALL' ? true : session.protocolType === protocolFilter
  )

  const handleToggleSession = async (session: Session) => {
    setActionInProgress(session.sessionId)
    try {
      if (session.enabled) {
        await disableSession(app, session.sessionId)
      } else {
        await enableSession(app, session.sessionId)
      }
      onRefresh?.()
    } catch (error) {
      console.error('Failed to toggle session:', error)
    } finally {
      setActionInProgress(null)
    }
  }

  const protocolCounts = {
    ALL: sessions.length,
    FIX: sessions.filter((s) => s.protocolType === 'FIX').length,
    OUCH: sessions.filter((s) => s.protocolType === 'OUCH').length,
  }

  return (
    <div>
      <div className="mb-4 flex gap-2">
        {(['ALL', 'FIX', 'OUCH'] as const).map((filter) => (
          <button
            key={filter}
            onClick={() => setProtocolFilter(filter)}
            className={`px-3 py-1 text-sm rounded-md transition-colors ${
              protocolFilter === filter
                ? 'bg-blue-600 text-white'
                : 'bg-gray-700 text-gray-300 hover:bg-gray-600'
            }`}
          >
            {filter} ({protocolCounts[filter]})
          </button>
        ))}
      </div>

      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-gray-700">
              <th className="pb-3 pr-4 font-medium">Status</th>
              <th className="pb-3 pr-4 font-medium">Session ID</th>
              <th className="pb-3 pr-4 font-medium">Name</th>
              <th className="pb-3 pr-4 font-medium">Protocol</th>
              <th className="pb-3 pr-4 font-medium">State</th>
              <th className="pb-3 pr-4 font-medium">Connected</th>
              <th className="pb-3 pr-4 font-medium">Logged On</th>
              <th className="pb-3 pr-4 font-medium">Enabled</th>
              <th className="pb-3 font-medium">Actions</th>
            </tr>
          </thead>
          <tbody>
            {filteredSessions.length === 0 ? (
              <tr>
                <td colSpan={9} className="py-8 text-center text-gray-400">
                  No sessions found
                </td>
              </tr>
            ) : (
              filteredSessions.map((session) => (
                <tr
                  key={session.sessionId}
                  className="border-b border-gray-800 hover:bg-gray-800/50"
                >
                  <td className="py-3 pr-4">
                    <SessionStatusIndicator
                      connected={session.connected}
                      loggedOn={session.loggedOn}
                      enabled={session.enabled}
                    />
                  </td>
                  <td className="py-3 pr-4 font-mono text-xs text-gray-300">
                    {session.sessionId}
                  </td>
                  <td className="py-3 pr-4 text-white">{session.sessionName}</td>
                  <td className="py-3 pr-4">
                    <span
                      className={`px-2 py-0.5 rounded text-xs font-medium ${
                        session.protocolType === 'FIX'
                          ? 'bg-blue-900 text-blue-300'
                          : 'bg-purple-900 text-purple-300'
                      }`}
                    >
                      {session.protocolType}
                    </span>
                  </td>
                  <td className="py-3 pr-4 text-gray-300">{session.state}</td>
                  <td className="py-3 pr-4">
                    <span
                      className={
                        session.connected ? 'text-green-400' : 'text-gray-500'
                      }
                    >
                      {session.connected ? 'Yes' : 'No'}
                    </span>
                  </td>
                  <td className="py-3 pr-4">
                    <span
                      className={
                        session.loggedOn ? 'text-green-400' : 'text-gray-500'
                      }
                    >
                      {session.loggedOn ? 'Yes' : 'No'}
                    </span>
                  </td>
                  <td className="py-3 pr-4">
                    <span
                      className={
                        session.enabled ? 'text-green-400' : 'text-gray-500'
                      }
                    >
                      {session.enabled ? 'Yes' : 'No'}
                    </span>
                  </td>
                  <td className="py-3">
                    <button
                      onClick={() => handleToggleSession(session)}
                      disabled={actionInProgress === session.sessionId}
                      className={`px-2 py-1 text-xs rounded transition-colors ${
                        session.enabled
                          ? 'bg-red-900 hover:bg-red-800 text-red-300'
                          : 'bg-green-900 hover:bg-green-800 text-green-300'
                      } disabled:opacity-50`}
                    >
                      {actionInProgress === session.sessionId
                        ? '...'
                        : session.enabled
                        ? 'Disable'
                        : 'Enable'}
                    </button>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}
