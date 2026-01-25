import { useParams, Link, useNavigate } from 'react-router-dom'
import { useAppStore } from '../store/appStore'
import { useSessionStore } from '../store/sessionStore'
import { SessionsTable } from '../components/SessionsTable'
import { ConnectionStatus } from '../components/ConnectionStatus'

export function AppDetail() {
  const { appId } = useParams<{ appId: string }>()
  const navigate = useNavigate()
  const app = useAppStore((state) => state.getApp(appId || ''))
  const { getSessions, getStats, getConnectionStatus, getLastError } =
    useSessionStore()

  if (!app) {
    return (
      <div className="text-center py-12">
        <p className="text-gray-400 mb-4">Application not found</p>
        <Link
          to="/"
          className="text-blue-400 hover:text-blue-300"
        >
          Back to Dashboard
        </Link>
      </div>
    )
  }

  const sessions = getSessions(app.id)
  const stats = getStats(app.id)
  const connectionStatus = getConnectionStatus(app.id)
  const lastError = getLastError(app.id)

  return (
    <div>
      <div className="mb-6">
        <button
          onClick={() => navigate('/')}
          className="text-sm text-gray-400 hover:text-white mb-2 flex items-center gap-1"
        >
          <span>&larr;</span> Back to Dashboard
        </button>
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-white">{app.name}</h1>
            <p className="text-sm text-gray-400">
              {app.host}:{app.port}
            </p>
          </div>
          <ConnectionStatus status={connectionStatus} error={lastError} />
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-8">
        <StatCard label="Total Sessions" value={stats?.total || 0} />
        <StatCard
          label="Logged On"
          value={stats?.loggedOn || 0}
          color="green"
        />
        <StatCard
          label="Connected"
          value={stats?.connected || 0}
          color="yellow"
        />
        <StatCard
          label="Enabled"
          value={stats?.enabled || 0}
          color="blue"
        />
      </div>

      <div className="bg-gray-800 rounded-lg p-6 border border-gray-700">
        <h2 className="text-lg font-semibold text-white mb-4">Sessions</h2>
        {connectionStatus === 'connected' ? (
          <SessionsTable sessions={sessions} app={app} />
        ) : (
          <div className="text-center py-8 text-gray-400">
            {connectionStatus === 'connecting'
              ? 'Connecting to application...'
              : 'Not connected to application'}
          </div>
        )}
      </div>
    </div>
  )
}

interface StatCardProps {
  label: string
  value: number
  color?: 'green' | 'yellow' | 'blue' | 'default'
}

function StatCard({ label, value, color = 'default' }: StatCardProps) {
  const colorClasses = {
    green: 'text-green-400',
    yellow: 'text-yellow-400',
    blue: 'text-blue-400',
    default: 'text-white',
  }

  return (
    <div className="bg-gray-800 rounded-lg p-4 border border-gray-700">
      <p className={`text-3xl font-bold ${colorClasses[color]}`}>{value}</p>
      <p className="text-sm text-gray-400">{label}</p>
    </div>
  )
}
