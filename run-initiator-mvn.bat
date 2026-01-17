@echo off
REM Run the Sample FIX Initiator using Maven
REM Usage: run-initiator-mvn.bat [options]
REM All options are passed to the application

cd /d "%~dp0"
mvn -pl fix-sample-apps exec:java -Dexec.mainClass="com.fixengine.samples.initiator.SampleInitiator" -Dexec.args="%*" -q
