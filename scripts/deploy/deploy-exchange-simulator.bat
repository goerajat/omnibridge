@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM Exchange Simulator Deployment Script (Windows)
REM =============================================================================
REM Deploys the exchange simulator to a remote Linux server using PuTTY tools
REM
REM Prerequisites: pscp.exe and plink.exe must be in PATH
REM =============================================================================

set SCRIPT_DIR=%~dp0

REM Default values
set ADMIN_PORT=8080
set DEPLOY_DIR=/opt/exchange-simulator
set SSH_PORT=22

REM Parse arguments
:parse_args
if "%1"=="" goto end_parse
if "%1"=="-i" (
    set PEM_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--identity" (
    set PEM_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-u" (
    set SSH_USER=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--user" (
    set SSH_USER=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-h" (
    set SSH_HOST=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--host" (
    set SSH_HOST=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-p" (
    set ADMIN_PORT=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--port" (
    set ADMIN_PORT=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-d" (
    set DEPLOY_DIR=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--deploy-dir" (
    set DEPLOY_DIR=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-s" (
    set SSH_PORT=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--ssh-port" (
    set SSH_PORT=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--help" goto show_help
shift
goto parse_args
:end_parse

REM Validate required arguments
if "%PEM_FILE%"=="" goto missing_args
if "%SSH_USER%"=="" goto missing_args
if "%SSH_HOST%"=="" goto missing_args

if not exist "%PEM_FILE%" (
    echo Error: PEM file not found: %PEM_FILE%
    exit /b 1
)

REM Find distribution package
set DIST_DIR=%SCRIPT_DIR%apps\exchange-simulator\target
for %%f in ("%DIST_DIR%\exchange-simulator-*-dist.tar.gz") do set DIST_PACKAGE=%%f

if "%DIST_PACKAGE%"=="" (
    echo Error: Distribution package not found in %DIST_DIR%
    echo Please build the project first: mvn package -DskipTests
    exit /b 1
)

echo ========================================================
echo Exchange Simulator Deployment
echo ========================================================
echo Target: %SSH_USER%@%SSH_HOST%:%SSH_PORT%
echo Deploy Dir: %DEPLOY_DIR%
echo Admin Port: %ADMIN_PORT%
echo Package: %DIST_PACKAGE%
echo ========================================================

REM Upload distribution package
echo Uploading distribution package...
pscp -i "%PEM_FILE%" -P %SSH_PORT% "%DIST_PACKAGE%" %SSH_USER%@%SSH_HOST%:/tmp/

REM Deploy on remote server
echo Deploying on remote server...
plink -i "%PEM_FILE%" -P %SSH_PORT% %SSH_USER%@%SSH_HOST% "set -e; if [ -f %DEPLOY_DIR%/bin/exchange-simulator.sh ]; then sudo %DEPLOY_DIR%/bin/exchange-simulator.sh stop || true; fi; sudo mkdir -p %DEPLOY_DIR%; sudo chown -R %SSH_USER%:%SSH_USER% %DEPLOY_DIR%; cd /tmp; tar xzf exchange-simulator-*-dist.tar.gz; rm -rf %DEPLOY_DIR%/*; cp -r exchange-simulator-*/* %DEPLOY_DIR%/; rm -rf exchange-simulator-*; chmod +x %DEPLOY_DIR%/bin/*.sh; %DEPLOY_DIR%/bin/exchange-simulator.sh start -p %ADMIN_PORT%"

echo.
echo Deployment completed successfully!
echo Admin API: http://%SSH_HOST%:%ADMIN_PORT%
goto :eof

:missing_args
echo Error: Missing required arguments
echo Usage: %~nx0 -i ^<pem-file^> -u ^<username^> -h ^<hostname^> [options]
exit /b 1

:show_help
echo Usage: %~nx0 -i ^<pem-file^> -u ^<username^> -h ^<hostname^> [options]
echo.
echo Options:
echo   -i, --identity    Path to PEM file for SSH authentication (required)
echo   -u, --user        SSH username (required)
echo   -h, --host        Remote hostname or IP (required)
echo   -p, --port        Simulator admin port (default: 8080)
echo   -d, --deploy-dir  Deployment directory (default: /opt/exchange-simulator)
echo   -s, --ssh-port    SSH port (default: 22)
exit /b 0
