#!/bin/bash
# Optiq Acceptor startup script

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"

# Set classpath
CLASSPATH="$BASE_DIR/conf:$BASE_DIR/lib/*"

# JVM options
JAVA_OPTS="-Xms512m -Xmx1g"
JAVA_OPTS="$JAVA_OPTS -XX:+UseG1GC"
JAVA_OPTS="$JAVA_OPTS -Dlogback.configurationFile=$BASE_DIR/conf/logback.xml"

# Run acceptor
exec java $JAVA_OPTS -cp "$CLASSPATH" com.omnibridge.optiq.samples.SampleOptiqAcceptor "$@"
