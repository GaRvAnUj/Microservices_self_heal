@echo off
set ROOT_DIR=%~dp0..
set SVC_JAR=%ROOT_DIR%\service-app\target\service-app.jar

echo Starting leaf services...
start "text-8083" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=text --server.port=8083
start "media-8084" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=media --server.port=8084
start "user-8085" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=user --server.port=8085
start "post-storage-8086" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=post-storage --server.port=8086

ping 127.0.0.1 -n 6 >nul

echo Starting compose-post...
start "compose-post-8082" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=compose-post --server.port=8082 --svc.downstream="text:8083,media:8084,user:8085,post-storage:8086"

ping 127.0.0.1 -n 4 >nul

echo Starting gateway...
start "gateway-8081" /B java -Xmx128m -jar "%SVC_JAR%" --svc.name=gateway --server.port=8081 --svc.downstream="compose-post:8082"

ping 127.0.0.1 -n 6 >nul
echo All 6 services started.
