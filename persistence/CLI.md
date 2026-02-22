Up# FIX Log Viewer CLI Documentation

The FIX Log Viewer (`fixlog`) is a command-line tool for viewing, searching, and exporting FIX message logs from the shared memory persistence store.

## Installation

Build the CLI tool:

```bash
cd fix-persistence
mvn clean package
```

This creates an executable JAR at `target/fix-persistence-1.0.0-SNAPSHOT-cli.jar`.

## Quick Start

```bash
# Set up an alias for convenience
alias fixlog="java -jar fix-persistence/target/fix-persistence-1.0.0-SNAPSHOT-cli.jar"

# List all sessions
fixlog list ./fix-logs

# Show messages for a session
fixlog show ./fix-logs --stream MySession

# Search for specific orders
fixlog search ./fix-logs --pattern "ClOrdID=12345"

# Show statistics
fixlog stats ./fix-logs --by-type

# Follow new messages in real-time
fixlog tail ./fix-logs --stream MySession
```

## Commands

### `fixlog list` - List Sessions

List all available sessions/streams in the log directory.

```bash
fixlog list <log-directory> [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-f, --format <format>` | Output format: `text` (default), `json` |

**Examples:**
```bash
# List all sessions in text format
fixlog list ./fix-logs

# List sessions in JSON format
fixlog list ./fix-logs --format json
```

**Sample Output (text):**
```
Available Sessions
==================

Session Name                      Entries    Last Inbound   Last Outbound
---------------------------------------------------------------------------
CLIENT-EXCHANGE                       245         seq=122         seq=123
MARKET-DATA-FEED                     1024         seq=512            -

Total: 2 sessions, 1269 entries
```

---

### `fixlog show` - Show Messages

Show FIX messages with optional filters.

```bash
fixlog show <log-directory> [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-s, --stream <name>` | Filter by session/stream name |
| `-d, --direction <dir>` | Filter by direction: `in`, `out`, `inbound`, `outbound` |
| `-t, --type <msgType>` | Filter by message type (e.g., `D`, `8`, `A`) |
| `--from-seq <num>` | Start sequence number (inclusive) |
| `--to-seq <num>` | End sequence number (inclusive) |
| `--from-time <time>` | Start time (format: `yyyy-MM-dd HH:mm:ss`) |
| `--to-time <time>` | End time (format: `yyyy-MM-dd HH:mm:ss`) |
| `--decode-types` | Decode message types to human-readable names |
| `-v, --verbose` | Show verbose output including raw message and metadata |
| `-f, --format <format>` | Output format: `text` (default), `json`, `csv`, `raw` |
| `-n, --count <num>` | Maximum number of messages to show |
| `--skip-admin` | Skip admin/session messages (Heartbeat, TestRequest, etc.) |
| `--timezone <zone>` | Timezone for timestamps (default: system timezone) |

**Examples:**
```bash
# Show all messages for a session
fixlog show ./fix-logs --stream CLIENT-EXCHANGE

# Show only NewOrderSingle (D) messages
fixlog show ./fix-logs --type D

# Show inbound ExecutionReports with human-readable types
fixlog show ./fix-logs --type 8 --direction in --decode-types

# Show messages from a specific time range
fixlog show ./fix-logs --from-time "2024-01-15 09:30:00" --to-time "2024-01-15 16:00:00"

# Show last 10 messages in verbose mode
fixlog show ./fix-logs -n 10 -v

# Output as CSV
fixlog show ./fix-logs --format csv > messages.csv

# Skip admin messages (Heartbeat, TestRequest, etc.)
fixlog show ./fix-logs --skip-admin
```

**Sample Output (text with --decode-types):**
```
====================================================================================================
FIX Log Viewer
====================================================================================================

[2024-01-15 09:30:45.123] OUT | SeqNum: 1      | Type: A (Logon)               | Stream: CLIENT-EXCHANGE
[2024-01-15 09:30:45.156] IN  | SeqNum: 1      | Type: A (Logon)               | Stream: CLIENT-EXCHANGE
[2024-01-15 09:30:46.234] OUT | SeqNum: 2      | Type: D (NewOrderSingle)      | Stream: CLIENT-EXCHANGE
[2024-01-15 09:30:46.289] IN  | SeqNum: 2      | Type: 8 (ExecutionReport)     | Stream: CLIENT-EXCHANGE

----------------------------------------------------------------------------------------------------
Total entries: 4
```

---

### `fixlog search` - Search Messages

Search for messages containing specific text or patterns (regex supported).

```bash
fixlog search <log-directory> --pattern <pattern> [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-p, --pattern <pattern>` | Search pattern (regex supported) **[Required]** |
| `-i, --ignore-case` | Case-insensitive search |
| `-n, --count <num>` | Maximum number of results |
| `-f, --format <format>` | Output format: `text` (default), `json`, `csv` |
| Plus all filtering options from `show` command |

**Examples:**
```bash
# Search for a specific ClOrdID
fixlog search ./fix-logs --pattern "ClOrdID=ORD-12345"

# Case-insensitive search
fixlog search ./fix-logs --pattern "symbol=AAPL" -i

# Search with regex
fixlog search ./fix-logs --pattern "Price=1[0-9]{2}\.[0-9]{2}"

# Search only in a specific session and direction
fixlog search ./fix-logs --pattern "ExecType=F" --stream CLIENT-EXCHANGE --direction in

# Limit results
fixlog search ./fix-logs --pattern "OrdStatus=2" -n 100
```

---

### `fixlog stats` - Show Statistics

Show statistics for FIX logs.

```bash
fixlog stats <log-directory> [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-s, --stream <name>` | Show stats for specific stream only |
| `-f, --format <format>` | Output format: `text` (default), `json` |
| `--by-type` | Show breakdown by message type |

**Examples:**
```bash
# Show basic statistics
fixlog stats ./fix-logs

# Show statistics with message type breakdown
fixlog stats ./fix-logs --by-type

# Show stats for specific session in JSON format
fixlog stats ./fix-logs --stream CLIENT-EXCHANGE --format json --by-type
```

**Sample Output (with --by-type):**
```
FIX Log Statistics
==================

Store path: /var/fix-logs
Total entries: 1269

Stream: CLIENT-EXCHANGE
--------------------------------------------------
  Total entries: 245
  Last inbound:  seq=122    at 2024-01-15 16:00:01.234
  Last outbound: seq=123    at 2024-01-15 16:00:01.567
  By direction:
    INBOUND   : 122
    OUTBOUND  : 123
  By message type:
    0     (Heartbeat): 48
    8     (ExecutionReport): 85
    A     (Logon): 2
    D     (NewOrderSingle): 100
    5     (Logout): 2
    F     (OrderCancelRequest): 8
```

---

### `fixlog tail` - Follow Messages

Follow new messages in real-time (like `tail -f`).

```bash
fixlog tail <log-directory> [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-n, --lines <num>` | Number of recent messages to show initially (default: 10) |
| `--skip-admin` | Skip admin/session messages |
| `--interval <ms>` | Poll interval in milliseconds (default: 100) |
| Plus filtering options: `--stream`, `--direction`, `--type`, `--decode-types`, `--verbose`, `--timezone` |

**Examples:**
```bash
# Follow all messages
fixlog tail ./fix-logs

# Follow a specific session
fixlog tail ./fix-logs --stream CLIENT-EXCHANGE

# Follow only execution reports
fixlog tail ./fix-logs --type 8 --decode-types

# Show last 20 messages initially, skip heartbeats
fixlog tail ./fix-logs -n 20 --skip-admin

# Follow with verbose output
fixlog tail ./fix-logs -v
```

Press `Ctrl+C` to exit tail mode.

---

### `fixlog export` - Export Logs

Export FIX logs to a file.

```bash
fixlog export <log-directory> --output <file> [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-o, --output <file>` | Output file path **[Required]** |
| `-f, --format <format>` | Output format: `csv` (default), `text`, `json`, `raw` |
| `--skip-admin` | Skip admin/session messages |
| `--append` | Append to existing file instead of overwriting |
| Plus all filtering options from `show` command |

**Examples:**
```bash
# Export all messages to CSV
fixlog export ./fix-logs --output messages.csv

# Export to JSON
fixlog export ./fix-logs --output messages.json --format json

# Export only execution reports with decoded types
fixlog export ./fix-logs --output executions.csv --type 8 --decode-types

# Export a time range
fixlog export ./fix-logs --output today.csv \
  --from-time "2024-01-15 09:30:00" --to-time "2024-01-15 16:00:00"

# Append new messages to existing file
fixlog export ./fix-logs --output daily.csv --append \
  --from-time "2024-01-15 16:00:00"
```

---

### `fixlog msgtypes` - List Message Types

List all known FIX message types.

```bash
fixlog msgtypes [options]
```

**Options:**
| Option | Description |
|--------|-------------|
| `-f, --format <format>` | Output format: `text` (default), `json` |
| `--admin-only` | Show only admin/session message types |
| `--app-only` | Show only application message types |

**Examples:**
```bash
# List all message types
fixlog msgtypes

# List only admin messages
fixlog msgtypes --admin-only

# List application messages in JSON format
fixlog msgtypes --app-only --format json
```

**Sample Output:**
```
FIX Message Types
=================

Code     Name                                Category
------------------------------------------------------------
0        Heartbeat                           Admin
1        TestRequest                         Admin
2        ResendRequest                       Admin
3        Reject                              Admin
4        SequenceReset                       Admin
5        Logout                              Admin
8        ExecutionReport                     Application
9        OrderCancelReject                   Application
A        Logon                               Admin
D        NewOrderSingle                      Application
F        OrderCancelRequest                  Application
G        OrderCancelReplaceRequest           Application
...

Total: 52 message types
```

---

## Output Formats

### Text Format (default)
Human-readable format with aligned columns, headers, and footers.

### JSON Format
Structured JSON output suitable for programmatic processing.

```json
{
  "totalEntries": 245,
  "generatedAt": "2024-01-15T16:30:00Z",
  "entries": [
    {
      "timestamp": 1705340445123,
      "timestampFormatted": "2024-01-15 09:30:45.123",
      "seqNum": 1,
      "direction": "OUTBOUND",
      "msgType": "A",
      "msgTypeName": "Logon",
      "streamName": "CLIENT-EXCHANGE"
    }
  ]
}
```

### CSV Format
Comma-separated values suitable for spreadsheet import.

```csv
Timestamp,SeqNum,Direction,MsgType,MsgTypeName,StreamName
2024-01-15 09:30:45.123,1,OUTBOUND,A,Logon,CLIENT-EXCHANGE
2024-01-15 09:30:45.156,1,INBOUND,A,Logon,CLIENT-EXCHANGE
```

### Raw Format
Raw FIX messages only, with SOH (0x01) replaced by `|`.

```
8=FIX.4.4|9=123|35=A|49=CLIENT|56=EXCHANGE|34=1|52=20240115-09:30:45.123|...
```

---

## Common Message Types

| Code | Name | Description |
|------|------|-------------|
| 0 | Heartbeat | Session keepalive |
| 1 | TestRequest | Request heartbeat response |
| 2 | ResendRequest | Request message resend |
| 3 | Reject | Session-level rejection |
| 4 | SequenceReset | Reset sequence numbers |
| 5 | Logout | Session logout |
| A | Logon | Session logon |
| D | NewOrderSingle | New order |
| F | OrderCancelRequest | Cancel order |
| G | OrderCancelReplaceRequest | Modify order |
| 8 | ExecutionReport | Execution/order status |
| 9 | OrderCancelReject | Cancel rejection |

Use `fixlog msgtypes` to see the complete list.

---

## Filtering Best Practices

1. **Filter by session first** - Use `--stream` to narrow down to specific sessions
2. **Use time ranges for large logs** - Use `--from-time` and `--to-time` for historical analysis
3. **Skip admin messages for trading analysis** - Use `--skip-admin` to focus on business messages
4. **Combine filters** - Multiple filters are combined with AND logic

**Example: Analyze trading activity**
```bash
fixlog show ./fix-logs \
  --stream CLIENT-EXCHANGE \
  --from-time "2024-01-15 09:30:00" \
  --to-time "2024-01-15 16:00:00" \
  --skip-admin \
  --decode-types \
  --format csv \
  > trading_activity.csv
```

---

## Troubleshooting

### "Log directory does not exist"
Ensure the path to the log directory is correct. The directory should contain `.fixlog` files.

### No sessions found
The log directory may be empty or not contain valid FIX log files. Check that the FIX engine has written logs to this directory.

### Memory issues with large logs
For very large log files, use filters to limit the data:
- Use `--from-time` and `--to-time` to limit time range
- Use `-n` to limit the number of results
- Use `--stream` to focus on specific sessions

### Timezone issues
By default, timestamps are displayed in the system timezone. Use `--timezone` to specify a different timezone:
```bash
fixlog show ./fix-logs --timezone America/New_York
fixlog show ./fix-logs --timezone UTC
```
