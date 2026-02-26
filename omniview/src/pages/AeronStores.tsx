import { useState, useEffect, useCallback } from 'react'
import { useAppStore } from '../store/appStore'
import type { AppConfig } from '../types'

const POLL_INTERVAL = 5000

interface StoreStatus {
  status: string
  entriesReceived: number
  publisherCount: number
  streamCount: number
}

interface PublisherInfo {
  publisherId: number
  entriesReceived: number
  lastTimestamp: number
  lastSeqNum: number
}

interface StreamInfo {
  streamName: string
  entryCount: number
}

function formatTimestamp(ts: number): string {
  if (!ts) return '-'
  return new Date(ts).toLocaleString()
}

function stripStreamPrefix(name: string): string {
  const match = name.match(/^pub~\d+~(.+)$/)
  return match ? match[1] : name
}

function StoreCard({ app }: { app: AppConfig }) {
  const [status, setStatus] = useState<StoreStatus | null>(null)
  const [publishers, setPublishers] = useState<PublisherInfo[]>([])
  const [streams, setStreams] = useState<StreamInfo[]>([])
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState(false)

  const fetchData = useCallback(async () => {
    try {
      const statusRes = await fetch(`/api/proxy/${app.id}/api/store/status`)
      if (!statusRes.ok) throw new Error(`Status ${statusRes.status}`)
      setStatus(await statusRes.json())
      setError(null)

      if (expanded) {
        const [pubRes, streamRes] = await Promise.all([
          fetch(`/api/proxy/${app.id}/api/store/publishers`),
          fetch(`/api/proxy/${app.id}/api/store/streams`),
        ])
        if (pubRes.ok) setPublishers(await pubRes.json())
        if (streamRes.ok) setStreams(await streamRes.json())
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to fetch')
      setStatus(null)
    }
  }, [app.id, expanded])

  useEffect(() => {
    if (!app.enabled) return

    fetchData()
    const interval = setInterval(fetchData, POLL_INTERVAL)
    return () => clearInterval(interval)
  }, [app.enabled, fetchData])

  const isConnected = status !== null && error === null

  return (
    <div className="bg-gray-800 rounded-lg border border-gray-700 p-5">
      <div className="flex items-center justify-between mb-4">
        <div className="flex items-center gap-3">
          <div
            className={`w-3 h-3 rounded-full ${
              !app.enabled
                ? 'bg-gray-600'
                : isConnected
                  ? 'bg-green-500'
                  : 'bg-red-500'
            }`}
          />
          <h3 className="text-lg font-semibold text-white">{app.name}</h3>
        </div>
        <span className="text-xs text-gray-500">
          {app.host}:{app.port}
        </span>
      </div>

      {!app.enabled ? (
        <p className="text-sm text-gray-500">Disabled</p>
      ) : error ? (
        <p className="text-sm text-red-400">{error}</p>
      ) : status ? (
        <>
          <div className="grid grid-cols-3 gap-4 mb-4">
            <div>
              <div className="text-xs text-gray-500 uppercase">Entries</div>
              <div className="text-xl font-mono text-white">
                {status.entriesReceived.toLocaleString()}
              </div>
            </div>
            <div>
              <div className="text-xs text-gray-500 uppercase">Publishers</div>
              <div className="text-xl font-mono text-white">
                {status.publisherCount}
              </div>
            </div>
            <div>
              <div className="text-xs text-gray-500 uppercase">Streams</div>
              <div className="text-xl font-mono text-white">
                {status.streamCount}
              </div>
            </div>
          </div>

          <button
            onClick={() => setExpanded(!expanded)}
            className="text-sm text-blue-400 hover:text-blue-300 transition-colors"
          >
            {expanded ? 'Hide details' : 'Show details'}
          </button>

          {expanded && (
            <div className="mt-4 space-y-4">
              {/* Publishers Table */}
              <div>
                <h4 className="text-sm font-medium text-gray-400 mb-2">
                  Publishers
                </h4>
                {publishers.length === 0 ? (
                  <p className="text-sm text-gray-600">No publishers</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-left text-gray-500 border-b border-gray-700">
                          <th className="py-1 pr-4">ID</th>
                          <th className="py-1 pr-4">Entries</th>
                          <th className="py-1 pr-4">Last Timestamp</th>
                          <th className="py-1">Last Seq</th>
                        </tr>
                      </thead>
                      <tbody>
                        {publishers.map((pub) => (
                          <tr
                            key={pub.publisherId}
                            className="text-gray-300 border-b border-gray-700/50"
                          >
                            <td className="py-1 pr-4 font-mono">
                              {pub.publisherId}
                            </td>
                            <td className="py-1 pr-4 font-mono">
                              {pub.entriesReceived.toLocaleString()}
                            </td>
                            <td className="py-1 pr-4">
                              {formatTimestamp(pub.lastTimestamp)}
                            </td>
                            <td className="py-1 font-mono">
                              {pub.lastSeqNum}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>

              {/* Streams Table */}
              <div>
                <h4 className="text-sm font-medium text-gray-400 mb-2">
                  Streams
                </h4>
                {streams.length === 0 ? (
                  <p className="text-sm text-gray-600">No streams</p>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                      <thead>
                        <tr className="text-left text-gray-500 border-b border-gray-700">
                          <th className="py-1 pr-4">Stream</th>
                          <th className="py-1">Entries</th>
                        </tr>
                      </thead>
                      <tbody>
                        {streams.map((s) => (
                          <tr
                            key={s.streamName}
                            className="text-gray-300 border-b border-gray-700/50"
                          >
                            <td className="py-1 pr-4">
                              {stripStreamPrefix(s.streamName)}
                            </td>
                            <td className="py-1 font-mono">
                              {s.entryCount.toLocaleString()}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>
            </div>
          )}
        </>
      ) : (
        <p className="text-sm text-gray-500">Loading...</p>
      )}
    </div>
  )
}

export function AeronStores() {
  const apps = useAppStore((state) => state.apps)
  const storeApps = apps.filter((app) => app.type === 'store')

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-white">Stores</h1>
      </div>

      {storeApps.length === 0 ? (
        <div className="text-center py-12">
          <div className="text-gray-400 mb-2">No store applications configured.</div>
          <div className="text-gray-500 text-sm">
            Add an app with type "Store" in Settings to monitor Aeron remote stores.
          </div>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {storeApps.map((app) => (
            <StoreCard key={app.id} app={app} />
          ))}
        </div>
      )}
    </div>
  )
}
