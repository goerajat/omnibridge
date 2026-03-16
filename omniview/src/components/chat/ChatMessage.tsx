import type { ChatMessage as ChatMessageType } from '../../types/chat'
import { ToolCallCard } from './ToolCallCard'

interface ChatMessageProps {
  message: ChatMessageType
}

export function ChatMessage({ message }: ChatMessageProps) {
  const isUser = message.role === 'user'

  return (
    <div className={`flex ${isUser ? 'justify-end' : 'justify-start'} mb-4`}>
      <div className={`max-w-[85%] ${isUser ? 'order-1' : 'order-1'}`}>
        {/* Text content */}
        {message.content && (
          <div
            className={`rounded-lg px-4 py-3 ${
              isUser
                ? 'bg-blue-600 text-white'
                : 'bg-gray-800 text-gray-100 border border-gray-700'
            }`}
          >
            <div className="whitespace-pre-wrap text-sm leading-relaxed">
              {message.content}
            </div>
          </div>
        )}

        {/* Tool calls and results */}
        {message.toolCalls && message.toolCalls.length > 0 && (
          <div className="mt-2 space-y-2">
            {message.toolCalls.map((tc) => {
              const result = message.toolResults?.find((tr) => tr.id === tc.id)
              return (
                <ToolCallCard
                  key={tc.id}
                  toolCall={tc}
                  toolResult={result}
                />
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
