export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  toolCalls?: ToolCallInfo[]
  toolResults?: ToolResultInfo[]
  timestamp: number
}

export interface ToolCallInfo {
  id: string
  tool: string
  args: Record<string, unknown>
}

export interface ToolResultInfo {
  id: string
  tool: string
  data: unknown
  isError: boolean
}

export interface SseEvent {
  type: 'status' | 'text' | 'tool_call' | 'tool_result' | 'error' | 'done'
  data: Record<string, unknown>
}

export interface ChatHealthResponse {
  enabled: boolean
  mcpServerReachable: boolean
  model: string
}

export interface ApiMessage {
  role: 'user' | 'assistant'
  content: string | unknown[]
}
