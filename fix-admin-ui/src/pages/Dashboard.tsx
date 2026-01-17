import { useQuery } from '@tanstack/react-query'
import { fetchSessions } from '../api/sessions'
import SessionCard from '../components/SessionCard'

export default function Dashboard() {
  const { data: sessions, isLoading, error } = useQuery({
    queryKey: ['sessions'],
    queryFn: fetchSessions,
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  if (error) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-4">
        <h3 className="text-red-800 font-medium">Error loading sessions</h3>
        <p className="text-red-600 text-sm mt-1">
          {error instanceof Error ? error.message : 'Unknown error'}
        </p>
      </div>
    )
  }

  const loggedOnCount = sessions?.filter(s => s.loggedOn).length ?? 0
  const connectedCount = sessions?.filter(s => s.connected).length ?? 0
  const totalCount = sessions?.length ?? 0

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-gray-900">FIX Sessions</h1>
        <p className="text-gray-600 mt-1">Monitor and manage FIX protocol sessions</p>
      </div>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-8">
        <div className="bg-white rounded-lg shadow p-4">
          <div className="text-sm text-gray-500">Total Sessions</div>
          <div className="text-3xl font-bold text-gray-900">{totalCount}</div>
        </div>
        <div className="bg-white rounded-lg shadow p-4">
          <div className="text-sm text-gray-500">Connected</div>
          <div className="text-3xl font-bold text-yellow-600">{connectedCount}</div>
        </div>
        <div className="bg-white rounded-lg shadow p-4">
          <div className="text-sm text-gray-500">Logged On</div>
          <div className="text-3xl font-bold text-green-600">{loggedOnCount}</div>
        </div>
      </div>

      {/* Session Grid */}
      {sessions && sessions.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {sessions.map(session => (
            <SessionCard key={session.sessionId} session={session} />
          ))}
        </div>
      ) : (
        <div className="bg-white rounded-lg shadow p-8 text-center">
          <p className="text-gray-500">No sessions configured</p>
        </div>
      )}
    </div>
  )
}
