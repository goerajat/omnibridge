import type { ConnectionStatus as ConnectionStatusType } from '../types'
import { StatusIndicator } from './StatusIndicator'

interface ConnectionStatusProps {
  status: ConnectionStatusType
  error?: string
}

export function ConnectionStatus({ status, error }: ConnectionStatusProps) {
  return (
    <div className="flex items-center gap-2">
      <StatusIndicator status={status} showLabel />
      {error && status === 'error' && (
        <span className="text-xs text-red-400">{error}</span>
      )}
    </div>
  )
}
