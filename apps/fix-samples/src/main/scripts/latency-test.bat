@echo off
REM Run latency test: launches acceptor in background, then initiator in latency mode
REM Usage: latency-test.bat [initiator-options]
REM
REM Example:
REM   latency-test.bat
REM   latency-test.bat --warmup-orders 5000 --test-orders 500 --rate 200

setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set APP_HOME=%SCRIPT_DIR%..
set LIB_DIR=%APP_HOME%\lib
set CONF_DIR=%APP_HOME%\conf
set LOG_DIR=%APP_HOME%\logs

echo ============================================================
echo FIX Engine Latency Test
echo ============================================================
echo.

REM Create logs directory
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

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

REM JVM options for low latency
set JVM_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10
set JVM_OPTS=%JVM_OPTS% -XX:+AlwaysPreTouch
REM Agrona: disable bounds checking for UnsafeBuffer (improves tail latencies)
set JVM_OPTS=%JVM_OPTS% -Dagrona.disable.bounds.checks=true

REM Log files
set ACCEPTOR_LOG=%LOG_DIR%\latency-acceptor.log
set INITIATOR_LOG=%LOG_DIR%\latency-initiator.log

REM Generate unique identifier for acceptor window
set ACCEPTOR_TITLE=FIX-Latency-Acceptor-%RANDOM%

echo Starting FIX Acceptor in latency mode (background)...
echo Acceptor log: %ACCEPTOR_LOG%

REM Start acceptor in background with latency mode, redirect output to log
start "%ACCEPTOR_TITLE%" /min cmd /c "java %JVM_OPTS% -cp "%CP%" com.omnibridge.apps.fix.acceptor.SampleAcceptor -c latency-acceptor.conf --latency --fill-rate 1.0 > "%ACCEPTOR_LOG%" 2>&1"

REM Wait for acceptor to start
echo Waiting for acceptor to initialize...
timeout /t 3 /nobreak > nul

REM Check if acceptor is listening on port 9876
netstat -an | findstr ":9876.*LISTENING" > nul 2>&1
if errorlevel 1 (
    echo WARNING: Could not verify acceptor is listening on port 9876
    echo Check %ACCEPTOR_LOG% for errors
    echo Continuing anyway...
) else (
    echo Acceptor is listening on port 9876
)

echo.
echo ============================================================
echo Starting FIX Initiator in latency mode...
echo Initiator log: %INITIATOR_LOG%
echo ============================================================
echo.

REM Run initiator in foreground with latency mode, tee output to log and console
java %JVM_OPTS% -cp "%CP%" com.omnibridge.apps.fix.initiator.SampleInitiator -c latency-initiator.conf --latency %* 2>&1 | powershell -Command "$input | Tee-Object -FilePath '%INITIATOR_LOG%'"
set INITIATOR_EXIT_CODE=%errorlevel%

echo.
echo ============================================================

REM Give acceptor time to log final statistics
timeout /t 2 /nobreak > nul

REM Stop acceptor by window title
echo Stopping acceptor...
taskkill /fi "WINDOWTITLE eq %ACCEPTOR_TITLE%*" /f > nul 2>&1

REM Also clean up any orphaned acceptor processes
for /f "tokens=2" %%p in ('wmic process where "commandline like '%%SampleAcceptor%%--latency%%'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    taskkill /pid %%p /f > nul 2>&1
)

echo.
echo ============================================================
echo Latency Test Complete
echo ============================================================
echo.
echo Log files:
echo   Acceptor: %ACCEPTOR_LOG%
echo   Initiator: %INITIATOR_LOG%
echo.

REM Show acceptor statistics from log
echo Acceptor Statistics:
echo ------------------------------------------------------------
findstr /C:"Acceptor Statistics" /C:"Orders received" /C:"Cancel requests" /C:"Replace requests" /C:"Total messages" /C:"Execution reports" "%ACCEPTOR_LOG%" 2>nul
echo.

if %INITIATOR_EXIT_CODE% equ 0 (
    echo TEST RESULT: SUCCESS
) else (
    echo TEST RESULT: FAILED ^(exit code: %INITIATOR_EXIT_CODE%^)
)

endlocal
exit /b %INITIATOR_EXIT_CODE%
