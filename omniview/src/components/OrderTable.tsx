import { useState } from 'react'
import type { AppConfig, TrackedOrder } from '../types'
import { MessageViewer } from './MessageViewer'
import { PartialFillModal } from './PartialFillModal'
import { rejectOrder, fillOrder, cancelOrder } from '../api/orders'

interface OrderTableProps {
  orders: TrackedOrder[]
  app: AppConfig
  canManageOrders: boolean
  onRefresh: () => void
}

const stateColors: Record<string, string> = {
  PENDING: 'bg-gray-700 text-gray-300',
  PENDING_NEW: 'bg-gray-700 text-gray-300',
  NEW: 'bg-blue-900 text-blue-300',
  PARTIALLY_FILLED: 'bg-yellow-900 text-yellow-300',
  FILLED: 'bg-green-900 text-green-300',
  CANCELED: 'bg-gray-700 text-gray-400',
  REJECTED: 'bg-red-900 text-red-300',
  REPLACED: 'bg-purple-900 text-purple-300',
}

function isActionable(state: string): boolean {
  return state === 'NEW' || state === 'PARTIALLY_FILLED' || state === 'PENDING'
}

export function OrderTable({ orders, app, canManageOrders, onRefresh }: OrderTableProps) {
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [actionInProgress, setActionInProgress] = useState<string | null>(null)
  const [partialFillOrder, setPartialFillOrder] = useState<TrackedOrder | null>(null)

  const handleAction = async (clOrdId: string, action: () => Promise<unknown>) => {
    setActionInProgress(clOrdId)
    try {
      await action()
      onRefresh()
    } catch (err) {
      console.error('Action failed:', err)
    } finally {
      setActionInProgress(null)
    }
  }

  if (orders.length === 0) {
    return (
      <div className="text-center py-8 text-gray-400">
        No orders tracked yet
      </div>
    )
  }

  return (
    <>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-gray-400 border-b border-gray-700">
              <th className="pb-3 pr-4 font-medium">ClOrdID</th>
              <th className="pb-3 pr-4 font-medium">Symbol</th>
              <th className="pb-3 pr-4 font-medium">Side</th>
              <th className="pb-3 pr-4 font-medium">Qty</th>
              <th className="pb-3 pr-4 font-medium">Price</th>
              <th className="pb-3 pr-4 font-medium">State</th>
              <th className="pb-3 pr-4 font-medium">Filled</th>
              <th className="pb-3 pr-4 font-medium">Leaves</th>
              <th className="pb-3 pr-4 font-medium">Time</th>
              {canManageOrders && <th className="pb-3 font-medium">Actions</th>}
            </tr>
          </thead>
          <tbody>
            {orders.map((order) => (
              <>
                <tr
                  key={order.clOrdId}
                  onClick={() =>
                    setExpandedId(expandedId === order.clOrdId ? null : order.clOrdId)
                  }
                  className="border-b border-gray-800 hover:bg-gray-800/50 cursor-pointer"
                >
                  <td className="py-3 pr-4 font-mono text-xs text-gray-300">
                    {order.clOrdId}
                  </td>
                  <td className="py-3 pr-4 text-white font-medium">{order.symbol}</td>
                  <td className="py-3 pr-4">
                    <span
                      className={`font-medium ${
                        order.side === 'BUY' ? 'text-green-400' : 'text-red-400'
                      }`}
                    >
                      {order.side}
                    </span>
                  </td>
                  <td className="py-3 pr-4 text-gray-300">{order.qty}</td>
                  <td className="py-3 pr-4 text-gray-300">
                    {order.price > 0 ? order.price.toFixed(2) : '-'}
                  </td>
                  <td className="py-3 pr-4">
                    <span
                      className={`px-2 py-0.5 rounded text-xs font-medium ${
                        stateColors[order.state] || 'bg-gray-700 text-gray-300'
                      }`}
                    >
                      {order.state}
                    </span>
                  </td>
                  <td className="py-3 pr-4 text-gray-300">{order.filledQty}</td>
                  <td className="py-3 pr-4 text-gray-300">{order.leavesQty}</td>
                  <td className="py-3 pr-4 text-gray-500 text-xs">
                    {new Date(order.createTime).toLocaleTimeString()}
                  </td>
                  {canManageOrders && (
                    <td className="py-3" onClick={(e) => e.stopPropagation()}>
                      {isActionable(order.state) && (
                        <div className="flex gap-1">
                          <button
                            onClick={() =>
                              handleAction(order.clOrdId, () => fillOrder(app, order.clOrdId))
                            }
                            disabled={actionInProgress === order.clOrdId}
                            className="px-2 py-1 text-xs rounded bg-green-900 hover:bg-green-800 text-green-300 disabled:opacity-50 transition-colors"
                          >
                            Fill
                          </button>
                          <button
                            onClick={() => setPartialFillOrder(order)}
                            disabled={actionInProgress === order.clOrdId}
                            className="px-2 py-1 text-xs rounded bg-yellow-900 hover:bg-yellow-800 text-yellow-300 disabled:opacity-50 transition-colors"
                          >
                            Partial
                          </button>
                          <button
                            onClick={() =>
                              handleAction(order.clOrdId, () => cancelOrder(app, order.clOrdId))
                            }
                            disabled={actionInProgress === order.clOrdId}
                            className="px-2 py-1 text-xs rounded bg-gray-700 hover:bg-gray-600 text-gray-300 disabled:opacity-50 transition-colors"
                          >
                            Cancel
                          </button>
                          {order.state === 'PENDING' && (
                            <button
                              onClick={() =>
                                handleAction(order.clOrdId, () =>
                                  rejectOrder(app, order.clOrdId)
                                )
                              }
                              disabled={actionInProgress === order.clOrdId}
                              className="px-2 py-1 text-xs rounded bg-red-900 hover:bg-red-800 text-red-300 disabled:opacity-50 transition-colors"
                            >
                              Reject
                            </button>
                          )}
                        </div>
                      )}
                    </td>
                  )}
                </tr>
                {expandedId === order.clOrdId && (
                  <tr key={`${order.clOrdId}-detail`} className="border-b border-gray-800">
                    <td colSpan={canManageOrders ? 10 : 9} className="px-4 py-3 bg-gray-900/50">
                      <div className="mb-2 text-xs text-gray-400">
                        Order ID: {order.orderId} | Session: {order.sessionId} | Type:{' '}
                        {order.orderType}
                        {order.avgFillPrice > 0 && ` | Avg Fill Price: ${order.avgFillPrice.toFixed(2)}`}
                      </div>
                      <MessageViewer messages={order.messages} />
                    </td>
                  </tr>
                )}
              </>
            ))}
          </tbody>
        </table>
      </div>

      {partialFillOrder && (
        <PartialFillModal
          app={app}
          order={partialFillOrder}
          onClose={() => setPartialFillOrder(null)}
          onFilled={onRefresh}
        />
      )}
    </>
  )
}
