@echo off
setlocal enabledelayedexpansion

REM ==========================================================================
REM Aeron Replication Test Script (Windows)
REM Runs FIX acceptor with Aeron persistence + standalone AeronRemoteStore,
REM then runs reference/session tests, and validates both stores match.
REM
REM Usage: run-aeron-replication-test.bat [test-type] [test-names]
REM   test-type: reference (default), session, or both
REM   test-names: comma-separated test names or 'all' (default: all)
REM ==========================================================================

cd /d "%~dp0"

REM Find JARs
set ACCEPTOR_JAR=
for %%f in (apps\fix-samples\target\fix-samples-*-all.jar) do set ACCEPTOR_JAR=%%f
set REF_TESTER_JAR=
for %%f in (protocols\fix\reference-tester\target\reference-tester-*-all.jar) do set REF_TESTER_JAR=%%f
set SESSION_TESTER_JAR=
for %%f in (protocols\fix\session-tester\target\session-tester-*-all.jar) do set SESSION_TESTER_JAR=%%f
set CONFIG_DIR=apps\fix-samples\src\main\resources

REM Check if acceptor jar exists
if "%ACCEPTOR_JAR%"=="" (
    echo ERROR: Acceptor jar not found.
    echo Please run 'mvn install -DskipTests' first.
    exit /b 1
)

REM Default parameters
set TEST_TYPE=%1
if "%TEST_TYPE%"=="" set TEST_TYPE=reference
set TESTS=%2
if "%TESTS%"=="" set TESTS=all

echo ==========================================================================
echo Aeron Replication Test Suite
echo ==========================================================================
echo Acceptor JAR:     %ACCEPTOR_JAR%
echo Test Type:        %TEST_TYPE%
echo Tests:            %TESTS%
echo ==========================================================================

REM JVM options for Chronicle Queue (Java 17+)
set CHRONICLE_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED

REM Clean data directories
echo.
echo Cleaning data directories...
if exist data\local-cache rmdir /s /q data\local-cache
if exist data\remote-store rmdir /s /q data\remote-store
mkdir data\local-cache
mkdir data\remote-store

REM Step 1: Start AeronRemoteStore
echo.
echo Starting AeronRemoteStore...
start /b "AeronRemoteStore" java %CHRONICLE_OPTS% -cp "%ACCEPTOR_JAR%" com.omnibridge.persistence.aeron.AeronRemoteStoreMain -c "%CONFIG_DIR%\remote-store.conf" > remote-store.log 2>&1

REM Wait for remote store to initialize
timeout /t 3 /nobreak > nul

REM Step 2: Start FIX Acceptor with Aeron persistence
echo.
echo Starting FIX Acceptor with Aeron persistence...
start /b "FIXAcceptor" java %CHRONICLE_OPTS% -jar "%ACCEPTOR_JAR%" -c "%CONFIG_DIR%\acceptor-aeron.conf" > acceptor-aeron-test.log 2>&1

REM Wait for acceptor to start
echo Waiting for acceptor to start...
set ACCEPTOR_READY=0
for /l %%i in (1,1,30) do (
    if !ACCEPTOR_READY!==0 (
        powershell -Command "try { $c = New-Object Net.Sockets.TcpClient; $c.Connect('localhost', 9876); $c.Close(); exit 0 } catch { exit 1 }" > nul 2>&1
        if !errorlevel!==0 (
            set ACCEPTOR_READY=1
            echo Acceptor is ready on port 9876
        ) else (
            timeout /t 1 /nobreak > nul
        )
    )
)

if %ACCEPTOR_READY%==0 (
    echo ERROR: Acceptor failed to start within 30 seconds
    echo Check acceptor-aeron-test.log for details
    type acceptor-aeron-test.log
    goto :cleanup
)

REM Small delay for full initialization
timeout /t 2 /nobreak > nul

REM Step 3: Run tests
set TEST_EXIT_CODE=0

if "%TEST_TYPE%"=="reference" (
    if "%REF_TESTER_JAR%"=="" (
        echo WARNING: Reference tester jar not found, skipping.
        set TEST_EXIT_CODE=1
    ) else (
        echo.
        echo Running Reference Tests...
        echo ==========================================================================
        java %CHRONICLE_OPTS% -jar "%REF_TESTER_JAR%" test --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests %TESTS%
        set TEST_EXIT_CODE=!errorlevel!
    )
) else if "%TEST_TYPE%"=="session" (
    if "%SESSION_TESTER_JAR%"=="" (
        echo WARNING: Session tester jar not found, skipping.
        set TEST_EXIT_CODE=1
    ) else (
        echo.
        echo Running Session Tests...
        echo ==========================================================================
        java %CHRONICLE_OPTS% -jar "%SESSION_TESTER_JAR%" --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests %TESTS% --report-format text
        set TEST_EXIT_CODE=!errorlevel!
    )
) else if "%TEST_TYPE%"=="both" (
    if not "%REF_TESTER_JAR%"=="" (
        echo.
        echo Running Reference Tests...
        java %CHRONICLE_OPTS% -jar "%REF_TESTER_JAR%" test --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests %TESTS%
        if !errorlevel! neq 0 set TEST_EXIT_CODE=!errorlevel!
    )
    if not "%SESSION_TESTER_JAR%"=="" (
        echo.
        echo Running Session Tests...
        java %CHRONICLE_OPTS% -jar "%SESSION_TESTER_JAR%" --host localhost --port 9876 --sender CLIENT --target EXCHANGE --tests %TESTS% --report-format text
        if !errorlevel! neq 0 set TEST_EXIT_CODE=!errorlevel!
    )
) else (
    echo ERROR: Unknown test type '%TEST_TYPE%'. Use: reference, session, or both
    goto :cleanup
)

REM Step 4: Wait for replication to drain
echo.
echo Waiting for replication to drain...
timeout /t 5 /nobreak > nul

REM Step 5: Kill acceptor and remote store
echo Stopping processes...
taskkill /f /fi "WINDOWTITLE eq FIXAcceptor" > nul 2>&1
taskkill /f /fi "WINDOWTITLE eq AeronRemoteStore" > nul 2>&1
REM Also kill by finding java processes on specific ports
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":9876" ^| findstr "LISTENING" 2^>nul') do taskkill /f /pid %%a > nul 2>&1
timeout /t 2 /nobreak > nul

REM Step 6: Validate both stores
echo.
echo ==========================================================================
echo Store Validation
echo ==========================================================================

set VALIDATE_EXIT_CODE=0
java %CHRONICLE_OPTS% -cp "%ACCEPTOR_JAR%" com.omnibridge.persistence.aeron.StoreValidator --local data\local-cache --remote data\remote-store --publisher-id 1 --fix-validate --verbose
set VALIDATE_EXIT_CODE=!errorlevel!

REM Step 7: Report combined result
echo.
echo ==========================================================================
echo COMBINED RESULT
echo ==========================================================================
if %TEST_EXIT_CODE%==0 (echo Test result:       PASSED) else (echo Test result:       FAILED)
if %VALIDATE_EXIT_CODE%==0 (echo Validation result: PASSED) else (echo Validation result: FAILED)

set FINAL_EXIT_CODE=0
if %TEST_EXIT_CODE% neq 0 set FINAL_EXIT_CODE=1
if %VALIDATE_EXIT_CODE% neq 0 set FINAL_EXIT_CODE=1

if %FINAL_EXIT_CODE%==0 (echo OVERALL: PASSED) else (echo OVERALL: FAILED)
echo ==========================================================================
echo Logs: acceptor-aeron-test.log, remote-store.log
echo Data: data\local-cache, data\remote-store
echo ==========================================================================

:cleanup
exit /b %FINAL_EXIT_CODE%
