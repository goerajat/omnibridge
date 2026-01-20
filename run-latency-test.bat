@echo off
setlocal EnableDelayedExpansion

REM ==========================================================================
REM Latency Test Script for Development Environment
REM Runs acceptor in background, then initiator in latency mode
REM Usage: run-latency-test.bat [warmup-orders] [test-orders] [rate]
REM ==========================================================================

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

set UBER_JAR=fix-sample-apps\target\fix-sample-apps-1.0.0-SNAPSHOT-all.jar
set CONFIG_DIR=fix-sample-apps\src\main\resources

REM Check if uber jar exists
if not exist "%UBER_JAR%" (
    echo ERROR: Uber jar not found at %UBER_JAR%
    echo Please run 'mvn install -DskipTests' first.
    exit /b 1
)

REM Default parameters
set WARMUP_ORDERS=10000
set TEST_ORDERS=1000
set RATE=100

REM Parse command line arguments
if not "%~1"=="" set WARMUP_ORDERS=%~1
if not "%~2"=="" set TEST_ORDERS=%~2
if not "%~3"=="" set RATE=%~3

echo ==========================================================================
echo FIX Engine Latency Test
echo ==========================================================================
echo Uber JAR: %UBER_JAR%
echo Warmup Orders: %WARMUP_ORDERS%
echo Test Orders: %TEST_ORDERS%
echo Rate: %RATE% orders/sec
echo ==========================================================================

REM JVM options for low latency
set JVM_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch

REM Start acceptor in background (uber jar default main class is acceptor)
echo.
echo Starting FIX Acceptor in background...
start "" /b java %JVM_OPTS% -jar "%UBER_JAR%" -c "%CONFIG_DIR%\latency-acceptor.conf" --latency > acceptor.log 2>&1

REM Wait for acceptor to start
echo Waiting for acceptor to start...
set ACCEPTOR_READY=0
for /L %%i in (1,1,30) do (
    if !ACCEPTOR_READY!==0 (
        powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort 9876 -State Listen -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
        if !ERRORLEVEL!==0 (
            set ACCEPTOR_READY=1
            echo Acceptor is ready on port 9876
        ) else (
            powershell -NoProfile -Command "Start-Sleep -Milliseconds 500" >nul 2>&1
        )
    )
)

if !ACCEPTOR_READY!==0 (
    echo ERROR: Acceptor failed to start within 30 seconds
    echo Check acceptor.log for details
    type acceptor.log
    goto :cleanup
)

REM Small delay to ensure acceptor is fully initialized
powershell -NoProfile -Command "Start-Sleep -Seconds 2" >nul 2>&1

REM Run initiator in latency mode (use -cp to specify different main class)
echo.
echo Starting FIX Initiator in latency mode...
echo.
java %JVM_OPTS% -cp "%UBER_JAR%" com.fixengine.samples.initiator.SampleInitiator -c "%CONFIG_DIR%\latency-initiator.conf" --latency --warmup-orders %WARMUP_ORDERS% --test-orders %TEST_ORDERS% --rate %RATE%

set TEST_EXIT_CODE=%ERRORLEVEL%

:cleanup
REM Cleanup: Kill acceptor process
echo.
echo Stopping acceptor...

REM Find and kill Java process listening on port 9876
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":9876.*LISTENING" 2^>nul') do (
    echo Killing process %%a
    taskkill /PID %%a /F >nul 2>&1
)

echo ==========================================================================
if defined TEST_EXIT_CODE (
    if !TEST_EXIT_CODE!==0 (
        echo Latency test completed successfully
    ) else (
        echo Latency test completed with errors
    )
) else (
    echo Latency test aborted
    set TEST_EXIT_CODE=1
)
echo ==========================================================================
echo Acceptor log saved to: acceptor.log
echo ==========================================================================

endlocal
exit /b %TEST_EXIT_CODE%
