@echo off
setlocal enabledelayedexpansion

set APP_NAME=omniview
set PID_FILE=%TEMP%\%APP_NAME%.pid

echo Stopping OmniView...

:: Try to find PID from PID file
if exist "%PID_FILE%" (
    set /p PID=<"%PID_FILE%"
    echo Found PID file with PID: !PID!

    :: Check if process is running
    tasklist /fi "pid eq !PID!" 2>nul | find "!PID!" >nul
    if not errorlevel 1 (
        echo Terminating process !PID!...
        taskkill /pid !PID! /f >nul 2>&1
        if not errorlevel 1 (
            echo OmniView stopped successfully.
            del "%PID_FILE%" 2>nul
            exit /b 0
        )
    ) else (
        echo Process !PID! not found, cleaning up PID file.
        del "%PID_FILE%" 2>nul
    )
)

:: Fallback: Find by window title
for /f "tokens=2" %%a in ('tasklist /fi "windowtitle eq %APP_NAME%" /fo list 2^>nul ^| find "PID:"') do (
    echo Found OmniView process by window title with PID %%a
    taskkill /pid %%a /f >nul 2>&1
    if not errorlevel 1 (
        echo OmniView stopped successfully.
        exit /b 0
    )
)

:: Fallback: Find Java process running omniview jar
for /f "tokens=1" %%p in ('wmic process where "commandline like '%%omniview%%' and name='java.exe'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    echo Found OmniView Java process with PID %%p
    taskkill /pid %%p /f >nul 2>&1
    if not errorlevel 1 (
        echo OmniView stopped successfully.
        exit /b 0
    )
)

echo OmniView is not running or could not be found.
exit /b 1

endlocal
