@echo off
REM Run latency test: launches acceptor in latency mode, then initiator in latency mode
REM Usage: run-latency-test.bat [initiator-options]
REM
REM Default test configuration:
REM   Acceptor: port 9876, latency mode enabled, 100% fill rate
REM   Initiator: connects to localhost:9876, latency mode enabled
REM
REM Example:
REM   run-latency-test.bat
REM   run-latency-test.bat --warmup-orders 5000 --test-orders 500 --rate 200

setlocal EnableDelayedExpansion

set SCRIPT_DIR=%~dp0
set TARGET_DIR=%SCRIPT_DIR%fix-sample-apps\target

echo ============================================================
echo FIX Engine Latency Test
echo ============================================================
echo.

REM Check if compiled
if not exist "%TARGET_DIR%\classes" (
    echo Project not compiled. Running mvn compile...
    cd /d "%SCRIPT_DIR%"
    call mvn compile -q
    if errorlevel 1 (
        echo Build failed!
        exit /b 1
    )
)

REM Build classpath
set CP=%TARGET_DIR%\classes
set CP=!CP!;%SCRIPT_DIR%fix-engine\target\classes
set CP=!CP!;%SCRIPT_DIR%fix-message\target\classes
set CP=!CP!;%SCRIPT_DIR%fix-network-io\target\classes
set CP=!CP!;%SCRIPT_DIR%fix-persistence\target\classes

REM Add Maven dependencies from local repo
set M2_REPO=%USERPROFILE%\.m2\repository
set CP=!CP!;%M2_REPO%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar
set CP=!CP!;%M2_REPO%\ch\qos\logback\logback-classic\1.4.14\logback-classic-1.4.14.jar
set CP=!CP!;%M2_REPO%\ch\qos\logback\logback-core\1.4.14\logback-core-1.4.14.jar
set CP=!CP!;%M2_REPO%\info\picocli\picocli\4.7.5\picocli-4.7.5.jar
set CP=!CP!;%M2_REPO%\com\fasterxml\jackson\core\jackson-databind\2.16.1\jackson-databind-2.16.1.jar
set CP=!CP!;%M2_REPO%\com\fasterxml\jackson\core\jackson-core\2.16.1\jackson-core-2.16.1.jar
set CP=!CP!;%M2_REPO%\com\fasterxml\jackson\core\jackson-annotations\2.16.1\jackson-annotations-2.16.1.jar
set CP=!CP!;%M2_REPO%\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar
set CP=!CP!;%M2_REPO%\net\java\dev\jna\jna-platform\5.14.0\jna-platform-5.14.0.jar
set CP=!CP!;%M2_REPO%\org\agrona\agrona\1.20.0\agrona-1.20.0.jar

REM Generate unique window title for acceptor
set ACCEPTOR_TITLE=FIX-Acceptor-Latency-%RANDOM%

REM Start acceptor in a new minimized window
echo Starting FIX Acceptor in latency mode (background)...
start "%ACCEPTOR_TITLE%" /min java -cp "%CP%" com.fixengine.samples.acceptor.SampleAcceptor --latency --fill-rate 1.0

REM Wait for acceptor to start listening
echo Waiting for acceptor to initialize...
timeout /t 3 /nobreak > nul

REM Verify acceptor started by checking if port 9876 is listening
netstat -an | findstr ":9876.*LISTENING" > nul 2>&1
if errorlevel 1 (
    echo WARNING: Could not verify acceptor is listening on port 9876
    echo Continuing anyway...
)

echo.
echo Starting FIX Initiator in latency mode...
echo ============================================================
echo.

REM Run initiator in foreground and capture exit code
java -cp "%CP%" com.fixengine.samples.initiator.SampleInitiator --latency %*
set INITIATOR_EXIT_CODE=!errorlevel!

echo.
echo ============================================================

REM Terminate the acceptor by window title
echo Stopping acceptor...
taskkill /fi "WINDOWTITLE eq %ACCEPTOR_TITLE%*" /f > nul 2>&1

REM Also clean up any orphaned acceptor processes
for /f "tokens=2" %%p in ('wmic process where "commandline like '%%SampleAcceptor%%--latency%%'" get processid 2^>nul ^| findstr /r "[0-9]"') do (
    taskkill /pid %%p /f > nul 2>&1
)

REM Report result
echo.
if !INITIATOR_EXIT_CODE! equ 0 (
    echo ============================================================
    echo LATENCY TEST COMPLETED SUCCESSFULLY
    echo ============================================================
) else (
    echo ============================================================
    echo LATENCY TEST FAILED ^(exit code: !INITIATOR_EXIT_CODE!^)
    echo ============================================================
)

endlocal & exit /b %INITIATOR_EXIT_CODE%
