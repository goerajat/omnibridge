@echo off
REM FIX Acceptor (Exchange Simulator)
REM Usage: fix-acceptor.bat [options]

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

REM Create logs directory
set LOG_DIR=%APP_HOME%\logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM JVM options
set JVM_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10

REM Verbose GC logging (Java 11+ style)
set GC_LOG_FILE=%LOG_DIR%\acceptor-gc.log
set JVM_OPTS=%JVM_OPTS% -Xlog:gc*,gc+age=trace,gc+heap=debug,safepoint:file=%GC_LOG_FILE%:time,uptime,level,tags:filecount=5,filesize=10m

REM Additional low-latency tuning
set JVM_OPTS=%JVM_OPTS% -XX:+AlwaysPreTouch -XX:-UseBiasedLocking

java %JVM_OPTS% -cp "%CP%" com.fixengine.apps.fix.acceptor.SampleAcceptor %*

endlocal
