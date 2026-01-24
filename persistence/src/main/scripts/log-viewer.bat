@echo off
REM ==========================================================================
REM Log Viewer - Protocol-agnostic message log viewer
REM ==========================================================================
REM
REM Usage: log-viewer.bat <command> [options]
REM
REM Commands:
REM   list                    List available log streams
REM   replay <stream>         Replay messages from a stream
REM   tail <stream>           Follow messages in real-time
REM   search <stream> <query> Search for messages matching query
REM   stats <stream>          Show statistics for a stream
REM   export <stream> <file>  Export messages to JSON file
REM
REM Options:
REM   --log-dir <dir>         Log directory (default: .\logs)
REM   --start <timestamp>     Start time filter (ISO-8601 or epoch millis)
REM   --end <timestamp>       End time filter
REM   --direction <in|out>    Filter by direction
REM   --msg-type <type>       Filter by message type
REM   --limit <n>             Limit number of messages
REM   --format <text|json>    Output format (default: text)
REM   --verbose               Verbose output with all fields
REM   --decoder <class>       Decoder class for protocol-specific display
REM
REM Examples:
REM   log-viewer.bat list --log-dir .\fix-logs
REM   log-viewer.bat replay SENDER-TARGET --limit 100
REM   log-viewer.bat tail SENDER-TARGET --msg-type D
REM   log-viewer.bat search SENDER-TARGET "OrderQty=100"
REM   log-viewer.bat stats SENDER-TARGET
REM   log-viewer.bat export SENDER-TARGET orders.json --start 2024-01-01
REM
REM ==========================================================================

setlocal EnableDelayedExpansion

REM Determine script directory and application home
set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%.."
set "APP_HOME=%CD%"
popd
set "LIB_DIR=%APP_HOME%\lib"
set "CONF_DIR=%APP_HOME%\conf"

REM Build classpath dynamically from lib directory
set "CP="
set "JAR_COUNT=0"

if exist "%LIB_DIR%" (
    for %%F in ("%LIB_DIR%\*.jar") do (
        if "!CP!"=="" (
            set "CP=%%F"
        ) else (
            set "CP=!CP!;%%F"
        )
        set /a JAR_COUNT+=1
    )
)

REM Add conf directory to classpath for logback.xml
if exist "%CONF_DIR%" (
    if "!CP!"=="" (
        set "CP=%CONF_DIR%"
    ) else (
        set "CP=%CONF_DIR%;!CP!"
    )
)

REM Fallback: check for uber jar if no lib directory
if %JAR_COUNT% EQU 0 (
    for %%F in ("%APP_HOME%\*-all.jar" "%APP_HOME%\*-cli.jar") do (
        if exist "%%F" (
            set "CP=%%F"
            echo Using uber jar: %%F >&2
            goto :found_jar
        )
    )
    echo ERROR: No JAR files found in %LIB_DIR% >&2
    echo Please ensure the application is properly installed. >&2
    exit /b 1
) else (
    echo Loaded %JAR_COUNT% JAR files from %LIB_DIR% >&2
)
:found_jar

REM JVM options - lightweight for CLI tool
if "%JVM_OPTS%"=="" (
    set "JVM_OPTS=-Xms64m -Xmx256m"
)
set "JVM_OPTS=%JVM_OPTS% -XX:+UseG1GC"
set "JVM_OPTS=%JVM_OPTS% -XX:+DisableExplicitGC"
set "JVM_OPTS=%JVM_OPTS% -Dcom.sun.management.jmxremote=false"
set "JVM_OPTS=%JVM_OPTS% -Dorg.slf4j.simpleLogger.defaultLogLevel=WARN"

REM Show usage if no arguments
if "%~1"=="" (
    java %JVM_OPTS% -cp "%CP%" com.fixengine.persistence.cli.LogViewer --help
    exit /b 0
)

REM Run the log viewer
java %JVM_OPTS% -cp "%CP%" com.fixengine.persistence.cli.LogViewer %*

endlocal
