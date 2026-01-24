#!/bin/bash
# ==========================================================================
# Log Viewer - Protocol-agnostic message log viewer
# ==========================================================================
#
# Usage: log-viewer.sh <command> [options]
#
# Commands:
#   list                    List available log streams
#   replay <stream>         Replay messages from a stream
#   tail <stream>           Follow messages in real-time
#   search <stream> <query> Search for messages matching query
#   stats <stream>          Show statistics for a stream
#   export <stream> <file>  Export messages to JSON file
#
# Options:
#   --log-dir <dir>         Log directory (default: ./logs)
#   --start <timestamp>     Start time filter (ISO-8601 or epoch millis)
#   --end <timestamp>       End time filter
#   --direction <in|out>    Filter by direction
#   --msg-type <type>       Filter by message type
#   --limit <n>             Limit number of messages
#   --format <text|json>    Output format (default: text)
#   --verbose               Verbose output with all fields
#   --decoder <class>       Decoder class for protocol-specific display
#
# Examples:
#   log-viewer.sh list --log-dir ./fix-logs
#   log-viewer.sh replay SENDER-TARGET --limit 100
#   log-viewer.sh tail SENDER-TARGET --msg-type D
#   log-viewer.sh search SENDER-TARGET "OrderQty=100"
#   log-viewer.sh stats SENDER-TARGET
#   log-viewer.sh export SENDER-TARGET orders.json --start 2024-01-01
#
# ==========================================================================

set -e

# Determine script directory and application home
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
LIB_DIR="$APP_HOME/lib"
CONF_DIR="$APP_HOME/conf"

# Build classpath dynamically from lib directory
build_classpath() {
    local cp=""
    local jar_count=0

    if [ -d "$LIB_DIR" ]; then
        for jar in "$LIB_DIR"/*.jar; do
            if [ -f "$jar" ]; then
                if [ -z "$cp" ]; then
                    cp="$jar"
                else
                    cp="$cp:$jar"
                fi
                ((jar_count++)) || true
            fi
        done
    fi

    # Add conf directory to classpath for logback.xml
    if [ -d "$CONF_DIR" ]; then
        if [ -z "$cp" ]; then
            cp="$CONF_DIR"
        else
            cp="$CONF_DIR:$cp"
        fi
    fi

    # Fallback: check for uber jar if no lib directory
    if [ $jar_count -eq 0 ]; then
        # Check parent directory for uber jar
        local uber_jar=$(find "$APP_HOME" -maxdepth 1 -name "*-all.jar" -o -name "*-cli.jar" 2>/dev/null | head -1)
        if [ -n "$uber_jar" ] && [ -f "$uber_jar" ]; then
            cp="$uber_jar"
            echo "Using uber jar: $uber_jar" >&2
        else
            echo "ERROR: No JAR files found in $LIB_DIR" >&2
            echo "Please ensure the application is properly installed." >&2
            exit 1
        fi
    else
        echo "Loaded $jar_count JAR files from $LIB_DIR" >&2
    fi

    echo "$cp"
}

# Build classpath
CP=$(build_classpath)

# JVM options - lightweight for CLI tool
JVM_OPTS="${JVM_OPTS:--Xms64m -Xmx256m}"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"

# Disable JMX and other unnecessary services
JVM_OPTS="$JVM_OPTS -XX:+DisableExplicitGC"
JVM_OPTS="$JVM_OPTS -Dcom.sun.management.jmxremote=false"

# Reduce logging verbosity
JVM_OPTS="$JVM_OPTS -Dorg.slf4j.simpleLogger.defaultLogLevel=WARN"

# Show usage if no arguments
if [ $# -eq 0 ]; then
    exec java $JVM_OPTS -cp "$CP" com.fixengine.persistence.cli.LogViewer --help
fi

# Run the log viewer
exec java $JVM_OPTS -cp "$CP" com.fixengine.persistence.cli.LogViewer "$@"
