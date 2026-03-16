import { create } from 'zustand'
import type { ChatMessage, ToolCallInfo, ToolResultInfo, ApiMessage } from '../types/chat'

interface ChatState {
  messages: ChatMessage[]
  isLoading: boolean
  error: string | null
  abortController: AbortController | null

  addUserMessage: (content: string) => void
  startAssistantMessage: () => void
  appendAssistantText: (text: string) => void
  addToolCall: (toolCall: ToolCallInfo) => void
  addToolResult: (toolResult: ToolResultInfo) => void
  setError: (error: string | null) => void
  setLoading: (loading: boolean) => void
  setAbortController: (controller: AbortController | null) => void
  cancelRequest: () => void
  clearMessages: () => void
  getMessagesForApi: () => ApiMessage[]
}

let messageCounter = 0

export const useChatStore = create<ChatState>()((set, get) => ({
  messages: [],
  isLoading: false,
  error: null,
  abortController: null,

  addUserMessage: (content: string) => {
    const msg: ChatMessage = {
      id: `msg-${++messageCounter}`,
      role: 'user',
      content,
      timestamp: Date.now(),
    }
    set((state) => ({
      messages: [...state.messages, msg],
      error: null,
    }))
  },

  startAssistantMessage: () => {
    const msg: ChatMessage = {
      id: `msg-${++messageCounter}`,
      role: 'assistant',
      content: '',
      toolCalls: [],
      toolResults: [],
      timestamp: Date.now(),
    }
    set((state) => ({
      messages: [...state.messages, msg],
    }))
  },

  appendAssistantText: (text: string) => {
    set((state) => {
      const msgs = [...state.messages]
      const last = msgs[msgs.length - 1]
      if (last && last.role === 'assistant') {
        msgs[msgs.length - 1] = { ...last, content: last.content + text }
      }
      return { messages: msgs }
    })
  },

  addToolCall: (toolCall: ToolCallInfo) => {
    set((state) => {
      const msgs = [...state.messages]
      const last = msgs[msgs.length - 1]
      if (last && last.role === 'assistant') {
        msgs[msgs.length - 1] = {
          ...last,
          toolCalls: [...(last.toolCalls || []), toolCall],
        }
      }
      return { messages: msgs }
    })
  },

  addToolResult: (toolResult: ToolResultInfo) => {
    set((state) => {
      const msgs = [...state.messages]
      const last = msgs[msgs.length - 1]
      if (last && last.role === 'assistant') {
        msgs[msgs.length - 1] = {
          ...last,
          toolResults: [...(last.toolResults || []), toolResult],
        }
      }
      return { messages: msgs }
    })
  },

  setError: (error: string | null) => set({ error }),
  setLoading: (loading: boolean) => set({ isLoading: loading }),
  setAbortController: (controller: AbortController | null) => set({ abortController: controller }),

  cancelRequest: () => {
    const { abortController } = get()
    if (abortController) {
      abortController.abort()
      set({ abortController: null, isLoading: false })
    }
  },

  clearMessages: () => set({ messages: [], error: null }),

  getMessagesForApi: (): ApiMessage[] => {
    return get().messages.map((msg) => ({
      role: msg.role,
      content: msg.content,
    }))
  },
}))
