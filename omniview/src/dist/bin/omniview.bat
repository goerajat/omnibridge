@echo off
setlocal enabledelayedexpansion

rem OmniView - Protocol Engine Monitor
rem Usage: omniview.bat {start|stop|status|restart} [port]

rem Resolve script directory
set SCRIPT_DIR=%~dp0
set OMNIVIEW_HOME=%SCRIPT_DIR%..

rem Configuration
set APP_NAME=omniview
set JAR_FILE=%OMNIVIEW_HOME%\lib\omniview.jar
set PID_FILE=%OMNIVIEW_HOME%\omniview.pid
set LOG_FILE=%OMNIVIEW_HOME%\logs\omniview.log
set DATA_DIR=%OMNIVIEW_HOME%\data
set DEFAULT_PORT=3000

rem Java options
if "%JAVA_OPTS%"=="" set JAVA_OPTS=-Xms128m -Xmx512m

rem Ensure directories exist
if not exist "%OMNIVIEW_HOME%\logs" mkdir "%OMNIVIEW_HOME%\logs"
if not exist "%DATA_DIR%" mkdir "%DATA_DIR%"

rem Parse command
set COMMAND=%~1
set PORT=%~2
if "%PORT%"=="" set PORT=%DEFAULT_PORT%

if "%COMMAND%"=="start" goto :start
if "%COMMAND%"=="stop" goto :stop
if "%COMMAND%"=="status" goto :status
if "%COMMAND%"=="restart" goto :restart
goto :usage

:start
rem Check if already running
if exist "%PID_FILE%" (
    set /p OLD_PID=<"%PID_FILE%"
    tasklist /fi "pid eq !OLD_PID!" 2>nul | find "!OLD_PID!" >nul
    if not errorlevel 1 (
        echo OmniView is already running ^(PID: !OLD_PID!^)
        exit /b 1
    )
    del "%PID_FILE%" 2>nul
)

rem Check if JAR exists
if not exist "%JAR_FILE%" (
    echo ERROR: JAR file not found: %JAR_FILE%
    exit /b 1
)

echo Starting OmniView on port %PORT%...

rem Start the server in background
start /b "" java %JAVA_OPTS% -Dport=%PORT% -Domniview.data.dir="%DATA_DIR%" -jar "%JAR_FILE%" > "%LOG_FILE%" 2>&1

rem Get the PID (approximate - find java process running omniview)
timeout /t 2 /nobreak >nul

for /f "tokens=2" %%p in ('wmic process where "commandline like '%%omniview.jar%%'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    echo %%p > "%PID_FILE%"
    echo OmniView started successfully ^(PID: %%p^)
    echo Access the application at: http://localhost:%PORT%
    echo Logs: %LOG_FILE%
    exit /b 0
)

echo OmniView started. Check logs for status.
echo Access the application at: http://localhost:%PORT%
exit /b 0

:stop
if not exist "%PID_FILE%" (
    echo OmniView is not running
    exit /b 0
)

set /p PID=<"%PID_FILE%"
echo Stopping OmniView ^(PID: %PID%^)...

tasklist /fi "pid eq %PID%" 2>nul | find "%PID%" >nul
if errorlevel 1 (
    echo OmniView is not running ^(stale PID file^)
    del "%PID_FILE%" 2>nul
    exit /b 0
)

taskkill /pid %PID% /f >nul 2>&1
del "%PID_FILE%" 2>nul
echo OmniView stopped successfully
exit /b 0

:status
if not exist "%PID_FILE%" (
    echo OmniView is not running
    exit /b 1
)

set /p PID=<"%PID_FILE%"
tasklist /fi "pid eq %PID%" 2>nul | find "%PID%" >nul
if errorlevel 1 (
    echo OmniView is not running ^(stale PID file^)
    del "%PID_FILE%" 2>nul
    exit /b 1
)

echo OmniView is running ^(PID: %PID%^)
echo PID file: %PID_FILE%
echo Log file: %LOG_FILE%
exit /b 0

:restart
call :stop
timeout /t 1 /nobreak >nul
call :start
exit /b %errorlevel%

:usage
echo Usage: %~nx0 {start^|stop^|status^|restart} [port]
echo.
echo Commands:
echo   start [port]   Start OmniView (default port: %DEFAULT_PORT%)
echo   stop           Stop OmniView
echo   status         Check if OmniView is running
echo   restart [port] Restart OmniView
echo.
echo Environment variables:
echo   JAVA_OPTS      JVM options (default: -Xms128m -Xmx512m)
exit /b 1

endlocal
