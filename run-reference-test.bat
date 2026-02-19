@echo off
setlocal EnableDelayedExpansion

REM ==========================================================================
REM Reference Test Script
REM Runs sample acceptor, then reference tester (QuickFIX/J) against it
REM Usage: run-reference-test.bat [test-names]
REM   test-names: comma-separated test names or 'all' (default: all)
REM ==========================================================================

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

set ACCEPTOR_JAR=apps\fix-samples\target\fix-samples-1.0.0-SNAPSHOT-all.jar
set TESTER_JAR=protocols\fix\reference-tester\target\reference-tester-1.0.0-SNAPSHOT-all.jar
set CONFIG_DIR=apps\fix-samples\src\main\resources

REM Check if jars exist
if not exist "%ACCEPTOR_JAR%" (
    echo ERROR: Acceptor jar not found at %ACCEPTOR_JAR%
    echo Please run 'mvn install -DskipTests' first.
    exit /b 1
)
if not exist "%TESTER_JAR%" (
    echo ERROR: Reference tester jar not found at %TESTER_JAR%
    echo Please run 'mvn install -DskipTests' first.
    exit /b 1
)

REM Default parameters
set TESTS=all
if not "%~1"=="" set TESTS=%~1

echo ==========================================================================
echo FIX Reference Test Suite
echo ==========================================================================
echo Acceptor JAR: %ACCEPTOR_JAR%
echo Tester JAR: %TESTER_JAR%
echo Tests: %TESTS%
echo ==========================================================================

REM JVM options for Chronicle Queue (Java 17+)
set CHRONICLE_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED

REM Start acceptor in background
echo.
echo Starting FIX Acceptor in background...
start "" /b java %CHRONICLE_OPTS% -jar "%ACCEPTOR_JAR%" -c "%CONFIG_DIR%\acceptor.conf" > acceptor-ref-test.log 2>&1

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
    echo Check acceptor-ref-test.log for details
    type acceptor-ref-test.log
    goto :cleanup
)

REM Small delay to ensure acceptor is fully initialized
powershell -NoProfile -Command "Start-Sleep -Seconds 2" >nul 2>&1

REM Run reference tester
echo.
echo Running Reference Tester (QuickFIX/J)...
echo ==========================================================================
echo.
java %CHRONICLE_OPTS% -jar "%TESTER_JAR%" test --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests %TESTS%

set TEST_EXIT_CODE=%ERRORLEVEL%

:cleanup
REM Cleanup: Kill acceptor process
echo.
echo Stopping acceptor...

REM Find and kill Java process listening on port 9876
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":9876.*LISTENING" 2^>nul') do (
    taskkill /PID %%a /F >nul 2>&1
)

echo ==========================================================================
echo.
echo SUMMARY
echo ==========================================================================
if defined TEST_EXIT_CODE (
    if !TEST_EXIT_CODE!==0 (
        echo Result: ALL TESTS PASSED
    ) else (
        echo Result: SOME TESTS FAILED
    )
) else (
    echo Result: TEST ABORTED
    set TEST_EXIT_CODE=1
)
echo ==========================================================================
echo Acceptor log: acceptor-ref-test.log
echo ==========================================================================

endlocal
exit /b %TEST_EXIT_CODE%
