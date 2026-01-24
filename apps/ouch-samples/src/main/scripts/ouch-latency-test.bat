@echo off
REM OUCH Latency Test script
REM Starts acceptor, runs latency test with initiator, then cleans up

setlocal

set SCRIPT_DIR=%~dp0

set WARMUP_ORDERS=%1
set TEST_ORDERS=%2
set RATE=%3

if "%WARMUP_ORDERS%"=="" set WARMUP_ORDERS=1000
if "%TEST_ORDERS%"=="" set TEST_ORDERS=1000
if "%RATE%"=="" set RATE=100

echo OUCH Latency Test
echo =================
echo Warmup orders: %WARMUP_ORDERS%
echo Test orders: %TEST_ORDERS%
echo Rate: %RATE% orders/sec
echo.

REM Start acceptor in background
echo Starting OUCH Acceptor...
start "OUCH Acceptor" /B cmd /c "%SCRIPT_DIR%ouch-acceptor.bat" --latency
timeout /t 3 /nobreak > nul

REM Run initiator in latency mode
echo Running latency test...
call "%SCRIPT_DIR%ouch-initiator.bat" --latency ^
    --warmup-orders %WARMUP_ORDERS% ^
    --test-orders %TEST_ORDERS% ^
    --rate %RATE%

REM Cleanup
echo.
echo Stopping acceptor...
taskkill /FI "WINDOWTITLE eq OUCH Acceptor" /F > nul 2>&1

echo Done.

endlocal
