import { create } from 'zustand'
import type { AppConfig } from '../types'

// API base URL - in production, this is served from the same origin
const API_BASE = '/api/apps'

interface AppState {
  apps: AppConfig[]
  loading: boolean
  error: string | null
  initialized: boolean
  fetchApps: () => Promise<void>
  addApp: (app: Omit<AppConfig, 'id'>) => Promise<string>
  updateApp: (id: string, updates: Partial<Omit<AppConfig, 'id'>>) => Promise<void>
  removeApp: (id: string) => Promise<void>
  toggleApp: (id: string) => Promise<void>
  getApp: (id: string) => AppConfig | undefined
}

export const useAppStore = create<AppState>()((set, get) => ({
  apps: [],
  loading: false,
  error: null,
  initialized: false,

  fetchApps: async () => {
    if (get().loading) return

    set({ loading: true, error: null })
    try {
      const response = await fetch(API_BASE)
      if (!response.ok) {
        throw new Error(`Failed to fetch apps: ${response.status}`)
      }
      const apps: AppConfig[] = await response.json()
      set({ apps, loading: false, initialized: true })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to fetch apps'
      console.error('Failed to fetch apps:', error)
      set({ error: message, loading: false, initialized: true })
    }
  },

  addApp: async (app) => {
    set({ error: null })
    try {
      const response = await fetch(API_BASE, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...app, id: '' }),
      })
      if (!response.ok) {
        throw new Error(`Failed to add app: ${response.status}`)
      }
      const created: AppConfig = await response.json()
      set((state) => ({
        apps: [...state.apps, created],
      }))
      return created.id
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to add app'
      console.error('Failed to add app:', error)
      set({ error: message })
      throw error
    }
  },

  updateApp: async (id, updates) => {
    set({ error: null })
    try {
      const currentApp = get().apps.find((app) => app.id === id)
      if (!currentApp) {
        throw new Error(`App not found: ${id}`)
      }

      const response = await fetch(`${API_BASE}/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...currentApp, ...updates }),
      })
      if (!response.ok) {
        throw new Error(`Failed to update app: ${response.status}`)
      }
      const updated: AppConfig = await response.json()
      set((state) => ({
        apps: state.apps.map((app) => (app.id === id ? updated : app)),
      }))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to update app'
      console.error('Failed to update app:', error)
      set({ error: message })
      throw error
    }
  },

  removeApp: async (id) => {
    set({ error: null })
    try {
      const response = await fetch(`${API_BASE}/${id}`, {
        method: 'DELETE',
      })
      if (!response.ok && response.status !== 404) {
        throw new Error(`Failed to remove app: ${response.status}`)
      }
      set((state) => ({
        apps: state.apps.filter((app) => app.id !== id),
      }))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to remove app'
      console.error('Failed to remove app:', error)
      set({ error: message })
      throw error
    }
  },

  toggleApp: async (id) => {
    set({ error: null })
    try {
      const response = await fetch(`${API_BASE}/${id}/toggle`, {
        method: 'POST',
      })
      if (!response.ok) {
        throw new Error(`Failed to toggle app: ${response.status}`)
      }
      const updated: AppConfig = await response.json()
      set((state) => ({
        apps: state.apps.map((app) => (app.id === id ? updated : app)),
      }))
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Failed to toggle app'
      console.error('Failed to toggle app:', error)
      set({ error: message })
      throw error
    }
  },

  getApp: (id) => {
    return get().apps.find((app) => app.id === id)
  },
}))
