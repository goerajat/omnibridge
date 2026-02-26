import { useState } from 'react'
import type { AppConfig, TrackedOrder } from '../types'
import { partialFillOrder } from '../api/orders'

interface PartialFillModalProps {
  app: AppConfig
  order: TrackedOrder
  onClose: () => void
  onFilled: () => void
}

export function PartialFillModal({ app, order, onClose, onFilled }: PartialFillModalProps) {
  const [qty, setQty] = useState(Math.floor(order.leavesQty / 2).toString())
  const [price, setPrice] = useState(order.price.toFixed(2))
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setError(null)

    try {
      await partialFillOrder(app, order.clOrdId, parseInt(qty, 10), parseFloat(price))
      onFilled()
      onClose()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to partial fill')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-gray-800 rounded-lg border border-gray-700 p-6 w-96">
        <h3 className="text-lg font-semibold text-white mb-4">
          Partial Fill: {order.clOrdId}
        </h3>
        <p className="text-sm text-gray-400 mb-4">
          {order.symbol} {order.side} - Leaves: {order.leavesQty}
        </p>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="block text-sm text-gray-400 mb-1">
              Quantity (max: {order.leavesQty})
            </label>
            <input
              type="number"
              value={qty}
              onChange={(e) => setQty(e.target.value)}
              className="w-full bg-gray-700 text-white px-3 py-2 rounded text-sm border border-gray-600 focus:border-blue-500 focus:outline-none"
              min="1"
              max={order.leavesQty}
              required
            />
          </div>
          <div>
            <label className="block text-sm text-gray-400 mb-1">Price</label>
            <input
              type="number"
              value={price}
              onChange={(e) => setPrice(e.target.value)}
              className="w-full bg-gray-700 text-white px-3 py-2 rounded text-sm border border-gray-600 focus:border-blue-500 focus:outline-none"
              step="0.01"
              min="0"
              required
            />
          </div>

          {error && <p className="text-red-400 text-sm">{error}</p>}

          <div className="flex gap-3 justify-end">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-gray-300 hover:text-white transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="bg-yellow-600 hover:bg-yellow-500 text-white px-4 py-2 rounded text-sm font-medium disabled:opacity-50 transition-colors"
            >
              {submitting ? 'Filling...' : 'Partial Fill'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
