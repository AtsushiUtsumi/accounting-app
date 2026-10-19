@echo off
rem Starts accounting-app in containers: PostgreSQL (port 15432) + Spring Boot (port 80 by default).
rem The app image is built locally from the Dockerfile (no GHCR). Ctrl+C stops both containers.
setlocal
cd /d "%~dp0"

if not defined ACCOUNTING_PORT set ACCOUNTING_PORT=80

docker info >nul 2>&1
if errorlevel 1 (
    echo [run-container.bat] Docker is not running. Start Docker Desktop and try again.
    exit /b 1
)

netstat -ano | findstr /R /C:":%ACCOUNTING_PORT% .*LISTENING" >nul
if not errorlevel 1 (
    echo [run-container.bat] Port %ACCOUNTING_PORT% is already in use by another program.
    echo [run-container.bat] Stop that program, or run with another port, e.g.:  set ACCOUNTING_PORT=8080 ^& run-container.bat
    exit /b 1
)

echo [run-container.bat] Building the jar...
call "%~dp0gradlew.bat" bootJar
if errorlevel 1 (
    echo [run-container.bat] The build failed.
    exit /b 1
)

echo [run-container.bat] Starting accounting-app on http://localhost:%ACCOUNTING_PORT%/ ...
docker compose --profile app up --build --attach app
endlocal
