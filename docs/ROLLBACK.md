# ROLLBACK: Safe Reversion and Recovery

## Last Known Good Git Commit
- `3f9d1f4`: S7: Implement Python diagnosis-service (FastAPI, Port 5000) with N1 disambiguation and startup integration

## Reversion Checklist
1. Stop all active background Java processes using `scripts/stop-all.bat` or:
   ```powershell
   Get-Process -Name java -ErrorAction SilentlyContinue | Stop-Process -Force
   ```
2. Reset git working directory:
   ```powershell
   git clean -fd
   git checkout .
   ```
3. Re-verify clean status:
   ```powershell
   git status
   ```
