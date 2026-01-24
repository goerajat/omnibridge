#!/bin/bash
# FIX Acceptor (Exchange Simulator)
# Usage: fix-acceptor.sh [options]
#
# Options:
#   -p, --port <port>       Listen port (default: 9876)
#   -s, --sender <id>       SenderCompID (default: EXCHANGE)
#   -t, --target <id>       TargetCompID (default: CLIENT)
#   --heartbeat <seconds>   Heartbeat interval (default: 30)
#   --fill-rate <rate>      Fill probability 0.0-1.0 (default: 0.8)
#   --latency               Enable latency mode (minimal logging)

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

# Verbose GC logging (Java 11+ style)
GC_LOG_DIR="$APP_HOME/logs"
mkdir -p "$GC_LOG_DIR"
GC_LOG_FILE="$GC_LOG_DIR/acceptor-gc.log"
JVM_OPTS="$JVM_OPTS -Xlog:gc*,gc+age=trace,gc+heap=debug,safepoint:file=$GC_LOG_FILE:time,uptime,level,tags:filecount=5,filesize=10m"

# Additional low-latency tuning
JVM_OPTS="$JVM_OPTS -XX:+AlwaysPreTouch"
JVM_OPTS="$JVM_OPTS -XX:-UseBiasedLocking"
JVM_OPTS="$JVM_OPTS -XX:+UseNUMA"

# Run the acceptor
exec java $JVM_OPTS -cp "$CP" com.omnibridge.apps.fix.acceptor.SampleAcceptor "$@"
