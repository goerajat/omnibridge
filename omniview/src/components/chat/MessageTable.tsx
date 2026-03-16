interface MessageTableProps {
  messages: Record<string, string>[]
}

const COLUMNS = [
  { key: 'timestamp', label: 'Time', width: 'w-28' },
  { key: 'direction', label: 'Dir', width: 'w-14' },
  { key: 'msg_type_name', label: 'Type', width: 'w-28' },
  { key: 'Symbol', label: 'Symbol', width: 'w-20' },
  { key: 'ClOrdID', label: 'ClOrdID', width: 'w-24' },
  { key: 'Side', label: 'Side', width: 'w-14' },
  { key: 'OrderQty', label: 'Qty', width: 'w-16' },
  { key: 'Price', label: 'Price', width: 'w-16' },
  { key: 'OrdStatus', label: 'Status', width: 'w-16' },
]

export function MessageTable({ messages }: MessageTableProps) {
  if (!messages.length) {
    return <div className="text-xs text-gray-500 p-2">No messages</div>
  }

  // Detect available columns
  const availableColumns = COLUMNS.filter((col) =>
    messages.some((m) => m[col.key] != null && m[col.key] !== '')
  )

  return (
    <div className="overflow-x-auto max-h-64">
      <table className="w-full text-xs">
        <thead className="bg-gray-900 sticky top-0">
          <tr>
            {availableColumns.map((col) => (
              <th
                key={col.key}
                className={`text-left text-gray-400 px-2 py-1 font-medium ${col.width}`}
              >
                {col.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {messages.map((msg, i) => (
            <tr key={i} className="border-t border-gray-800 hover:bg-gray-800/50">
              {availableColumns.map((col) => (
                <td key={col.key} className="px-2 py-1 text-gray-300 truncate">
                  {formatCell(col.key, msg[col.key])}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
      {messages.length >= 100 && (
        <div className="text-xs text-gray-500 px-2 py-1 text-center">
          Showing first 100 results. Ask to narrow the search for more specific results.
        </div>
      )}
    </div>
  )
}

function formatCell(key: string, value: string | undefined): string {
  if (!value) return '-'
  if (key === 'timestamp' && value.includes('T')) {
    // Show time portion only for readability
    const timePart = value.split('T')[1]?.replace('Z', '')
    return timePart || value
  }
  if (key === 'direction') {
    return value === 'INBOUND' ? 'IN' : value === 'OUTBOUND' ? 'OUT' : value
  }
  return value
}
