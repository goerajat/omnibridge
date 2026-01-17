#!/bin/bash
# Run the Sample FIX Acceptor using Maven
# Usage: ./run-acceptor-mvn.sh [options]
# All options are passed to the application

cd "$(dirname "${BASH_SOURCE[0]}")"
mvn -pl fix-sample-apps exec:java -Dexec.mainClass="com.fixengine.samples.acceptor.SampleAcceptor" -Dexec.args="$*" -q
