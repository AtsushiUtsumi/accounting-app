@echo off
rem Builds the jar, then builds the image and pushes it to GHCR (linux/amd64 + linux/arm64).
rem
rem   build-image.bat           test, build and push  ghcr.io/atsushiutsumi/accounting-app:latest and :<commit>
rem   build-image.bat --local   build for this PC only and keep it locally as :local (no push)
rem
rem Before the first push, log in to GHCR with a token that has write:packages:
rem   docker login ghcr.io -u AtsushiUtsumi
setlocal
cd /d "%~dp0"

set IMAGE=ghcr.io/atsushiutsumi/accounting-app

docker info >nul 2>&1
if errorlevel 1 (
    echo [build-image.bat] Docker is not running. Start Docker Desktop and try again.
    exit /b 1
)

echo [build-image.bat] Running tests and building the jar...
call "%~dp0gradlew.bat" test bootJar
if errorlevel 1 (
    echo [build-image.bat] Tests or the build failed.
    exit /b 1
)

if "%~1"=="--local" (
    docker build -t %IMAGE%:local .
    if errorlevel 1 exit /b 1
    echo [build-image.bat] Built %IMAGE%:local
    exit /b 0
)

for /f %%i in ('git rev-parse --short HEAD') do set TAG=%%i
git diff --quiet HEAD
if errorlevel 1 (
    echo [build-image.bat] There are uncommitted changes. Commit them first so that the tag %TAG% matches the image.
    exit /b 1
)

docker buildx build --platform linux/amd64,linux/arm64 -t %IMAGE%:latest -t %IMAGE%:%TAG% --push .
if errorlevel 1 (
    echo [build-image.bat] Push failed. Did you run: docker login ghcr.io -u AtsushiUtsumi ?
    exit /b 1
)
echo [build-image.bat] Pushed %IMAGE%:latest and %IMAGE%:%TAG%
endlocal
