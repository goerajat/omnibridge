import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import type { AppConfig } from '../types'

interface AppState {
  apps: AppConfig[]
  addApp: (app: Omit<AppConfig, 'id'>) => string
  updateApp: (id: string, updates: Partial<Omit<AppConfig, 'id'>>) => void
  removeApp: (id: string) => void
  toggleApp: (id: string) => void
  getApp: (id: string) => AppConfig | undefined
}

function generateId(): string {
  return Math.random().toString(36).substring(2, 11)
}

export const useAppStore = create<AppState>()(
  persist(
    (set, get) => ({
      apps: [],

      addApp: (app) => {
        const id = generateId()
        set((state) => ({
          apps: [...state.apps, { ...app, id }],
        }))
        return id
      },

      updateApp: (id, updates) => {
        set((state) => ({
          apps: state.apps.map((app) =>
            app.id === id ? { ...app, ...updates } : app
          ),
        }))
      },

      removeApp: (id) => {
        set((state) => ({
          apps: state.apps.filter((app) => app.id !== id),
        }))
      },

      toggleApp: (id) => {
        set((state) => ({
          apps: state.apps.map((app) =>
            app.id === id ? { ...app, enabled: !app.enabled } : app
          ),
        }))
      },

      getApp: (id) => {
        return get().apps.find((app) => app.id === id)
      },
    }),
    {
      name: 'omniview-apps',
    }
  )
)
