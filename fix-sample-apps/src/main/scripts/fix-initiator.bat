@echo off
REM FIX Initiator (Trading Client)
REM Usage: fix-initiator.bat [options]

setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set APP_HOME=%SCRIPT_DIR%..
set LIB_DIR=%APP_HOME%\lib
set CONF_DIR=%APP_HOME%\conf

REM Build classpath from lib directory
set CP=
for %%f in ("%LIB_DIR%\*.jar") do (
    if "!CP!"=="" (
        set CP=%%f
    ) else (
        set CP=!CP!;%%f
    )
)

REM Add conf directory to classpath
if exist "%CONF_DIR%" set CP=%CONF_DIR%;!CP!

REM JVM options
set JVM_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10

java %JVM_OPTS% -cp "%CP%" com.fixengine.samples.initiator.SampleInitiator %*

endlocal
