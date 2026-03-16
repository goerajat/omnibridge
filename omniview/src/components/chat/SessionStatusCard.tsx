interface SessionStatusCardProps {
  data: Record<string, unknown>
}

export function SessionStatusCard({ data }: SessionStatusCardProps) {
  const sessions = (data.sessions as Record<string, unknown>[]) || []
  const singleSession = data.session as Record<string, unknown>

  const items = singleSession ? [singleSession] : sessions

  if (!items.length) {
    return (
      <pre className="text-xs bg-gray-900 rounded p-2 text-gray-300 overflow-x-auto max-h-48">
        {JSON.stringify(data, null, 2)}
      </pre>
    )
  }

  return (
    <div className="space-y-2 max-h-64 overflow-y-auto">
      {items.map((session, i) => (
        <div key={i} className="bg-gray-900 rounded p-2 text-xs">
          <div className="flex items-center gap-2 mb-1">
            <StatusDot status={session.status as string} />
            <span className="text-gray-200 font-medium">
              {(session.stream as string) || `Session ${i + 1}`}
            </span>
            <span className="text-gray-500">
              {session.status as string}
            </span>
          </div>

          <div className="grid grid-cols-2 gap-x-4 gap-y-1 text-gray-400 ml-4">
            {session.total_messages != null && (
              <div>Messages: <span className="text-gray-300">{String(session.total_messages)}</span></div>
            )}
            {session.inbound_count != null && (
              <div>Inbound: <span className="text-gray-300">{String(session.inbound_count)}</span></div>
            )}
            {session.outbound_count != null && (
              <div>Outbound: <span className="text-gray-300">{String(session.outbound_count)}</span></div>
            )}
            {session.duration != null && (
              <div>Duration: <span className="text-gray-300">{String(session.duration)}</span></div>
            )}
            {Array.isArray(session.gaps) && (session.gaps as unknown[]).length > 0 && (
              <div className="col-span-2 text-yellow-400">
                Gaps: {(session.gaps as unknown[]).length} sequence gap(s) detected
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}

function StatusDot({ status }: { status: string }) {
  const color =
    status === 'ACTIVE' ? 'bg-green-400' :
    status === 'DISCONNECTED' ? 'bg-red-400' :
    'bg-yellow-400'

  return <span className={`inline-block w-2 h-2 rounded-full ${color}`} />
}
