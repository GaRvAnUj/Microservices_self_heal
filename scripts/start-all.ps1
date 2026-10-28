# start-all.ps1 - Launch diagnosis-service and healer controller; healer manages microservices
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "Starting Self-Healing Microservices Platform (Windows, -Xmx128m)" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = (Resolve-Path "$scriptDir\..").Path
$svcJar = "$rootDir\service-app\target\service-app.jar"
$healerJar = "$rootDir\healer\target\healer.jar"
$diagnosisDir = "$rootDir\diagnosis-service"
$modifyPython = "$rootDir\.venv-modify-training\Scripts\python.exe"

if (-not (Test-Path $modifyPython)) {
    $modifyPython = (Get-Command "python" -ErrorAction Stop).Source
}

if (-not (Test-Path $svcJar)) {
    Write-Error "[ERROR] service-app.jar not found at $svcJar. Run 'mvn clean package' first."
    exit 1
}

if (-not (Test-Path $healerJar)) {
    Write-Error "[ERROR] healer.jar not found at $healerJar. Run 'mvn clean package' first."
    exit 1
}

# 1. Stop any existing processes on platform ports
& "$scriptDir\stop-all.bat"
Start-Sleep -Seconds 2

# 2. Start Python diagnosis service (port 5000)
Write-Host "Starting Python diagnosis service (port 5000)..." -ForegroundColor Yellow
Start-Process -FilePath $modifyPython -ArgumentList @("-m", "uvicorn", "main:app", "--app-dir", $diagnosisDir, "--host", "127.0.0.1", "--port", "5000") -WindowStyle Hidden

Start-Sleep -Seconds 2

# 3. Start Healer controller & dashboard (port 8080). It owns the microservice processes.
Write-Host "Starting Healer controller and dashboard (port 8080)..." -ForegroundColor Yellow
Start-Process -FilePath "java" -ArgumentList @("-Xmx256m", "-jar", $healerJar, "--server.port=8080") -WindowStyle Hidden

Write-Host "Waiting for services to initialize..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

Write-Host "========================================================" -ForegroundColor Green
Write-Host "All processes initiated." -ForegroundColor Green
Write-Host "Healer dashboard:  http://localhost:8080" -ForegroundColor Cyan
Write-Host "Gateway endpoint:  http://localhost:8081/work" -ForegroundColor Cyan
Write-Host "Diagnosis service: http://localhost:5000/health" -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Green
