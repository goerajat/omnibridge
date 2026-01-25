import { useState } from 'react'
import { useAppStore } from '../store/appStore'
import { useSessionStore } from '../store/sessionStore'
import { AddAppModal } from '../components/AddAppModal'
import { StatusIndicator } from '../components/StatusIndicator'
import { testConnection } from '../api/sessions'
import type { AppConfig } from '../types'

export function Settings() {
  const { apps, removeApp, toggleApp } = useAppStore()
  const { getConnectionStatus, clearAppData } = useSessionStore()

  const [editingApp, setEditingApp] = useState<AppConfig | undefined>()
  const [showAddModal, setShowAddModal] = useState(false)
  const [testingApp, setTestingApp] = useState<string | null>(null)
  const [testResults, setTestResults] = useState<Map<string, boolean>>(new Map())

  const handleDelete = (app: AppConfig) => {
    if (confirm(`Are you sure you want to delete "${app.name}"?`)) {
      clearAppData(app.id)
      removeApp(app.id)
    }
  }

  const handleTestConnection = async (app: AppConfig) => {
    setTestingApp(app.id)
    const success = await testConnection(app)
    setTestResults((prev) => new Map(prev).set(app.id, success))
    setTestingApp(null)
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold text-white">Settings</h1>
        <button
          onClick={() => setShowAddModal(true)}
          className="px-4 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-md text-sm font-medium transition-colors"
        >
          + Add App
        </button>
      </div>

      <div className="bg-gray-800 rounded-lg border border-gray-700">
        <div className="px-6 py-4 border-b border-gray-700">
          <h2 className="text-lg font-semibold text-white">
            Configured Applications
          </h2>
        </div>

        {apps.length === 0 ? (
          <div className="px-6 py-8 text-center text-gray-400">
            No applications configured yet.
          </div>
        ) : (
          <div className="divide-y divide-gray-700">
            {apps.map((app) => {
              const connectionStatus = getConnectionStatus(app.id)
              const testResult = testResults.get(app.id)

              return (
                <div
                  key={app.id}
                  className="px-6 py-4 flex items-center justify-between"
                >
                  <div className="flex items-center gap-4">
                    <StatusIndicator status={connectionStatus} />
                    <div>
                      <h3 className="font-medium text-white">{app.name}</h3>
                      <p className="text-sm text-gray-400">
                        {app.host}:{app.port}
                      </p>
                    </div>
                    {!app.enabled && (
                      <span className="px-2 py-0.5 bg-gray-700 text-gray-400 rounded text-xs">
                        Disabled
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-3">
                    {testResult !== undefined && (
                      <span
                        className={`text-sm ${
                          testResult ? 'text-green-400' : 'text-red-400'
                        }`}
                      >
                        {testResult ? 'OK' : 'Failed'}
                      </span>
                    )}

                    <button
                      onClick={() => handleTestConnection(app)}
                      disabled={testingApp === app.id}
                      className="px-3 py-1 text-sm bg-gray-700 hover:bg-gray-600 text-gray-300 rounded-md disabled:opacity-50"
                    >
                      {testingApp === app.id ? 'Testing...' : 'Test'}
                    </button>

                    <button
                      onClick={() => toggleApp(app.id)}
                      className={`px-3 py-1 text-sm rounded-md ${
                        app.enabled
                          ? 'bg-yellow-900 hover:bg-yellow-800 text-yellow-300'
                          : 'bg-green-900 hover:bg-green-800 text-green-300'
                      }`}
                    >
                      {app.enabled ? 'Disable' : 'Enable'}
                    </button>

                    <button
                      onClick={() => setEditingApp(app)}
                      className="px-3 py-1 text-sm bg-gray-700 hover:bg-gray-600 text-gray-300 rounded-md"
                    >
                      Edit
                    </button>

                    <button
                      onClick={() => handleDelete(app)}
                      className="px-3 py-1 text-sm bg-red-900 hover:bg-red-800 text-red-300 rounded-md"
                    >
                      Delete
                    </button>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>

      <AddAppModal
        isOpen={showAddModal || !!editingApp}
        onClose={() => {
          setShowAddModal(false)
          setEditingApp(undefined)
        }}
        editApp={editingApp}
      />
    </div>
  )
}
