@echo off
setlocal enabledelayedexpansion

rem Deploy Sample FIX and OUCH Acceptors to a remote Linux server
rem
rem Usage: deploy-samples.bat -i <pem-file> -u <username> -h <hostname> [options]
rem
rem Requires: ssh and scp (OpenSSH client)

rem Default values
set DEPLOY_DIR=/opt/samples
set SSH_PORT=22
set FIX_ADMIN_PORT=8081
set OUCH_ADMIN_PORT=8082
set DEPLOY_FIX=true
set DEPLOY_OUCH=true

rem Parse arguments
:parse_args
if "%~1"=="" goto :validate_args

if "%~1"=="-i" (
    set PEM_FILE=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--identity" (
    set PEM_FILE=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="-u" (
    set REMOTE_USER=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--user" (
    set REMOTE_USER=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="-h" (
    set REMOTE_HOST=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--host" (
    set REMOTE_HOST=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="-d" (
    set DEPLOY_DIR=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--deploy-dir" (
    set DEPLOY_DIR=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="-s" (
    set SSH_PORT=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--ssh-port" (
    set SSH_PORT=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--fix-only" (
    set DEPLOY_OUCH=false
    shift
    goto :parse_args
)
if "%~1"=="--ouch-only" (
    set DEPLOY_FIX=false
    shift
    goto :parse_args
)
if "%~1"=="--help" goto :usage

echo [ERROR] Unknown option: %~1
goto :usage

:validate_args
rem Validate required arguments
if "%PEM_FILE%"=="" (
    echo [ERROR] Missing required argument: -i ^<pem-file^>
    goto :usage
)
if "%REMOTE_USER%"=="" (
    echo [ERROR] Missing required argument: -u ^<username^>
    goto :usage
)
if "%REMOTE_HOST%"=="" (
    echo [ERROR] Missing required argument: -h ^<hostname^>
    goto :usage
)

rem Check PEM file exists
if not exist "%PEM_FILE%" (
    echo [ERROR] PEM file not found: %PEM_FILE%
    exit /b 1
)

rem Find distribution files
set SCRIPT_DIR=%~dp0

if "%DEPLOY_FIX%"=="true" (
    for /f "delims=" %%f in ('dir /b /o-d "%SCRIPT_DIR%apps\fix-samples\target\fix-samples-*-dist.tar.gz" 2^>nul') do (
        set FIX_DIST=%SCRIPT_DIR%apps\fix-samples\target\%%f
        goto :found_fix
    )
    echo [ERROR] FIX distribution not found. Run 'mvn package' first.
    exit /b 1
)
:found_fix

if "%DEPLOY_OUCH%"=="true" (
    for /f "delims=" %%f in ('dir /b /o-d "%SCRIPT_DIR%apps\ouch-samples\target\ouch-samples-*-dist.tar.gz" 2^>nul') do (
        set OUCH_DIST=%SCRIPT_DIR%apps\ouch-samples\target\%%f
        goto :found_ouch
    )
    echo [ERROR] OUCH distribution not found. Run 'mvn package' first.
    exit /b 1
)
:found_ouch

echo [INFO] Deploying to %REMOTE_USER%@%REMOTE_HOST%:%DEPLOY_DIR%
if "%DEPLOY_FIX%"=="true" echo [INFO] FIX distribution: %FIX_DIST%
if "%DEPLOY_OUCH%"=="true" echo [INFO] OUCH distribution: %OUCH_DIST%

rem SSH options
set SSH_OPTS=-i "%PEM_FILE%" -p %SSH_PORT% -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10
set SCP_OPTS=-i "%PEM_FILE%" -P %SSH_PORT% -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10

rem Test SSH connection
echo [INFO] Testing SSH connection...
ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "echo Connection successful" >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Failed to connect to %REMOTE_USER%@%REMOTE_HOST%
    exit /b 1
)

rem Create deployment directory
echo [INFO] Creating deployment directory...
ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "sudo mkdir -p %DEPLOY_DIR% && sudo chown %REMOTE_USER%:%REMOTE_USER% %DEPLOY_DIR%"

rem Deploy FIX acceptor
if "%DEPLOY_FIX%"=="true" (
    echo [INFO] Deploying FIX Acceptor...
    for %%f in ("%FIX_DIST%") do set FIX_BASENAME=%%~nxf
    scp %SCP_OPTS% "%FIX_DIST%" %REMOTE_USER%@%REMOTE_HOST%:/tmp/!FIX_BASENAME!
    if errorlevel 1 (
        echo [ERROR] Failed to copy FIX distribution
        exit /b 1
    )

    ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "pkill -f SampleAcceptor 2>/dev/null; sleep 1; cd /tmp && tar -xzf !FIX_BASENAME! && rm -rf %DEPLOY_DIR%/fix-acceptor && mv $(tar -tzf !FIX_BASENAME! | head -1 | cut -d'/' -f1) %DEPLOY_DIR%/fix-acceptor && chmod +x %DEPLOY_DIR%/fix-acceptor/bin/*.sh && rm -f !FIX_BASENAME! && sed -i 's/port = 8081/port = %FIX_ADMIN_PORT%/' %DEPLOY_DIR%/fix-acceptor/conf/acceptor.conf 2>/dev/null; echo 'FIX Acceptor installed'"
)

rem Deploy OUCH acceptor
if "%DEPLOY_OUCH%"=="true" (
    echo [INFO] Deploying OUCH Acceptor...
    for %%f in ("%OUCH_DIST%") do set OUCH_BASENAME=%%~nxf
    scp %SCP_OPTS% "%OUCH_DIST%" %REMOTE_USER%@%REMOTE_HOST%:/tmp/!OUCH_BASENAME!
    if errorlevel 1 (
        echo [ERROR] Failed to copy OUCH distribution
        exit /b 1
    )

    ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "pkill -f SampleOuchAcceptor 2>/dev/null; sleep 1; cd /tmp && tar -xzf !OUCH_BASENAME! && rm -rf %DEPLOY_DIR%/ouch-acceptor && mv $(tar -tzf !OUCH_BASENAME! | head -1 | cut -d'/' -f1) %DEPLOY_DIR%/ouch-acceptor && chmod +x %DEPLOY_DIR%/ouch-acceptor/bin/*.sh && rm -f !OUCH_BASENAME! && sed -i 's/port = 8081/port = %OUCH_ADMIN_PORT%/' %DEPLOY_DIR%/ouch-acceptor/conf/ouch-acceptor.conf 2>/dev/null; echo 'OUCH Acceptor installed'"
)

rem Copy management script
echo [INFO] Creating management script...
scp %SCP_OPTS% "%SCRIPT_DIR%deploy-samples-mgmt.sh" %REMOTE_USER%@%REMOTE_HOST%:%DEPLOY_DIR%/samples.sh
ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "chmod +x %DEPLOY_DIR%/samples.sh"

rem Start acceptors
echo [INFO] Starting acceptors...
ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "%DEPLOY_DIR%/samples.sh restart all"

echo.
echo [INFO] Deployment completed successfully!
echo.
echo Sample applications deployed to: %DEPLOY_DIR%
echo.
echo Port assignments:
echo   FIX Acceptor:  9876 (FIX protocol), %FIX_ADMIN_PORT% (Admin API)
echo   OUCH Acceptor: 9200 (OUCH protocol), %OUCH_ADMIN_PORT% (Admin API)
exit /b 0

:usage
echo Usage: %~nx0 -i ^<pem-file^> -u ^<username^> -h ^<hostname^> [options]
echo.
echo Required arguments:
echo   -i, --identity ^<pem-file^>   Path to PEM file for SSH authentication
echo   -u, --user ^<username^>       SSH username
echo   -h, --host ^<hostname^>       Remote hostname or IP address
echo.
echo Optional arguments:
echo   -d, --deploy-dir ^<dir^>      Deployment directory (default: /opt/samples)
echo   -s, --ssh-port ^<port^>       SSH port (default: 22)
echo   --fix-only                  Deploy only FIX acceptor
echo   --ouch-only                 Deploy only OUCH acceptor
echo   --help                      Show this help message
echo.
echo Port assignments:
echo   FIX Acceptor:  9876 (FIX), 8081 (Admin)
echo   OUCH Acceptor: 9200 (OUCH), 8082 (Admin)
exit /b 1

endlocal
