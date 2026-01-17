#!/bin/bash
# Run the Sample FIX Initiator using Maven
# Usage: ./run-initiator-mvn.sh [options]
# All options are passed to the application

cd "$(dirname "${BASH_SOURCE[0]}")"
mvn -pl fix-sample-apps exec:java -Dexec.mainClass="com.fixengine.samples.initiator.SampleInitiator" -Dexec.args="$*" -q
