import { useState } from 'react'
import type { AppConfig } from '../types'
import { submitOrder } from '../api/orders'

interface SendOrderFormProps {
  app: AppConfig
  onOrderSent: () => void
}

export function SendOrderForm({ app, onOrderSent }: SendOrderFormProps) {
  const [symbol, setSymbol] = useState('AAPL')
  const [side, setSide] = useState('BUY')
  const [qty, setQty] = useState('100')
  const [price, setPrice] = useState('150.00')
  const [orderType, setOrderType] = useState('LIMIT')
  const [submitting, setSubmitting] = useState(false)
  const [feedback, setFeedback] = useState<{ type: 'success' | 'error'; message: string } | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setSubmitting(true)
    setFeedback(null)

    try {
      const result = await submitOrder(app, {
        symbol,
        side,
        qty: parseInt(qty, 10),
        price: parseFloat(price),
        orderType,
      })
      setFeedback({ type: 'success', message: `Order sent: ${result.clOrdId}` })
      onOrderSent()
      setTimeout(() => setFeedback(null), 3000)
    } catch (err) {
      setFeedback({ type: 'error', message: err instanceof Error ? err.message : 'Failed to submit order' })
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-wrap items-end gap-3 mb-4">
      <div>
        <label className="block text-xs text-gray-400 mb-1">Symbol</label>
        <input
          type="text"
          value={symbol}
          onChange={(e) => setSymbol(e.target.value.toUpperCase())}
          className="bg-gray-700 text-white px-2 py-1.5 rounded text-sm w-24 border border-gray-600 focus:border-blue-500 focus:outline-none"
          required
        />
      </div>
      <div>
        <label className="block text-xs text-gray-400 mb-1">Side</label>
        <select
          value={side}
          onChange={(e) => setSide(e.target.value)}
          className="bg-gray-700 text-white px-2 py-1.5 rounded text-sm border border-gray-600 focus:border-blue-500 focus:outline-none"
        >
          <option value="BUY">BUY</option>
          <option value="SELL">SELL</option>
        </select>
      </div>
      <div>
        <label className="block text-xs text-gray-400 mb-1">Qty</label>
        <input
          type="number"
          value={qty}
          onChange={(e) => setQty(e.target.value)}
          className="bg-gray-700 text-white px-2 py-1.5 rounded text-sm w-20 border border-gray-600 focus:border-blue-500 focus:outline-none"
          min="1"
          required
        />
      </div>
      <div>
        <label className="block text-xs text-gray-400 mb-1">Price</label>
        <input
          type="number"
          value={price}
          onChange={(e) => setPrice(e.target.value)}
          className="bg-gray-700 text-white px-2 py-1.5 rounded text-sm w-28 border border-gray-600 focus:border-blue-500 focus:outline-none"
          step="0.01"
          min="0"
        />
      </div>
      <div>
        <label className="block text-xs text-gray-400 mb-1">Type</label>
        <select
          value={orderType}
          onChange={(e) => setOrderType(e.target.value)}
          className="bg-gray-700 text-white px-2 py-1.5 rounded text-sm border border-gray-600 focus:border-blue-500 focus:outline-none"
        >
          <option value="LIMIT">LIMIT</option>
          <option value="MARKET">MARKET</option>
        </select>
      </div>
      <button
        type="submit"
        disabled={submitting}
        className="bg-blue-600 hover:bg-blue-500 text-white px-4 py-1.5 rounded text-sm font-medium disabled:opacity-50 transition-colors"
      >
        {submitting ? 'Sending...' : 'Send Order'}
      </button>
      {feedback && (
        <span
          className={`text-sm ${
            feedback.type === 'success' ? 'text-green-400' : 'text-red-400'
          }`}
        >
          {feedback.message}
        </span>
      )}
    </form>
  )
}
