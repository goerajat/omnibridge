@echo off
setlocal EnableDelayedExpansion

REM ==========================================================================
REM Session Test Script
REM Runs sample acceptor, then session tester (using our FIX engine) against it
REM Usage: run-session-test.bat [test-names] [report-format]
REM   test-names: comma-separated test names or 'all' (default: all)
REM   report-format: text, json, or html (default: text)
REM ==========================================================================

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

set ACCEPTOR_JAR=apps\fix-samples\target\fix-samples-1.0.0-SNAPSHOT-all.jar
set TESTER_JAR=fix\session-tester\target\session-tester-1.0.0-SNAPSHOT-all.jar
set CONFIG_DIR=apps\fix-samples\src\main\resources

REM Check if jars exist
if not exist "%ACCEPTOR_JAR%" (
    echo ERROR: Acceptor jar not found at %ACCEPTOR_JAR%
    echo Please run 'mvn install -DskipTests' first.
    exit /b 1
)
if not exist "%TESTER_JAR%" (
    echo ERROR: Session tester jar not found at %TESTER_JAR%
    echo Please run 'mvn install -DskipTests' first.
    exit /b 1
)

REM Default parameters
set TESTS=all
set REPORT_FORMAT=text
if not "%~1"=="" set TESTS=%~1
if not "%~2"=="" set REPORT_FORMAT=%~2

echo ==========================================================================
echo FIX Session Test Suite
echo ==========================================================================
echo Acceptor JAR: %ACCEPTOR_JAR%
echo Tester JAR: %TESTER_JAR%
echo Tests: %TESTS%
echo Report Format: %REPORT_FORMAT%
echo ==========================================================================

REM Start acceptor in background
echo.
echo Starting FIX Acceptor in background...
start "" /b java -jar "%ACCEPTOR_JAR%" -c "%CONFIG_DIR%\acceptor.conf" > acceptor-session-test.log 2>&1

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
    echo Check acceptor-session-test.log for details
    type acceptor-session-test.log
    goto :cleanup
)

REM Small delay to ensure acceptor is fully initialized
powershell -NoProfile -Command "Start-Sleep -Seconds 2" >nul 2>&1

REM Run session tester
echo.
echo Running Session Tester (FIX Engine)...
echo ==========================================================================
echo.
java -jar "%TESTER_JAR%" --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests %TESTS% --report-format %REPORT_FORMAT%

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
echo Acceptor log: acceptor-session-test.log
echo ==========================================================================

endlocal
exit /b %TEST_EXIT_CODE%
