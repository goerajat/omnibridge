#!/bin/bash
# =============================================================================
# MCP Server Start Script
# =============================================================================
# Starts the MCP Server for FIX message querying.
#
# Usage: ./mcp-server.sh [options]
#
# Options are passed directly to the MCP Server CLI.
# See: java -jar lib/mcp-server.jar --help
# =============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Default JVM options
JVM_OPTS="${JVM_OPTS:--Xms256m -Xmx512m -XX:+UseZGC}"

# Chronicle Queue requires these --add-opens flags
CHRONICLE_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/java.io=ALL-UNNAMED \
--add-opens java.base/java.nio=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
--add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
--add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
--add-exports java.base/sun.nio.ch=ALL-UNNAMED"

exec java $JVM_OPTS $CHRONICLE_OPTS \
    -Dlogback.configurationFile="$SCRIPT_DIR/conf/logback.xml" \
    -jar "$SCRIPT_DIR/lib/mcp-server.jar" \
    "$@"
