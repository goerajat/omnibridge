import { useState } from 'react'
import { useAppStore } from '../store/appStore'
import { testConnection } from '../api/sessions'
import type { AppConfig } from '../types'

interface AddAppModalProps {
  isOpen: boolean
  onClose: () => void
  editApp?: AppConfig
}

export function AddAppModal({ isOpen, onClose, editApp }: AddAppModalProps) {
  const { addApp, updateApp } = useAppStore()

  const [name, setName] = useState(editApp?.name || '')
  const [host, setHost] = useState(editApp?.host || 'localhost')
  const [port, setPort] = useState(editApp?.port?.toString() || '8080')
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<'success' | 'failure' | null>(null)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  if (!isOpen) return null

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')

    const portNum = parseInt(port, 10)
    if (isNaN(portNum) || portNum < 1 || portNum > 65535) {
      setError('Port must be a number between 1 and 65535')
      return
    }

    if (!name.trim()) {
      setError('Name is required')
      return
    }

    setSaving(true)
    try {
      if (editApp) {
        await updateApp(editApp.id, {
          name: name.trim(),
          host: host.trim(),
          port: portNum,
        })
      } else {
        await addApp({
          name: name.trim(),
          host: host.trim(),
          port: portNum,
          enabled: true,
        })
      }
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to save app')
    } finally {
      setSaving(false)
    }
  }

  const handleTestConnection = async () => {
    setTesting(true)
    setTestResult(null)

    const portNum = parseInt(port, 10)
    if (isNaN(portNum)) {
      setTestResult('failure')
      setTesting(false)
      return
    }

    const success = await testConnection({
      id: '',
      name: name.trim(),
      host: host.trim(),
      port: portNum,
      enabled: true,
    })

    setTestResult(success ? 'success' : 'failure')
    setTesting(false)
  }

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-gray-800 rounded-lg p-6 w-full max-w-md border border-gray-700">
        <h2 className="text-xl font-semibold text-white mb-4">
          {editApp ? 'Edit App' : 'Add App'}
        </h2>

        <form onSubmit={handleSubmit}>
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">
                Name
              </label>
              <input
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full px-3 py-2 bg-gray-700 border border-gray-600 rounded-md text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="My FIX App"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">
                Host
              </label>
              <input
                type="text"
                value={host}
                onChange={(e) => setHost(e.target.value)}
                className="w-full px-3 py-2 bg-gray-700 border border-gray-600 rounded-md text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="localhost"
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-300 mb-1">
                Port
              </label>
              <input
                type="text"
                value={port}
                onChange={(e) => setPort(e.target.value)}
                className="w-full px-3 py-2 bg-gray-700 border border-gray-600 rounded-md text-white placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
                placeholder="8080"
              />
            </div>

            {error && (
              <p className="text-sm text-red-400">{error}</p>
            )}

            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={handleTestConnection}
                disabled={testing}
                className="px-3 py-1 text-sm bg-gray-700 hover:bg-gray-600 text-gray-300 rounded-md disabled:opacity-50"
              >
                {testing ? 'Testing...' : 'Test Connection'}
              </button>
              {testResult === 'success' && (
                <span className="text-sm text-green-400">Connection successful</span>
              )}
              {testResult === 'failure' && (
                <span className="text-sm text-red-400">Connection failed</span>
              )}
            </div>
          </div>

          <div className="mt-6 flex justify-end gap-3">
            <button
              type="button"
              onClick={onClose}
              disabled={saving}
              className="px-4 py-2 text-sm text-gray-300 hover:text-white disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={saving}
              className="px-4 py-2 text-sm bg-blue-600 hover:bg-blue-500 text-white rounded-md disabled:opacity-50"
            >
              {saving ? 'Saving...' : editApp ? 'Save' : 'Add'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
