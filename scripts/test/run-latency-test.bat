@echo off
setlocal EnableDelayedExpansion

REM ==========================================================================
REM Latency Test Script for Development Environment
REM Runs acceptor in background, then initiator in latency mode.
REM Optionally monitors acceptor GC/heap usage via jstat.
REM
REM Usage: run-latency-test.bat [warmup-orders] [test-orders] [rate] [--gc]
REM                              [--busy-spin] [--stages] [--persistence]
REM
REM The --gc flag enables jstat heap monitoring of the acceptor JVM during the
REM test run. A summary is printed at the end showing heap growth, GC counts,
REM and GC pause times.
REM --busy-spin    both event loops busy-spin (network.busy-spin-mode=true)
REM --stages       per-stage engine timestamps on both sides
REM                (fix-engine.stage-timing.enabled=true); the acceptor's
REM                report is printed from acceptor.log after the run
REM --persistence  both sessions write every message to their store
REM                (latency-*-persistence.conf: log-messages on, no tracing)
REM ==========================================================================

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%..\..\"

set UBER_JAR=
for /f "delims=" %%i in ('dir /b apps\fix-samples\target\fix-samples-*-all.jar 2^>nul') do set UBER_JAR=apps\fix-samples\target\%%i
set CONFIG_DIR=apps\fix-samples\src\main\resources

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
set GC_MONITOR=0
set GC_INTERVAL=2000
set BUSY_SPIN=0
set STAGES=0
set PERSISTENCE=0

REM Parse command line arguments: numbers are positional, flags may appear anywhere
set POS=0
for %%a in (%*) do (
    set "ARG=%%~a"
    if "!ARG:~0,2!"=="--" (
        if /i "!ARG!"=="--gc" set GC_MONITOR=1
        if /i "!ARG!"=="--busy-spin" set BUSY_SPIN=1
        if /i "!ARG!"=="--stages" set STAGES=1
        if /i "!ARG!"=="--persistence" set PERSISTENCE=1
    ) else (
        set /a POS+=1
        if !POS!==1 set WARMUP_ORDERS=!ARG!
        if !POS!==2 set TEST_ORDERS=!ARG!
        if !POS!==3 set RATE=!ARG!
    )
)

REM Config files and properties for both sides
set ACCEPTOR_CONFIGS=%CONFIG_DIR%\latency-acceptor.conf
set INITIATOR_CONFIGS=%CONFIG_DIR%\latency-initiator.conf
if !PERSISTENCE!==1 (
    set ACCEPTOR_CONFIGS=!ACCEPTOR_CONFIGS!,%CONFIG_DIR%\latency-acceptor-persistence.conf
    set INITIATOR_CONFIGS=!INITIATOR_CONFIGS!,%CONFIG_DIR%\latency-initiator-persistence.conf
)
set TEST_PROPS=
if !BUSY_SPIN!==1 set TEST_PROPS=!TEST_PROPS! -Dnetwork.busy-spin-mode=true
if !STAGES!==1 set TEST_PROPS=!TEST_PROPS! -Dfix-engine.stage-timing.enabled=true

echo ==========================================================================
echo FIX Engine Latency Test
echo ==========================================================================
echo Uber JAR: %UBER_JAR%
echo Warmup Orders: %WARMUP_ORDERS%
echo Test Orders: %TEST_ORDERS%
echo Rate: %RATE% orders/sec
if !GC_MONITOR!==1 (echo GC Monitoring: true) else (echo GC Monitoring: false)
if !BUSY_SPIN!==1 (echo Busy Spin: true) else (echo Busy Spin: false)
if !STAGES!==1 (echo Stage Timing: true) else (echo Stage Timing: false)
if !PERSISTENCE!==1 (echo Persistence: true) else (echo Persistence: false)
echo ==========================================================================

REM JVM options for low latency
REM -Dagrona.disable.bounds.checks=true removes bounds checking from UnsafeBuffer for max performance
set JVM_OPTS=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=10 -XX:+AlwaysPreTouch -Dagrona.disable.bounds.checks=true

REM Add GC logging when monitoring is enabled
set GC_LOG_OPTS=
if !GC_MONITOR!==1 (
    set GC_LOG_OPTS=-Xlog:gc*:file=acceptor-gc.log:time,uptime,level,tags:filecount=1,filesize=10m
)

REM JVM options for Chronicle Queue (Java 17+)
set CHRONICLE_OPTS=--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.misc=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED

REM Start acceptor in background (uber jar default main class is acceptor)
echo.
echo Starting FIX Acceptor in background...
start "" /b java %JVM_OPTS% %GC_LOG_OPTS% %CHRONICLE_OPTS% !TEST_PROPS! -jar "%UBER_JAR%" -c "!ACCEPTOR_CONFIGS!" --latency > acceptor.log 2>&1

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

REM Find the acceptor PID for jstat monitoring
set ACCEPTOR_PID=
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":9876.*LISTENING" 2^>nul') do (
    set ACCEPTOR_PID=%%a
)

REM Start jstat GC monitoring on acceptor if enabled
if !GC_MONITOR!==1 (
    if defined ACCEPTOR_PID (
        echo Starting jstat GC monitoring ^(PID=!ACCEPTOR_PID!, interval=%GC_INTERVAL%ms^)...
        REM Capture baseline heap snapshot
        jstat -gc !ACCEPTOR_PID! > acceptor-heap-baseline.log 2>&1
        REM Start jstat in background
        start "" /b cmd /c "jstat -gcutil !ACCEPTOR_PID! %GC_INTERVAL% > acceptor-jstat.log 2>&1"
    ) else (
        echo WARNING: Could not find acceptor PID. GC monitoring disabled.
        set GC_MONITOR=0
    )
)

REM Run initiator in latency mode (use -cp to specify different main class)
echo.
echo Starting FIX Initiator in latency mode...
echo.
java %JVM_OPTS% %CHRONICLE_OPTS% !TEST_PROPS! -cp "%UBER_JAR%" com.omnibridge.apps.fix.initiator.SampleInitiator -c "!INITIATOR_CONFIGS!" --latency --warmup-orders %WARMUP_ORDERS% --test-orders %TEST_ORDERS% --rate %RATE%

set TEST_EXIT_CODE=%ERRORLEVEL%

REM The acceptor reports its stages when the initiator's Logout arrives
if !STAGES!==1 (
    powershell -NoProfile -Command "Start-Sleep -Seconds 2" >nul 2>&1
    echo.
    echo Acceptor stage timing ^(from acceptor.log^):
    findstr /C:"Stage timing [" /C:"  stage " acceptor.log
)

REM The acceptor logs its read-cap count at the initiator's Logout (whole connection, warmup included)
echo.
echo Acceptor read cap ^(from acceptor.log^):
findstr /C:"read cap reached" acceptor.log || echo   ^(no read cap line in acceptor.log^)

REM Capture final heap snapshot before cleanup
if !GC_MONITOR!==1 (
    if defined ACCEPTOR_PID (
        jstat -gc !ACCEPTOR_PID! > acceptor-heap-final.log 2>&1
    )
)

:cleanup
REM Cleanup: Kill acceptor process (this also stops jstat since it targets the PID)
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

REM Print GC summary
if !GC_MONITOR!==1 (
    echo ==========================================================================
    echo ACCEPTOR GC SUMMARY
    echo ==========================================================================
    echo jstat log saved to: acceptor-jstat.log
    echo Heap snapshots saved to: acceptor-heap-baseline.log, acceptor-heap-final.log
    if exist acceptor-gc.log echo GC event log saved to: acceptor-gc.log
    echo.
    REM Use PowerShell to parse jstat output and print summary
    powershell -NoProfile -Command ^
        "$lines = Get-Content acceptor-jstat.log | Where-Object { $_ -notmatch '^\s*S0' -and $_ -match '\S' };" ^
        "if ($lines.Count -gt 0) {" ^
        "  $first = $lines[0] -split '\s+' | Where-Object { $_ };" ^
        "  $last = $lines[-1] -split '\s+' | Where-Object { $_ };" ^
        "  $allOld = $lines | ForEach-Object { ($_ -split '\s+' | Where-Object { $_ })[3] } | Sort-Object { [double]$_ };" ^
        "  Write-Host ('Samples collected: {0} (every {1}ms)' -f $lines.Count, '%GC_INTERVAL%');" ^
        "  Write-Host '';" ^
        "  Write-Host '--- Heap Utilization (%%) ---';" ^
        "  Write-Host ('  Old Gen:  start={0}%%  end={1}%%  max={2}%%' -f $first[3], $last[3], $allOld[-1]);" ^
        "  $allEden = $lines | ForEach-Object { ($_ -split '\s+' | Where-Object { $_ })[2] } | Sort-Object { [double]$_ };" ^
        "  Write-Host ('  Eden max: {0}%%' -f $allEden[-1]);" ^
        "  Write-Host '';" ^
        "  Write-Host '--- GC Activity During Test ---';" ^
        "  $ygcDuring = [int]$last[6] - [int]$first[6];" ^
        "  $fgcDuring = [int]$last[8] - [int]$first[8];" ^
        "  Write-Host ('  Young GC count:       {0}' -f $ygcDuring);" ^
        "  Write-Host ('  Young GC time:        {0}s (cumulative)' -f $last[7]);" ^
        "  Write-Host ('  Full GC count:        {0}' -f $fgcDuring);" ^
        "  Write-Host ('  Full GC time:         {0}s (cumulative)' -f $last[9]);" ^
        "  if ($last.Count -gt 12) {" ^
        "    Write-Host ('  Concurrent GC count:  {0}' -f $last[10]);" ^
        "    Write-Host ('  Concurrent GC time:   {0}s (cumulative)' -f $last[11]);" ^
        "    $gctDuring = [double]$last[12] - [double]$first[12];" ^
        "    Write-Host ('  Total GC time:        {0:F3}s (during test)' -f $gctDuring);" ^
        "  }" ^
        "  Write-Host '';" ^
        "  $oldGrowth = [double]$last[3] - [double]$first[3];" ^
        "  if ($oldGrowth -gt 10) {" ^
        "    Write-Host ('WARNING: Old gen grew by {0:F1}%% during the test.' -f $oldGrowth);" ^
        "    Write-Host '  This may indicate a memory leak or insufficient heap sizing.';" ^
        "  } elseif ($fgcDuring -gt 0) {" ^
        "    Write-Host ('WARNING: {0} full GC(s) occurred during the test.' -f $fgcDuring);" ^
        "    Write-Host '  Full GCs cause long pauses - consider increasing heap size.';" ^
        "  } else {" ^
        "    Write-Host 'OK: No significant heap growth or full GCs detected.';" ^
        "  }" ^
        "} else {" ^
        "  Write-Host 'No jstat data collected (test may have been too short).';" ^
        "}"
    echo.
    echo --- Raw Heap Snapshots ^(jstat -gc^) ---
    echo Baseline:
    type acceptor-heap-baseline.log
    echo.
    echo Final:
    type acceptor-heap-final.log
)
echo ==========================================================================

REM Expand before endlocal discards the variable
endlocal & exit /b %TEST_EXIT_CODE%
