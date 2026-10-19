@echo off
echo ========================================================
echo Stopping all Self-Healing Microservices and Healer (Ports 8080-8086, 5000)...
echo ========================================================

powershell -NoProfile -Command ^
  "$ports = @(8080, 8081, 8082, 8083, 8084, 8085, 8086, 5000);" ^
  "foreach ($port in $ports) {" ^
  "    $connections = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue;" ^
  "    if ($connections) {" ^
  "        $pids = $connections | Select-Object -ExpandProperty OwningProcess -Unique;" ^
  "        foreach ($p in $pids) {" ^
  "            Write-Host 'Terminating process on port' $port 'PID:' $p;" ^
  "            Stop-Process -Id $p -Force -ErrorAction SilentlyContinue;" ^
  "        }" ^
  "    }" ^
  "}"

echo Done stopping processes.
