import { useEffect, useRef, useCallback } from 'react'
import { useChatStore } from '../store/chatStore'
import { streamChat } from '../api/chat'
import { ChatInput } from '../components/chat/ChatInput'
import { ChatMessage } from '../components/chat/ChatMessage'
import type { SseEvent } from '../types/chat'

const EXAMPLE_QUESTIONS = [
  'What streams are available?',
  'Show me recent orders for the last 2 hours',
  'Any sequence gaps today?',
  'What is the session status?',
]

export function Chat() {
  const {
    messages,
    isLoading,
    error,
    addUserMessage,
    startAssistantMessage,
    appendAssistantText,
    addToolCall,
    addToolResult,
    setError,
    setLoading,
    setAbortController,
    cancelRequest,
    clearMessages,
    getMessagesForApi,
  } = useChatStore()

  const scrollRef = useRef<HTMLDivElement>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages])

  const handleSend = useCallback(async (content: string) => {
    addUserMessage(content)
    setLoading(true)
    startAssistantMessage()

    const controller = new AbortController()
    setAbortController(controller)

    try {
      const apiMessages = getMessagesForApi()
      // Remove the empty assistant message we just added from the API payload
      const messagesToSend = apiMessages.slice(0, -1)

      await streamChat(
        messagesToSend,
        (event: SseEvent) => {
          switch (event.type) {
            case 'text':
              appendAssistantText((event.data as { content: string }).content)
              break
            case 'tool_call':
              addToolCall({
                id: event.data.id as string,
                tool: event.data.tool as string,
                args: event.data.args as Record<string, unknown>,
              })
              break
            case 'tool_result':
              addToolResult({
                id: event.data.id as string,
                tool: event.data.tool as string,
                data: event.data.data,
                isError: event.data.is_error as boolean,
              })
              break
            case 'error':
              setError((event.data as { message: string }).message)
              break
          }
        },
        controller.signal
      )
    } catch (err) {
      if ((err as Error).name !== 'AbortError') {
        setError((err as Error).message)
      }
    } finally {
      setLoading(false)
      setAbortController(null)
    }
  }, [addUserMessage, setLoading, startAssistantMessage, setAbortController,
      getMessagesForApi, appendAssistantText, addToolCall, addToolResult, setError])

  const isEmpty = messages.length === 0

  return (
    <div className="flex flex-col h-[calc(100vh-8rem)] -my-8 -mx-4 sm:-mx-6 lg:-mx-8">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-gray-700 bg-gray-800">
        <h1 className="text-lg font-semibold text-white">FIX Message Chat</h1>
        {messages.length > 0 && (
          <button
            onClick={clearMessages}
            className="text-sm text-gray-400 hover:text-white transition-colors"
          >
            Clear
          </button>
        )}
      </div>

      {/* Messages area */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-4">
        {isEmpty ? (
          <WelcomeScreen onExampleClick={handleSend} />
        ) : (
          <div className="max-w-3xl mx-auto">
            {messages.map((msg) => (
              <ChatMessage key={msg.id} message={msg} />
            ))}
            {error && (
              <div className="mb-4 px-4 py-2 bg-red-900/20 border border-red-800 rounded-lg text-red-300 text-sm">
                {error}
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
        )}
      </div>

      {/* Input */}
      <ChatInput
        onSend={handleSend}
        disabled={isLoading}
        onCancel={isLoading ? cancelRequest : undefined}
      />
    </div>
  )
}

function WelcomeScreen({ onExampleClick }: { onExampleClick: (q: string) => void }) {
  return (
    <div className="flex flex-col items-center justify-center h-full text-center">
      <h2 className="text-2xl font-bold text-white mb-2">FIX Message Analyst</h2>
      <p className="text-gray-400 mb-8 max-w-md">
        Ask questions about FIX messages, sessions, and order flow.
        I can query the persistence store and analyze results for you.
      </p>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 max-w-lg">
        {EXAMPLE_QUESTIONS.map((q) => (
          <button
            key={q}
            onClick={() => onExampleClick(q)}
            className="text-left px-4 py-3 bg-gray-800 border border-gray-700 rounded-lg
                       text-sm text-gray-300 hover:bg-gray-750 hover:border-gray-600
                       transition-colors"
          >
            {q}
          </button>
        ))}
      </div>
    </div>
  )
}
