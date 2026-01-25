import { Routes, Route } from 'react-router-dom'
import { Layout } from './components/Layout'
import { Dashboard } from './pages/Dashboard'
import { AppDetail } from './pages/AppDetail'
import { Settings } from './pages/Settings'
import { useAppConnections } from './hooks/useAppConnections'

function App() {
  // Initialize WebSocket connections for all configured apps
  useAppConnections()

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
