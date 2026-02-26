import { useState, useEffect, useCallback } from 'react'
import type { AppConfig, TrackedOrder, OrderCapabilities } from '../types'
import { fetchOrders, fetchOrderStats } from '../api/orders'
import { OrderTable } from './OrderTable'
import { SendOrderForm } from './SendOrderForm'

interface OrdersPanelProps {
  app: AppConfig
  capabilities: OrderCapabilities
}

const stateColorMap: Record<string, string> = {
  NEW: 'bg-blue-900 text-blue-300',
  PARTIALLY_FILLED: 'bg-yellow-900 text-yellow-300',
  FILLED: 'bg-green-900 text-green-300',
  CANCELED: 'bg-gray-700 text-gray-400',
  REJECTED: 'bg-red-900 text-red-300',
  REPLACED: 'bg-purple-900 text-purple-300',
  PENDING: 'bg-gray-700 text-gray-300',
  PENDING_NEW: 'bg-gray-700 text-gray-300',
}

export function OrdersPanel({ app, capabilities }: OrdersPanelProps) {
  const [orders, setOrders] = useState<TrackedOrder[]>([])
  const [stats, setStats] = useState<{ total: number; byState: Record<string, number> } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      const [ordersData, statsData] = await Promise.all([
        fetchOrders(app),
        fetchOrderStats(app),
      ])
      setOrders(ordersData)
      setStats(statsData)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to fetch orders')
    }
  }, [app])

  useEffect(() => {
    refresh()
    const interval = setInterval(refresh, 3000)
    return () => clearInterval(interval)
  }, [refresh])

  return (
    <div>
      {/* Stats bar */}
      {stats && (
        <div className="flex flex-wrap gap-2 mb-4">
          <span className="px-3 py-1 bg-gray-700 text-white rounded text-sm font-medium">
            Total: {stats.total}
          </span>
          {Object.entries(stats.byState).map(([state, count]) => (
            <span
              key={state}
              className={`px-3 py-1 rounded text-sm font-medium ${
                stateColorMap[state] || 'bg-gray-700 text-gray-300'
              }`}
            >
              {state}: {count}
            </span>
          ))}
        </div>
      )}

      {/* Send order form (initiator only) */}
      {capabilities.canSendOrders && (
        <SendOrderForm app={app} onOrderSent={refresh} />
      )}

      {error && (
        <p className="text-red-400 text-sm mb-4">{error}</p>
      )}

      {/* Order table */}
      <OrderTable
        orders={orders}
        app={app}
        canManageOrders={capabilities.canManageOrders}
        onRefresh={refresh}
      />
    </div>
  )
}
