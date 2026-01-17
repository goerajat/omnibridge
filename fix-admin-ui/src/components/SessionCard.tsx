import { Link } from 'react-router-dom'
import { Session } from '../api/sessions'

interface SessionCardProps {
  session: Session
}

export default function SessionCard({ session }: SessionCardProps) {
  const getStatusColor = () => {
    if (session.loggedOn) return 'bg-green-500'
    if (session.connected) return 'bg-yellow-500'
    return 'bg-red-500'
  }

  const getStatusText = () => {
    if (session.loggedOn) return 'Logged On'
    if (session.connected) return 'Connected'
    return 'Disconnected'
  }

  return (
    <Link
      to={`/session/${encodeURIComponent(session.sessionId)}`}
      className="block bg-white rounded-lg shadow hover:shadow-md transition-shadow"
    >
      <div className="p-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-gray-900">
            {session.sessionName}
          </h3>
          <div className="flex items-center">
            <span className={`w-3 h-3 rounded-full ${getStatusColor()} mr-2`}></span>
            <span className="text-sm text-gray-600">{getStatusText()}</span>
          </div>
        </div>

        <div className="space-y-2 text-sm">
          <div className="flex justify-between">
            <span className="text-gray-500">Session ID:</span>
            <span className="text-gray-900 font-mono">{session.sessionId}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">Role:</span>
            <span className={`px-2 py-0.5 rounded text-xs font-medium ${
              session.role === 'INITIATOR'
                ? 'bg-blue-100 text-blue-800'
                : 'bg-purple-100 text-purple-800'
            }`}>
              {session.role}
            </span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">State:</span>
            <span className="text-gray-900">{session.state}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">Seq (Out/In):</span>
            <span className="text-gray-900 font-mono">
              {session.outgoingSeqNum} / {session.expectedIncomingSeqNum}
            </span>
          </div>
          {session.host && (
            <div className="flex justify-between">
              <span className="text-gray-500">Endpoint:</span>
              <span className="text-gray-900 font-mono">{session.host}:{session.port}</span>
            </div>
          )}
        </div>
      </div>
    </Link>
  )
}
