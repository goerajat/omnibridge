import type { AppConfig, TrackedOrder, OrderCapabilities } from '../types'

function buildUrl(app: AppConfig, path: string): string {
  return `/api/proxy/${app.id}${path}`
}

export async function fetchOrderCapabilities(app: AppConfig): Promise<OrderCapabilities | null> {
  try {
    const response = await fetch(buildUrl(app, '/api/orders/capabilities'), {
      signal: AbortSignal.timeout(5000),
    })
    if (!response.ok) return null
    return response.json()
  } catch {
    return null
  }
}

export async function fetchOrders(app: AppConfig, filters?: {
  session?: string
  state?: string
  symbol?: string
}): Promise<TrackedOrder[]> {
  const params = new URLSearchParams()
  if (filters?.session) params.set('session', filters.session)
  if (filters?.state) params.set('state', filters.state)
  if (filters?.symbol) params.set('symbol', filters.symbol)
  const qs = params.toString()

  const response = await fetch(buildUrl(app, '/api/orders' + (qs ? '?' + qs : '')))
  if (!response.ok) throw new Error(`Failed to fetch orders: ${response.statusText}`)
  return response.json()
}

export async function fetchOrderStats(app: AppConfig): Promise<{ total: number; byState: Record<string, number> }> {
  const response = await fetch(buildUrl(app, '/api/orders/stats'))
  if (!response.ok) throw new Error(`Failed to fetch order stats: ${response.statusText}`)
  return response.json()
}

export async function fetchOrder(app: AppConfig, clOrdId: string): Promise<TrackedOrder> {
  const response = await fetch(buildUrl(app, `/api/orders/${encodeURIComponent(clOrdId)}`))
  if (!response.ok) throw new Error(`Failed to fetch order: ${response.statusText}`)
  return response.json()
}

export async function submitOrder(app: AppConfig, order: {
  symbol: string
  side: string
  qty: number
  price: number
  orderType: string
}): Promise<{ clOrdId: string }> {
  const response = await fetch(buildUrl(app, '/api/orders'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(order),
  })
  if (!response.ok) {
    const err = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(err.error || response.statusText)
  }
  return response.json()
}

export async function rejectOrder(app: AppConfig, clOrdId: string, reason?: string): Promise<TrackedOrder> {
  const response = await fetch(buildUrl(app, `/api/orders/${encodeURIComponent(clOrdId)}/reject`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ reason: reason || 'Manual rejection' }),
  })
  if (!response.ok) {
    const err = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(err.error || response.statusText)
  }
  return response.json()
}

export async function fillOrder(app: AppConfig, clOrdId: string, price?: number): Promise<TrackedOrder> {
  const response = await fetch(buildUrl(app, `/api/orders/${encodeURIComponent(clOrdId)}/fill`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(price != null ? { price } : {}),
  })
  if (!response.ok) {
    const err = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(err.error || response.statusText)
  }
  return response.json()
}

export async function partialFillOrder(app: AppConfig, clOrdId: string, qty: number, price: number): Promise<TrackedOrder> {
  const response = await fetch(buildUrl(app, `/api/orders/${encodeURIComponent(clOrdId)}/partial-fill`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ qty, price }),
  })
  if (!response.ok) {
    const err = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(err.error || response.statusText)
  }
  return response.json()
}

export async function cancelOrder(app: AppConfig, clOrdId: string): Promise<TrackedOrder> {
  const response = await fetch(buildUrl(app, `/api/orders/${encodeURIComponent(clOrdId)}/cancel`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
  })
  if (!response.ok) {
    const err = await response.json().catch(() => ({ error: response.statusText }))
    throw new Error(err.error || response.statusText)
  }
  return response.json()
}
