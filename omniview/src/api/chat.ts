import type { SseEvent, ChatHealthResponse, ApiMessage } from '../types/chat'

const CHAT_API = '/api/chat'

export async function streamChat(
  messages: ApiMessage[],
  onEvent: (event: SseEvent) => void,
  signal?: AbortSignal
): Promise<void> {
  const response = await fetch(CHAT_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ messages }),
    signal,
  })

  if (!response.ok) {
    const errorText = await response.text()
    throw new Error(`Chat API error: ${response.status} - ${errorText}`)
  }

  const reader = response.body?.getReader()
  if (!reader) throw new Error('No response body')

  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // Parse SSE lines
      const lines = buffer.split('\n')
      buffer = lines.pop() || '' // keep incomplete line in buffer

      for (const line of lines) {
        const trimmed = line.trim()
        if (trimmed.startsWith('data: ')) {
          const jsonStr = trimmed.substring(6)
          if (jsonStr) {
            try {
              const event: SseEvent = JSON.parse(jsonStr)
              onEvent(event)
            } catch {
              // skip malformed JSON
            }
          }
        }
      }
    }
  } finally {
    reader.releaseLock()
  }
}

export async function fetchChatHealth(): Promise<ChatHealthResponse> {
  const response = await fetch(`${CHAT_API}/health`)
  if (!response.ok) {
    throw new Error(`Health check failed: ${response.status}`)
  }
  return response.json()
}
