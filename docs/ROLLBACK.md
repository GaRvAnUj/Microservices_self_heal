# ROLLBACK: Safe Reversion and Recovery

## Last Known Good Git Commit
- `7e442bd`: S1: Initialize project skeleton, Maven multi-module configuration, start/stop scripts, and documentation

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
