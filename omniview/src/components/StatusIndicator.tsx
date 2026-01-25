import type { ConnectionStatus } from '../types'

interface StatusIndicatorProps {
  status: ConnectionStatus
  size?: 'sm' | 'md' | 'lg'
  showLabel?: boolean
}

const statusColors: Record<ConnectionStatus, string> = {
  connected: 'bg-green-500',
  connecting: 'bg-yellow-500 animate-pulse',
  disconnected: 'bg-gray-500',
  error: 'bg-red-500',
}

const statusLabels: Record<ConnectionStatus, string> = {
  connected: 'Connected',
  connecting: 'Connecting...',
  disconnected: 'Disconnected',
  error: 'Error',
}

const sizeClasses = {
  sm: 'w-2 h-2',
  md: 'w-3 h-3',
  lg: 'w-4 h-4',
}

export function StatusIndicator({
  status,
  size = 'md',
  showLabel = false,
}: StatusIndicatorProps) {
  return (
    <div className="flex items-center gap-2">
      <div
        className={`rounded-full ${statusColors[status]} ${sizeClasses[size]}`}
        title={statusLabels[status]}
      />
      {showLabel && (
        <span className="text-sm text-gray-400">{statusLabels[status]}</span>
      )}
    </div>
  )
}

interface SessionStatusIndicatorProps {
  connected: boolean
  loggedOn: boolean
  enabled: boolean
  size?: 'sm' | 'md' | 'lg'
}

export function SessionStatusIndicator({
  connected,
  loggedOn,
  enabled,
  size = 'md',
}: SessionStatusIndicatorProps) {
  let color: string
  let title: string

  if (!enabled) {
    color = 'bg-gray-500'
    title = 'Disabled'
  } else if (loggedOn) {
    color = 'bg-green-500'
    title = 'Logged On'
  } else if (connected) {
    color = 'bg-yellow-500'
    title = 'Connected (not logged on)'
  } else {
    color = 'bg-red-500'
    title = 'Disconnected'
  }

  return (
    <div
      className={`rounded-full ${color} ${sizeClasses[size]}`}
      title={title}
    />
  )
}

interface AppHealthIndicatorProps {
  total: number
  connected: number
  loggedOn: number
  connectionStatus: ConnectionStatus
  size?: 'sm' | 'md' | 'lg'
}

export function AppHealthIndicator({
  total,
  connected,
  loggedOn,
  connectionStatus,
  size = 'md',
}: AppHealthIndicatorProps) {
  let color: string
  let title: string

  if (connectionStatus !== 'connected') {
    color = 'bg-gray-500'
    title = 'Not connected to app'
  } else if (total === 0) {
    color = 'bg-gray-500'
    title = 'No sessions'
  } else if (loggedOn === total) {
    color = 'bg-green-500'
    title = 'All sessions logged on'
  } else if (connected > 0 || loggedOn > 0) {
    color = 'bg-yellow-500'
    title = `${loggedOn}/${total} sessions logged on`
  } else {
    color = 'bg-red-500'
    title = 'All sessions disconnected'
  }

  return (
    <div
      className={`rounded-full ${color} ${sizeClasses[size]}`}
      title={title}
    />
  )
}
