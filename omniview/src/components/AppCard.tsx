import { Link } from 'react-router-dom'
import type { AppConfig, SessionStats, ConnectionStatus } from '../types'
import { AppHealthIndicator, StatusIndicator } from './StatusIndicator'

interface AppCardProps {
  app: AppConfig
  stats: SessionStats | undefined
  connectionStatus: ConnectionStatus
}

export function AppCard({ app, stats, connectionStatus }: AppCardProps) {
  const total = stats?.total || 0
  const connected = stats?.connected || 0
  const loggedOn = stats?.loggedOn || 0

  return (
    <Link
      to={`/app/${app.id}`}
      className="block bg-gray-800 rounded-lg p-6 hover:bg-gray-750 transition-colors border border-gray-700 hover:border-gray-600"
    >
      <div className="flex items-start justify-between">
        <div className="flex items-center gap-3">
          <AppHealthIndicator
            total={total}
            connected={connected}
            loggedOn={loggedOn}
            connectionStatus={connectionStatus}
            size="lg"
          />
          <div>
            <h3 className="text-lg font-semibold text-white">{app.name}</h3>
            <p className="text-sm text-gray-400">
              {app.host}:{app.port}
            </p>
          </div>
        </div>
        <StatusIndicator status={connectionStatus} size="sm" />
      </div>

      <div className="mt-4 grid grid-cols-3 gap-4">
        <div>
          <p className="text-2xl font-bold text-white">{total}</p>
          <p className="text-xs text-gray-400">Total Sessions</p>
        </div>
        <div>
          <p className="text-2xl font-bold text-green-400">{loggedOn}</p>
          <p className="text-xs text-gray-400">Logged On</p>
        </div>
        <div>
          <p className="text-2xl font-bold text-yellow-400">{connected - loggedOn}</p>
          <p className="text-xs text-gray-400">Connecting</p>
        </div>
      </div>

      {stats && (stats.byProtocol.FIX.total > 0 || stats.byProtocol.OUCH.total > 0) && (
        <div className="mt-4 pt-4 border-t border-gray-700">
          <div className="flex gap-4 text-sm">
            {stats.byProtocol.FIX.total > 0 && (
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 bg-blue-900 text-blue-300 rounded text-xs font-medium">
                  FIX
                </span>
                <span className="text-gray-400">
                  {stats.byProtocol.FIX.loggedOn}/{stats.byProtocol.FIX.total}
                </span>
              </div>
            )}
            {stats.byProtocol.OUCH.total > 0 && (
              <div className="flex items-center gap-2">
                <span className="px-2 py-0.5 bg-purple-900 text-purple-300 rounded text-xs font-medium">
                  OUCH
                </span>
                <span className="text-gray-400">
                  {stats.byProtocol.OUCH.loggedOn}/{stats.byProtocol.OUCH.total}
                </span>
              </div>
            )}
          </div>
        </div>
      )}
    </Link>
  )
}
