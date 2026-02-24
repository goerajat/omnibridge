@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM Aeron Remote Persistence Store Management Script (Windows)
REM =============================================================================
REM Usage: aeron-remote-store.bat {start|stop|status|restart} [options]
REM =============================================================================

set SCRIPT_DIR=%~dp0
set BASE_DIR=%SCRIPT_DIR%..
set LIB_DIR=%BASE_DIR%\lib
set CONF_DIR=%BASE_DIR%\conf
set LOG_DIR=%BASE_DIR%\logs
set DATA_DIR=%BASE_DIR%\data
set PID_FILE=%BASE_DIR%\aeron-remote-store.pid

REM Default values
set CONFIG_FILE=%CONF_DIR%\aeron-remote-store.conf
set DEBUG_MODE=false

REM Java options
set JAVA_OPTS=-Xms1g -Xmx2g
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseZGC -XX:+AlwaysPreTouch
set JAVA_OPTS=%JAVA_OPTS% -Djava.net.preferIPv4Stack=true
set JAVA_OPTS=%JAVA_OPTS% -Dlogback.configurationFile=%CONF_DIR%\logback.xml

REM Chronicle Queue / Aeron --add-opens and --add-exports for Java 17+
set JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.lang=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.lang.reflect=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.io=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/java.nio=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/sun.nio.ch=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-opens java.base/jdk.internal.misc=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-exports java.base/jdk.internal.ref=ALL-UNNAMED
set JAVA_OPTS=%JAVA_OPTS% --add-exports java.base/sun.nio.ch=ALL-UNNAMED

REM Parse command
set COMMAND=%1
shift

:parse_args
if "%1"=="" goto end_parse
if "%1"=="-c" (
    set CONFIG_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--config" (
    set CONFIG_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-d" (
    set DEBUG_MODE=true
    shift
    goto parse_args
)
if "%1"=="--debug" (
    set DEBUG_MODE=true
    shift
    goto parse_args
)
if "%1"=="-h" goto show_help
if "%1"=="--help" goto show_help
shift
goto parse_args
:end_parse

if "%COMMAND%"=="start" goto start_store
if "%COMMAND%"=="stop" goto stop_store
if "%COMMAND%"=="status" goto show_status
if "%COMMAND%"=="restart" goto restart_store
goto show_help

:show_help
echo Aeron Remote Persistence Store Management Script
echo.
echo Usage: %~nx0 {start^|stop^|status^|restart} [options]
echo.
echo Commands:
echo   start     Start the remote store
echo   stop      Stop the remote store
echo   status    Check if the remote store is running
echo   restart   Restart the remote store
echo.
echo Options:
echo   -c, --config   Path to configuration file (default: conf\aeron-remote-store.conf)
echo   -d, --debug    Enable debug logging
echo   -h, --help     Show this help message
goto :eof

:start_store
echo Starting Aeron Remote Store...

REM Create directories if needed
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

REM Build classpath
set CLASSPATH=%CONF_DIR%;%LIB_DIR%\aeron-remote-store.jar

REM Enable debug if requested
if "%DEBUG_MODE%"=="true" set JAVA_OPTS=%JAVA_OPTS% -Dlogging.level.root=DEBUG

REM Start the application
start "Aeron Remote Store" /B java %JAVA_OPTS% -cp "%CLASSPATH%" ^
    com.omnibridge.persistence.aeron.AeronRemoteStoreMain ^
    -c "%CONFIG_FILE%" > "%LOG_DIR%\console.log" 2>&1

echo Aeron Remote Store started
echo Config: %CONFIG_FILE%
echo Logs: %LOG_DIR%
goto :eof

:stop_store
echo Stopping Aeron Remote Store...
REM Find and kill Java process running AeronRemoteStoreMain
for /f "tokens=2" %%a in ('wmic process where "commandline like '%%AeronRemoteStoreMain%%'" get processid ^| findstr /r "[0-9]"') do (
    taskkill /PID %%a /F >nul 2>&1
)
echo Aeron Remote Store stopped
goto :eof

:show_status
for /f "tokens=2" %%a in ('wmic process where "commandline like '%%AeronRemoteStoreMain%%'" get processid ^| findstr /r "[0-9]"') do (
    echo Aeron Remote Store is running (PID: %%a)
    goto :eof
)
echo Aeron Remote Store is not running
goto :eof

:restart_store
call :stop_store
timeout /t 2 /nobreak >nul
call :start_store
goto :eof
