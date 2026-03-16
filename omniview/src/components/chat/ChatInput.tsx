import { useState, useRef, useEffect } from 'react'

interface ChatInputProps {
  onSend: (message: string) => void
  disabled: boolean
  onCancel?: () => void
}

export function ChatInput({ onSend, disabled, onCancel }: ChatInputProps) {
  const [input, setInput] = useState('')
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (!disabled && textareaRef.current) {
      textareaRef.current.focus()
    }
  }, [disabled])

  const handleSubmit = () => {
    const trimmed = input.trim()
    if (trimmed && !disabled) {
      onSend(trimmed)
      setInput('')
      // Reset textarea height
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto'
      }
    }
  }

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSubmit()
    }
  }

  const handleInput = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setInput(e.target.value)
    // Auto-resize
    const el = e.target
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 200) + 'px'
  }

  return (
    <div className="border-t border-gray-700 bg-gray-800 p-4">
      <div className="max-w-3xl mx-auto flex gap-3 items-end">
        <textarea
          ref={textareaRef}
          value={input}
          onChange={handleInput}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          placeholder="Ask about FIX messages..."
          rows={1}
          className="flex-1 bg-gray-700 text-white rounded-lg px-4 py-3 resize-none
                     placeholder-gray-400 border border-gray-600 focus:border-blue-500
                     focus:outline-none focus:ring-1 focus:ring-blue-500
                     disabled:opacity-50 disabled:cursor-not-allowed"
        />
        {disabled && onCancel ? (
          <button
            onClick={onCancel}
            className="px-4 py-3 bg-red-600 text-white rounded-lg hover:bg-red-700
                       transition-colors font-medium"
          >
            Stop
          </button>
        ) : (
          <button
            onClick={handleSubmit}
            disabled={disabled || !input.trim()}
            className="px-4 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700
                       transition-colors font-medium disabled:opacity-50
                       disabled:cursor-not-allowed disabled:hover:bg-blue-600"
          >
            Send
          </button>
        )}
      </div>
    </div>
  )
}
