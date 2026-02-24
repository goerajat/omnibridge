@echo off
setlocal enabledelayedexpansion

rem Deploy OmniView to a remote Linux server
rem
rem Usage: deploy-omniview.bat -i <pem-file> -u <username> -h <hostname> [-p <port>] [-d <deploy-dir>]
rem
rem Requires: ssh and scp (OpenSSH client installed on Windows)

rem Default values
set REMOTE_PORT=3000
set DEPLOY_DIR=/opt/omniview
set SSH_PORT=22
set DIST_FILE=

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
if "%~1"=="-p" (
    set REMOTE_PORT=%~2
    shift
    shift
    goto :parse_args
)
if "%~1"=="--port" (
    set REMOTE_PORT=%~2
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
if "%~1"=="--dist" (
    set DIST_FILE=%~2
    shift
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

rem Find distribution file
set SCRIPT_DIR=%~dp0
if "%DIST_FILE%"=="" (
    for /f "delims=" %%f in ('dir /b /o-d "%SCRIPT_DIR%omniview\target\omniview-*-dist.tar.gz" 2^>nul') do (
        set DIST_FILE=%SCRIPT_DIR%omniview\target\%%f
        goto :found_dist
    )
    echo [ERROR] Distribution file not found. Run 'mvn package' in omniview/ first.
    exit /b 1
)
:found_dist

if not exist "%DIST_FILE%" (
    echo [ERROR] Distribution file not found: %DIST_FILE%
    exit /b 1
)

rem Extract version from distribution file
for %%f in ("%DIST_FILE%") do set DIST_BASENAME=%%~nxf
for /f "tokens=2 delims=-" %%v in ("%DIST_BASENAME%") do set VERSION=%%v

echo [INFO] Deploying OmniView %VERSION% to %REMOTE_USER%@%REMOTE_HOST%
echo [INFO] Distribution: %DIST_FILE%
echo [INFO] Deploy directory: %DEPLOY_DIR%
echo [INFO] Server port: %REMOTE_PORT%

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

rem Create deployment directory on remote
echo [INFO] Creating deployment directory...
ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "sudo mkdir -p %DEPLOY_DIR% && sudo chown %REMOTE_USER%:%REMOTE_USER% %DEPLOY_DIR%"

rem Copy distribution file
echo [INFO] Copying distribution package...
set REMOTE_TMP=/tmp/%DIST_BASENAME%
scp %SCP_OPTS% "%DIST_FILE%" %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_TMP%
if errorlevel 1 (
    echo [ERROR] Failed to copy distribution file
    exit /b 1
)

rem Copy remote deploy script
echo [INFO] Deploying on remote server...
set REMOTE_SCRIPT=/tmp/deploy_omniview_remote.sh
scp %SCP_OPTS% "%~dp0deploy-omniview-remote.sh" %REMOTE_USER%@%REMOTE_HOST%:%REMOTE_SCRIPT%
if errorlevel 1 (
    echo [ERROR] Failed to copy deployment script
    exit /b 1
)

rem Execute deployment on remote server
ssh %SSH_OPTS% %REMOTE_USER%@%REMOTE_HOST% "chmod +x %REMOTE_SCRIPT% && %REMOTE_SCRIPT% '%DEPLOY_DIR%' '%REMOTE_TMP%' '%REMOTE_PORT%' '%VERSION%' && rm -f %REMOTE_SCRIPT%"

if errorlevel 1 (
    echo [ERROR] Deployment failed
    exit /b 1
)

echo [INFO] Deployment completed successfully!
echo [INFO] OmniView is running at http://%REMOTE_HOST%:%REMOTE_PORT%
exit /b 0

:usage
echo Usage: %~nx0 -i ^<pem-file^> -u ^<username^> -h ^<hostname^> [options]
echo.
echo Required arguments:
echo   -i, --identity ^<pem-file^>   Path to the PEM file for SSH authentication
echo   -u, --user ^<username^>       SSH username
echo   -h, --host ^<hostname^>       Remote hostname or IP address
echo.
echo Optional arguments:
echo   -p, --port ^<port^>           OmniView server port (default: 3000)
echo   -d, --deploy-dir ^<dir^>      Deployment directory (default: /opt/omniview)
echo   -s, --ssh-port ^<port^>       SSH port (default: 22)
echo   --dist ^<file^>               Distribution file to deploy (default: auto-detect)
echo   --help                      Show this help message
echo.
echo Examples:
echo   %~nx0 -i C:\keys\mykey.pem -u ubuntu -h 192.168.1.100
echo   %~nx0 -i key.pem -u ec2-user -h myserver.com -p 8080
exit /b 1

endlocal
