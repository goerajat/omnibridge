@echo off
REM Run the Sample FIX Acceptor (Exchange Simulator)
REM Usage: run-acceptor.bat [options]
REM Options:
REM   -p, --port <port>       Listen port (default: 9876)
REM   -s, --sender <id>       SenderCompID (default: EXCHANGE)
REM   -t, --target <id>       TargetCompID (default: CLIENT)
REM   --heartbeat <seconds>   Heartbeat interval (default: 30)
REM   --persistence <dir>     Persistence directory
REM   --fill-rate <rate>      Fill probability 0.0-1.0 (default: 0.8)

setlocal

set SCRIPT_DIR=%~dp0
set TARGET_DIR=%SCRIPT_DIR%fix-sample-apps\target

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
for /r "%SCRIPT_DIR%fix-engine\target" %%f in (*.jar) do set CP=!CP!;%%f
for /r "%SCRIPT_DIR%fix-message\target" %%f in (*.jar) do set CP=!CP!;%%f
for /r "%SCRIPT_DIR%fix-network-io\target" %%f in (*.jar) do set CP=!CP!;%%f
for /r "%SCRIPT_DIR%fix-persistence\target" %%f in (*.jar) do set CP=!CP!;%%f

REM Add all module classes
set CP=%CP%;%SCRIPT_DIR%fix-engine\target\classes
set CP=%CP%;%SCRIPT_DIR%fix-message\target\classes
set CP=%CP%;%SCRIPT_DIR%fix-network-io\target\classes
set CP=%CP%;%SCRIPT_DIR%fix-persistence\target\classes

REM Add Maven dependencies from local repo
set M2_REPO=%USERPROFILE%\.m2\repository
set CP=%CP%;%M2_REPO%\org\slf4j\slf4j-api\2.0.9\slf4j-api-2.0.9.jar
set CP=%CP%;%M2_REPO%\ch\qos\logback\logback-classic\1.4.14\logback-classic-1.4.14.jar
set CP=%CP%;%M2_REPO%\ch\qos\logback\logback-core\1.4.14\logback-core-1.4.14.jar
set CP=%CP%;%M2_REPO%\info\picocli\picocli\4.7.5\picocli-4.7.5.jar
set CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-databind\2.16.1\jackson-databind-2.16.1.jar
set CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-core\2.16.1\jackson-core-2.16.1.jar
set CP=%CP%;%M2_REPO%\com\fasterxml\jackson\core\jackson-annotations\2.16.1\jackson-annotations-2.16.1.jar
set CP=%CP%;%M2_REPO%\net\java\dev\jna\jna\5.14.0\jna-5.14.0.jar
set CP=%CP%;%M2_REPO%\net\java\dev\jna\jna-platform\5.14.0\jna-platform-5.14.0.jar
set CP=%CP%;%M2_REPO%\org\agrona\agrona\1.20.0\agrona-1.20.0.jar

echo Starting FIX Acceptor (Exchange Simulator)...
echo.

java -cp "%CP%" com.fixengine.samples.acceptor.SampleAcceptor %*

endlocal
