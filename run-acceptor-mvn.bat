@echo off
REM Run the Sample FIX Acceptor using Maven
REM Usage: run-acceptor-mvn.bat [options]
REM All options are passed to the application

cd /d "%~dp0"
mvn -pl fix-sample-apps exec:java -Dexec.mainClass="com.fixengine.samples.acceptor.SampleAcceptor" -Dexec.args="%*" -q
