@echo off
REM OUCH Acceptor startup script

setlocal

set SCRIPT_DIR=%~dp0
set BASE_DIR=%SCRIPT_DIR%..

REM Set classpath
set CLASSPATH=%BASE_DIR%\conf;%BASE_DIR%\lib\*

REM JVM options
set JAVA_OPTS=-Xms512m -Xmx1g
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC
set JAVA_OPTS=%JAVA_OPTS% -Dlogback.configurationFile=%BASE_DIR%\conf\logback.xml

REM Run acceptor
java %JAVA_OPTS% -cp "%CLASSPATH%" com.fixengine.apps.ouch.acceptor.SampleOuchAcceptor %*

endlocal
