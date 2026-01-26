@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM Exchange Simulator Management Script (Windows)
REM =============================================================================
REM Usage: exchange-simulator.bat {start|stop|status} [options]
REM =============================================================================

set SCRIPT_DIR=%~dp0
set BASE_DIR=%SCRIPT_DIR%..
set LIB_DIR=%BASE_DIR%\lib
set CONF_DIR=%BASE_DIR%\conf
set LOG_DIR=%BASE_DIR%\logs
set DATA_DIR=%BASE_DIR%\data
set PID_FILE=%BASE_DIR%\exchange-simulator.pid

REM Default values
set ADMIN_PORT=8080
set CONFIG_FILE=
set DEBUG_MODE=false

REM Java options
set JAVA_OPTS=-Xms512m -Xmx2g
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC
set JAVA_OPTS=%JAVA_OPTS% -Dlogback.configurationFile=%CONF_DIR%\logback.xml

REM Parse command
set COMMAND=%1
shift

:parse_args
if "%1"=="" goto end_parse
if "%1"=="-p" (
    set ADMIN_PORT=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--port" (
    set ADMIN_PORT=%2
    shift
    shift
    goto parse_args
)
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

if "%COMMAND%"=="start" goto start_simulator
if "%COMMAND%"=="stop" goto stop_simulator
if "%COMMAND%"=="status" goto show_status
if "%COMMAND%"=="restart" goto restart_simulator
goto show_help

:show_help
echo Exchange Simulator Management Script
echo.
echo Usage: %~nx0 {start^|stop^|status^|restart} [options]
echo.
echo Commands:
echo   start     Start the exchange simulator
echo   stop      Stop the exchange simulator
echo   status    Check if the simulator is running
echo   restart   Restart the exchange simulator
echo.
echo Options:
echo   -p, --port     Admin API port (default: 8080)
echo   -c, --config   Path to configuration file
echo   -d, --debug    Enable debug logging
echo   -h, --help     Show this help message
goto :eof

:start_simulator
echo Starting Exchange Simulator...

REM Create directories if needed
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

REM Build classpath
set CLASSPATH=%CONF_DIR%;%LIB_DIR%\exchange-simulator.jar

REM Add config file if specified
set APP_ARGS=
if not "%CONFIG_FILE%"=="" set APP_ARGS=%APP_ARGS% -Dconfig.file=%CONFIG_FILE%

REM Enable debug if requested
if "%DEBUG_MODE%"=="true" set JAVA_OPTS=%JAVA_OPTS% -Dlogging.level.root=DEBUG

REM Override admin port
set APP_ARGS=%APP_ARGS% -Dadmin.port=%ADMIN_PORT%

REM Start the application
start "Exchange Simulator" /B java %JAVA_OPTS% %APP_ARGS% -cp "%CLASSPATH%" ^
    com.omnibridge.simulator.ExchangeSimulator > "%LOG_DIR%\console.log" 2>&1

echo Exchange Simulator started
echo Admin API: http://localhost:%ADMIN_PORT%
echo Logs: %LOG_DIR%
goto :eof

:stop_simulator
echo Stopping Exchange Simulator...
REM Find and kill Java process running ExchangeSimulator
for /f "tokens=2" %%a in ('wmic process where "commandline like '%%ExchangeSimulator%%'" get processid ^| findstr /r "[0-9]"') do (
    taskkill /PID %%a /F >nul 2>&1
)
echo Exchange Simulator stopped
goto :eof

:show_status
for /f "tokens=2" %%a in ('wmic process where "commandline like '%%ExchangeSimulator%%'" get processid ^| findstr /r "[0-9]"') do (
    echo Exchange Simulator is running (PID: %%a)
    echo Admin API: http://localhost:%ADMIN_PORT%
    goto :eof
)
echo Exchange Simulator is not running
goto :eof

:restart_simulator
call :stop_simulator
timeout /t 2 /nobreak >nul
call :start_simulator
goto :eof
