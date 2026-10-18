@echo off
rem Starts accounting-app: PostgreSQL (Docker, port 15432) + Spring Boot (port 80 by default).
setlocal
cd /d "%~dp0"

if not defined ACCOUNTING_PORT set ACCOUNTING_PORT=80

docker info >nul 2>&1
if errorlevel 1 (
    echo [run.bat] Docker is not running. Start Docker Desktop and try again.
    exit /b 1
)

netstat -ano | findstr /R /C:":%ACCOUNTING_PORT% .*LISTENING" >nul
if not errorlevel 1 (
    echo [run.bat] Port %ACCOUNTING_PORT% is already in use by another program.
    echo [run.bat] Stop that program, or run with another port, e.g.:  set ACCOUNTING_PORT=8080 ^& run.bat
    exit /b 1
)

echo [run.bat] Starting PostgreSQL...
docker compose up -d --wait
if errorlevel 1 (
    echo [run.bat] Failed to start PostgreSQL.
    exit /b 1
)

if not defined ACCOUNTING_DB_URL set ACCOUNTING_DB_URL=jdbc:postgresql://localhost:15432/accounting
if not defined ACCOUNTING_DB_USER set ACCOUNTING_DB_USER=accounting
if not defined ACCOUNTING_DB_PASSWORD set ACCOUNTING_DB_PASSWORD=accounting

echo [run.bat] Starting accounting-app on http://localhost:%ACCOUNTING_PORT%/ ...
call "%~dp0gradlew.bat" bootRun
endlocal
