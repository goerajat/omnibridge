import { useState } from 'react'
import { useAppStore } from '../store/appStore'
import { useSessionStore } from '../store/sessionStore'
import { AppCard } from '../components/AppCard'
import { AddAppModal } from '../components/AddAppModal'

export function Dashboard() {
  const apps = useAppStore((state) => state.apps)
  const { getStats, getConnectionStatus } = useSessionStore()
  const [showAddModal, setShowAddModal] = useState(false)

  const enabledApps = apps.filter((app) => app.enabled)
  const disabledApps = apps.filter((app) => !app.enabled)

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-white">Dashboard</h1>
        <button
          onClick={() => setShowAddModal(true)}
          className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-md text-sm font-medium transition-colors"
        >
          + Add App
        </button>
      </div>

      {apps.length === 0 ? (
        <div className="text-center py-12">
          <div className="text-gray-400 mb-4">
            No applications configured yet.
          </div>
          <button
            onClick={() => setShowAddModal(true)}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-md text-sm font-medium transition-colors"
          >
            Add your first app
          </button>
        </div>
      ) : (
        <>
          {enabledApps.length > 0 && (
            <div className="mb-8">
              <h2 className="text-lg font-semibold text-gray-300 mb-4">
                Active Applications ({enabledApps.length})
              </h2>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {enabledApps.map((app) => (
                  <AppCard
                    key={app.id}
                    app={app}
                    stats={getStats(app.id)}
                    connectionStatus={getConnectionStatus(app.id)}
                  />
                ))}
              </div>
            </div>
          )}

          {disabledApps.length > 0 && (
            <div>
              <h2 className="text-lg font-semibold text-gray-500 mb-4">
                Disabled Applications ({disabledApps.length})
              </h2>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 opacity-60">
                {disabledApps.map((app) => (
                  <AppCard
                    key={app.id}
                    app={app}
                    stats={getStats(app.id)}
                    connectionStatus={getConnectionStatus(app.id)}
                  />
                ))}
              </div>
            </div>
          )}
        </>
      )}

      <AddAppModal
        isOpen={showAddModal}
        onClose={() => setShowAddModal(false)}
      />
    </div>
  )
}
