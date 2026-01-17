import { useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  fetchSession,
  connectSession,
  disconnectSession,
  logoutSession,
  resetSequence,
  setOutgoingSeq,
  setIncomingSeq,
  sendTestRequest,
  triggerEod,
} from '../api/sessions'

export default function SessionDetail() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const queryClient = useQueryClient()
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [seqNumInput, setSeqNumInput] = useState({ outgoing: '', incoming: '' })

  const { data: session, isLoading, error } = useQuery({
    queryKey: ['session', sessionId],
    queryFn: () => fetchSession(sessionId!),
    enabled: !!sessionId,
  })

  const showMessage = (type: 'success' | 'error', text: string) => {
    setMessage({ type, text })
    setTimeout(() => setMessage(null), 3000)
  }

  const createMutation = (action: (id: string) => Promise<unknown>) => {
    return useMutation({
      mutationFn: () => action(sessionId!),
      onSuccess: () => {
        queryClient.invalidateQueries({ queryKey: ['session', sessionId] })
        queryClient.invalidateQueries({ queryKey: ['sessions'] })
        showMessage('success', 'Action completed successfully')
      },
      onError: (err) => {
        showMessage('error', err instanceof Error ? err.message : 'Action failed')
      },
    })
  }

  const connectMutation = createMutation(connectSession)
  const disconnectMutation = createMutation(disconnectSession)
  const logoutMutation = createMutation((id) => logoutSession(id, 'Admin logout'))
  const resetSeqMutation = createMutation(resetSequence)
  const testRequestMutation = createMutation(sendTestRequest)
  const eodMutation = createMutation(triggerEod)

  const setOutSeqMutation = useMutation({
    mutationFn: () => setOutgoingSeq(sessionId!, parseInt(seqNumInput.outgoing)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['session', sessionId] })
      showMessage('success', 'Outgoing sequence number updated')
      setSeqNumInput(prev => ({ ...prev, outgoing: '' }))
    },
    onError: (err) => showMessage('error', err instanceof Error ? err.message : 'Failed'),
  })

  const setInSeqMutation = useMutation({
    mutationFn: () => setIncomingSeq(sessionId!, parseInt(seqNumInput.incoming)),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['session', sessionId] })
      showMessage('success', 'Incoming sequence number updated')
      setSeqNumInput(prev => ({ ...prev, incoming: '' }))
    },
    onError: (err) => showMessage('error', err instanceof Error ? err.message : 'Failed'),
  })

  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="animate-spin rounded-full h-12 w-12 border-b-2 border-blue-600"></div>
      </div>
    )
  }

  if (error || !session) {
    return (
      <div className="bg-red-50 border border-red-200 rounded-lg p-4">
        <h3 className="text-red-800 font-medium">Session not found</h3>
        <Link to="/" className="text-blue-600 hover:underline mt-2 inline-block">
          Back to Dashboard
        </Link>
      </div>
    )
  }

  const getStatusColor = () => {
    if (session.loggedOn) return 'bg-green-500'
    if (session.connected) return 'bg-yellow-500'
    return 'bg-red-500'
  }

  return (
    <div>
      <div className="mb-6">
        <Link to="/" className="text-blue-600 hover:underline text-sm">
          &larr; Back to Dashboard
        </Link>
      </div>

      {message && (
        <div className={`mb-4 p-4 rounded-lg ${
          message.type === 'success' ? 'bg-green-50 text-green-800' : 'bg-red-50 text-red-800'
        }`}>
          {message.text}
        </div>
      )}

      <div className="bg-white rounded-lg shadow">
        <div className="p-6 border-b">
          <div className="flex items-center justify-between">
            <div>
              <h1 className="text-2xl font-bold text-gray-900">{session.sessionName}</h1>
              <p className="text-gray-500 font-mono">{session.sessionId}</p>
            </div>
            <div className="flex items-center">
              <span className={`w-4 h-4 rounded-full ${getStatusColor()} mr-2`}></span>
              <span className="font-medium">{session.state}</span>
            </div>
          </div>
        </div>

        <div className="p-6 grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Session Info */}
          <div>
            <h2 className="text-lg font-semibold mb-4">Session Information</h2>
            <dl className="space-y-3">
              <div className="flex justify-between">
                <dt className="text-gray-500">SenderCompID</dt>
                <dd className="font-mono">{session.senderCompId}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">TargetCompID</dt>
                <dd className="font-mono">{session.targetCompId}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-gray-500">Role</dt>
                <dd>
                  <span className={`px-2 py-0.5 rounded text-xs font-medium ${
                    session.role === 'INITIATOR'
                      ? 'bg-blue-100 text-blue-800'
                      : 'bg-purple-100 text-purple-800'
                  }`}>
                    {session.role}
                  </span>
                </dd>
              </div>
              {session.host && (
                <div className="flex justify-between">
                  <dt className="text-gray-500">Endpoint</dt>
                  <dd className="font-mono">{session.host}:{session.port}</dd>
                </div>
              )}
              <div className="flex justify-between">
                <dt className="text-gray-500">Heartbeat</dt>
                <dd>{session.heartbeatInterval} seconds</dd>
              </div>
            </dl>
          </div>

          {/* Sequence Numbers */}
          <div>
            <h2 className="text-lg font-semibold mb-4">Sequence Numbers</h2>
            <dl className="space-y-3">
              <div className="flex justify-between items-center">
                <dt className="text-gray-500">Outgoing</dt>
                <dd className="font-mono text-xl">{session.outgoingSeqNum}</dd>
              </div>
              <div className="flex justify-between items-center">
                <dt className="text-gray-500">Expected Incoming</dt>
                <dd className="font-mono text-xl">{session.expectedIncomingSeqNum}</dd>
              </div>
            </dl>

            <div className="mt-4 space-y-2">
              <div className="flex gap-2">
                <input
                  type="number"
                  min="1"
                  placeholder="Set outgoing seq"
                  value={seqNumInput.outgoing}
                  onChange={(e) => setSeqNumInput(prev => ({ ...prev, outgoing: e.target.value }))}
                  className="flex-1 px-3 py-2 border rounded-lg text-sm"
                />
                <button
                  onClick={() => setOutSeqMutation.mutate()}
                  disabled={!seqNumInput.outgoing || setOutSeqMutation.isPending}
                  className="px-3 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50"
                >
                  Set
                </button>
              </div>
              <div className="flex gap-2">
                <input
                  type="number"
                  min="1"
                  placeholder="Set incoming seq"
                  value={seqNumInput.incoming}
                  onChange={(e) => setSeqNumInput(prev => ({ ...prev, incoming: e.target.value }))}
                  className="flex-1 px-3 py-2 border rounded-lg text-sm"
                />
                <button
                  onClick={() => setInSeqMutation.mutate()}
                  disabled={!seqNumInput.incoming || setInSeqMutation.isPending}
                  className="px-3 py-2 bg-blue-600 text-white rounded-lg text-sm hover:bg-blue-700 disabled:opacity-50"
                >
                  Set
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Actions */}
        <div className="p-6 bg-gray-50 border-t">
          <h2 className="text-lg font-semibold mb-4">Actions</h2>
          <div className="flex flex-wrap gap-3">
            <button
              onClick={() => connectMutation.mutate()}
              disabled={session.connected || session.role !== 'INITIATOR' || connectMutation.isPending}
              className="px-4 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Connect
            </button>
            <button
              onClick={() => disconnectMutation.mutate()}
              disabled={!session.connected || disconnectMutation.isPending}
              className="px-4 py-2 bg-red-600 text-white rounded-lg hover:bg-red-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Disconnect
            </button>
            <button
              onClick={() => logoutMutation.mutate()}
              disabled={!session.loggedOn || logoutMutation.isPending}
              className="px-4 py-2 bg-orange-600 text-white rounded-lg hover:bg-orange-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Logout
            </button>
            <button
              onClick={() => resetSeqMutation.mutate()}
              disabled={resetSeqMutation.isPending}
              className="px-4 py-2 bg-gray-600 text-white rounded-lg hover:bg-gray-700 disabled:opacity-50"
            >
              Reset Sequence
            </button>
            <button
              onClick={() => testRequestMutation.mutate()}
              disabled={!session.loggedOn || testRequestMutation.isPending}
              className="px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
            >
              Send Test Request
            </button>
            <button
              onClick={() => eodMutation.mutate()}
              disabled={eodMutation.isPending}
              className="px-4 py-2 bg-purple-600 text-white rounded-lg hover:bg-purple-700 disabled:opacity-50"
            >
              Trigger EOD
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
