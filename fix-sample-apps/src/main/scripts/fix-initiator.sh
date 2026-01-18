#!/bin/bash
# FIX Initiator (Trading Client)
# Usage: fix-initiator.sh [options]
#
# Options:
#   -h, --host <host>       Target host (default: localhost)
#   -p, --port <port>       Target port (default: 9876)
#   -s, --sender <id>       SenderCompID (default: CLIENT)
#   -t, --target <id>       TargetCompID (default: EXCHANGE)
#   --heartbeat <seconds>   Heartbeat interval (default: 30)
#   --auto                  Auto-send sample orders
#   --count <n>             Number of orders in auto mode (default: 10)
#   --latency               Enable latency tracking mode
#   --warmup-orders <n>     Warmup orders for JIT (default: 10000)
#   --test-orders <n>       Test orders per run (default: 1000)
#   --rate <n>              Orders per second (default: 100, 0=unlimited)

set -e

# Determine script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(cd "$SCRIPT_DIR/.." && pwd)"
LIB_DIR="$APP_HOME/lib"
CONF_DIR="$APP_HOME/conf"

# Build classpath from lib directory
CP=""
for jar in "$LIB_DIR"/*.jar; do
    if [ -z "$CP" ]; then
        CP="$jar"
    else
        CP="$CP:$jar"
    fi
done

# Add conf directory to classpath for logback.xml
if [ -d "$CONF_DIR" ]; then
    CP="$CONF_DIR:$CP"
fi

# JVM options for low latency
JVM_OPTS="${JVM_OPTS:--Xms256m -Xmx512m}"
JVM_OPTS="$JVM_OPTS -XX:+UseG1GC"
JVM_OPTS="$JVM_OPTS -XX:MaxGCPauseMillis=10"

# Run the initiator
exec java $JVM_OPTS -cp "$CP" com.fixengine.samples.initiator.SampleInitiator "$@"
