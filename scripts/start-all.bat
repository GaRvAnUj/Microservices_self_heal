@echo off
echo ========================================================
echo Starting Self-Healing Microservices Platform (Windows, -Xmx128m)
echo ========================================================

call "%~dp0stop-all.bat"
timeout /t 2 /nobreak >nul

set ROOT_DIR=%~dp0..
set SVC_JAR=%ROOT_DIR%\service-app\target\service-app.jar
set HEALER_JAR=%ROOT_DIR%\healer\target\healer.jar

if not exist "%SVC_JAR%" (
    echo [ERROR] service-app.jar not found at %SVC_JAR%. Run 'mvn clean package' first.
    exit /b 1
)

if not exist "%HEALER_JAR%" (
    echo [ERROR] healer.jar not found at %HEALER_JAR%. Run 'mvn clean package' first.
    exit /b 1
)

echo Starting leaf services...
start "text-8083" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=text --server.port=8083
start "media-8084" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=media --server.port=8084
start "user-8085" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=user --server.port=8085
start "post-storage-8086" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=post-storage --server.port=8086

timeout /t 3 /nobreak >nul

echo Starting compose-post...
start "compose-post-8082" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=compose-post --server.port=8082 --svc.downstream="text:8083,media:8084,user:8085,post-storage:8086"

timeout /t 2 /nobreak >nul

echo Starting gateway...
start "gateway-8081" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=gateway --server.port=8081 --svc.downstream="compose-post:8082"

timeout /t 2 /nobreak >nul

echo Starting Healer controller & dashboard...
start "healer-8080" /B java -Xmx256m -jar "%HEALER_JAR%" --server.port=8080

echo ========================================================
echo All processes initiated. Healer dashboard: http://localhost:8080
echo Gateway endpoint: http://localhost:8081/work
echo ========================================================
