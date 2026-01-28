import { useEffect } from 'react'
import { Routes, Route } from 'react-router-dom'
import { Layout } from './components/Layout'
import { Dashboard } from './pages/Dashboard'
import { AppDetail } from './pages/AppDetail'
import { Settings } from './pages/Settings'
import { useAppConnections } from './hooks/useAppConnections'
import { useAppStore } from './store/appStore'

function App() {
  const fetchApps = useAppStore((state) => state.fetchApps)
  const initialized = useAppStore((state) => state.initialized)

  // Fetch app configurations from server on mount
  useEffect(() => {
    fetchApps()
  }, [fetchApps])

  // Initialize WebSocket connections for all configured apps
  useAppConnections()

  // Show loading state while fetching initial app configurations
  if (!initialized) {
    return (
      <div className="min-h-screen bg-gray-900 flex items-center justify-center">
        <div className="text-gray-400">Loading...</div>
      </div>
    )
  }

  return (
    <Routes>
      <Route path="/" element={<Layout />}>
        <Route index element={<Dashboard />} />
        <Route path="app/:appId" element={<AppDetail />} />
        <Route path="settings" element={<Settings />} />
      </Route>
    </Routes>
  )
}

export default App
