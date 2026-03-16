import { useState } from 'react'
import type { ToolCallInfo, ToolResultInfo } from '../../types/chat'
import { MessageTable } from './MessageTable'
import { SessionStatusCard } from './SessionStatusCard'

interface ToolCallCardProps {
  toolCall: ToolCallInfo
  toolResult?: ToolResultInfo
}

const TOOL_LABELS: Record<string, string> = {
  list_streams: 'List Streams',
  query_fix_messages: 'Query Messages',
  get_session_status: 'Session Status',
}

export function ToolCallCard({ toolCall, toolResult }: ToolCallCardProps) {
  const [expanded, setExpanded] = useState(false)
  const label = TOOL_LABELS[toolCall.tool] || toolCall.tool
  const isLoading = !toolResult
  const isError = toolResult?.isError

  return (
    <div className="border border-gray-700 rounded-lg overflow-hidden bg-gray-850">
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-3 py-2 bg-gray-800
                   hover:bg-gray-750 transition-colors text-left"
      >
        <div className="flex items-center gap-2">
          {isLoading ? (
            <span className="inline-block w-4 h-4 border-2 border-blue-400 border-t-transparent
                           rounded-full animate-spin" />
          ) : isError ? (
            <span className="text-red-400 text-sm">!</span>
          ) : (
            <span className="text-green-400 text-sm">&#10003;</span>
          )}
          <span className="text-sm font-medium text-gray-300">{label}</span>
        </div>
        <span className="text-gray-500 text-xs">
          {expanded ? '▲' : '▼'}
        </span>
      </button>

      {/* Body */}
      {expanded && (
        <div className="border-t border-gray-700 px-3 py-2 space-y-2">
          {/* Args */}
          <div>
            <div className="text-xs text-gray-500 mb-1">Arguments</div>
            <pre className="text-xs bg-gray-900 rounded p-2 text-gray-300 overflow-x-auto max-h-32">
              {JSON.stringify(toolCall.args, null, 2)}
            </pre>
          </div>

          {/* Result */}
          {toolResult && (
            <div>
              <div className="text-xs text-gray-500 mb-1">Result</div>
              {renderToolResult(toolCall.tool, toolResult)}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function renderToolResult(toolName: string, result: ToolResultInfo) {
  const data = result.data as Record<string, unknown>

  if (result.isError) {
    return (
      <pre className="text-xs bg-red-900/20 border border-red-800 rounded p-2 text-red-300 overflow-x-auto max-h-48">
        {JSON.stringify(data, null, 2)}
      </pre>
    )
  }

  // Render query results as a table
  if (toolName === 'query_fix_messages' && data?.messages && Array.isArray(data.messages)) {
    return <MessageTable messages={data.messages as Record<string, string>[]} />
  }

  // Render session status as a card
  if (toolName === 'get_session_status' && (data?.sessions || data?.session)) {
    return <SessionStatusCard data={data} />
  }

  // Default: JSON
  return (
    <pre className="text-xs bg-gray-900 rounded p-2 text-gray-300 overflow-x-auto max-h-64">
      {JSON.stringify(data, null, 2)}
    </pre>
  )
}
