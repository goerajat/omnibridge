#!/bin/bash
# Run the Sample FIX Initiator (Trading Client)
# Usage: ./run-initiator.sh [options]
# Options:
#   -h, --host <host>       Target host (default: localhost)
#   -p, --port <port>       Target port (default: 9876)
#   -s, --sender <id>       SenderCompID (default: CLIENT)
#   -t, --target <id>       TargetCompID (default: EXCHANGE)
#   --heartbeat <seconds>   Heartbeat interval (default: 30)
#   --persistence <dir>     Persistence directory
#   --auto                  Auto-send sample orders
#   --count <n>             Number of orders in auto mode (default: 10)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$SCRIPT_DIR/fix-sample-apps/target"

# Check if compiled
if [ ! -d "$TARGET_DIR/classes" ]; then
    echo "Project not compiled. Running mvn compile..."
    cd "$SCRIPT_DIR"
    mvn compile -q
    if [ $? -ne 0 ]; then
        echo "Build failed!"
        exit 1
    fi
fi

# Build classpath
CP="$TARGET_DIR/classes"
CP="$CP:$SCRIPT_DIR/fix-engine/target/classes"
CP="$CP:$SCRIPT_DIR/fix-message/target/classes"
CP="$CP:$SCRIPT_DIR/fix-network-io/target/classes"
CP="$CP:$SCRIPT_DIR/fix-persistence/target/classes"

# Add Maven dependencies from local repo
M2_REPO="$HOME/.m2/repository"
CP="$CP:$M2_REPO/org/slf4j/slf4j-api/2.0.9/slf4j-api-2.0.9.jar"
CP="$CP:$M2_REPO/ch/qos/logback/logback-classic/1.4.14/logback-classic-1.4.14.jar"
CP="$CP:$M2_REPO/ch/qos/logback/logback-core/1.4.14/logback-core-1.4.14.jar"
CP="$CP:$M2_REPO/info/picocli/picocli/4.7.5/picocli-4.7.5.jar"
CP="$CP:$M2_REPO/com/fasterxml/jackson/core/jackson-databind/2.16.1/jackson-databind-2.16.1.jar"
CP="$CP:$M2_REPO/com/fasterxml/jackson/core/jackson-core/2.16.1/jackson-core-2.16.1.jar"
CP="$CP:$M2_REPO/com/fasterxml/jackson/core/jackson-annotations/2.16.1/jackson-annotations-2.16.1.jar"
CP="$CP:$M2_REPO/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar"
CP="$CP:$M2_REPO/net/java/dev/jna/jna-platform/5.14.0/jna-platform-5.14.0.jar"
CP="$CP:$M2_REPO/org/agrona/agrona/1.20.0/agrona-1.20.0.jar"

echo "Starting FIX Initiator (Trading Client)..."
echo

java -cp "$CP" com.fixengine.samples.initiator.SampleInitiator "$@"
