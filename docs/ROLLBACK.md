# ROLLBACK: Safe Reversion and Recovery

## Last Known Good Git Commit
- `d9a9ec0`: S6: Implement Healer Dashboard UI, SSE stream (/api/stream), and offline canvas topology/charts

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
