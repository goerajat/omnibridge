@echo off
setlocal enabledelayedexpansion

set APP_NAME=omniview
set DEFAULT_PORT=3000
set JAR_FILE=omniview\target\omniview-1.0.0-SNAPSHOT.jar

:: Parse command line arguments
set PORT=%DEFAULT_PORT%
if not "%~1"=="" set PORT=%~1

:: Check if JAR exists
if not exist "%JAR_FILE%" (
    echo ERROR: JAR file not found: %JAR_FILE%
    echo Please build OmniView first with: cd omniview ^&^& mvn package
    exit /b 1
)

:: Check if already running
for /f "tokens=2" %%a in ('tasklist /fi "windowtitle eq %APP_NAME%" /fo list ^| find "PID:"') do (
    echo OmniView is already running with PID %%a
    exit /b 1
)

:: Also check PID file
set PID_FILE=%TEMP%\%APP_NAME%.pid
if exist "%PID_FILE%" (
    set /p OLD_PID=<"%PID_FILE%"
    tasklist /fi "pid eq !OLD_PID!" 2>nul | find "!OLD_PID!" >nul
    if not errorlevel 1 (
        echo OmniView is already running with PID !OLD_PID!
        exit /b 1
    )
    del "%PID_FILE%" 2>nul
)

echo Starting OmniView on port %PORT%...

:: Start the server in background
start "%APP_NAME%" /b java -Dport=%PORT% -jar "%JAR_FILE%"

:: Wait a moment for startup
timeout /t 2 /nobreak >nul

:: Verify it started
if exist "%PID_FILE%" (
    set /p NEW_PID=<"%PID_FILE%"
    echo OmniView started successfully with PID !NEW_PID!
    echo Access the application at: http://localhost:%PORT%
) else (
    echo OmniView started. Check logs for status.
    echo Access the application at: http://localhost:%PORT%
)

endlocal
