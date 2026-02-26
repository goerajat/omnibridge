import { useState } from 'react'
import type { MessageEvent } from '../types'

interface MessageViewerProps {
  messages: MessageEvent[]
}

export function MessageViewer({ messages }: MessageViewerProps) {
  const [expandedIdx, setExpandedIdx] = useState<number | null>(null)

  if (messages.length === 0) {
    return <p className="text-gray-500 text-sm py-2">No messages recorded</p>
  }

  return (
    <div className="space-y-1">
      {messages.map((msg, idx) => (
        <div key={idx} className="border border-gray-700 rounded">
          <button
            onClick={() => setExpandedIdx(expandedIdx === idx ? null : idx)}
            className="w-full text-left px-3 py-2 hover:bg-gray-800/50 flex items-center gap-3 text-sm"
          >
            <span className="text-gray-500 text-xs font-mono w-20 shrink-0">
              {new Date(msg.timestamp).toLocaleTimeString()}
            </span>
            <span
              className={`text-lg ${
                msg.direction === 'SENT' ? 'text-blue-400' : 'text-green-400'
              }`}
            >
              {msg.direction === 'SENT' ? '\u2192' : '\u2190'}
            </span>
            <span
              className={`px-2 py-0.5 rounded text-xs font-medium ${
                msg.direction === 'SENT'
                  ? 'bg-blue-900 text-blue-300'
                  : 'bg-green-900 text-green-300'
              }`}
            >
              {msg.msgTypeName}
            </span>
            <span className="text-gray-500 text-xs ml-auto">
              {expandedIdx === idx ? '\u25B2' : '\u25BC'}
            </span>
          </button>

          {expandedIdx === idx && (
            <div className="px-3 pb-3 border-t border-gray-700">
              {Object.keys(msg.fields).length > 0 && (
                <table className="w-full text-xs mt-2">
                  <thead>
                    <tr className="text-gray-400 border-b border-gray-700">
                      <th className="text-left py-1 pr-4 font-medium">Field</th>
                      <th className="text-left py-1 font-medium">Value</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(msg.fields).map(([name, value]) => (
                      <tr key={name} className="border-b border-gray-800">
                        <td className="py-1 pr-4 text-gray-400">{name}</td>
                        <td className="py-1 text-white font-mono">{value}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              {msg.rawMessage && (
                <div className="mt-2">
                  <p className="text-gray-500 text-xs mb-1">Raw Message:</p>
                  <pre className="text-xs text-gray-300 bg-gray-900 p-2 rounded font-mono overflow-x-auto whitespace-pre-wrap break-all">
                    {msg.rawMessage}
                  </pre>
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  )
}
