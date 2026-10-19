# FAILURE: Failure Modes and Resilience Boundaries

## 1. Known Failure Modes
- **Port Collision on Windows**:
  - *Symptom*: JVM fails to bind to assigned port (8081-8086, 8080).
  - *Remedy*: `stop-all.bat` forcefully terminates dangling processes on ports 8080-8086 via `netstat` and `taskkill`.
- **Healer Crash / Desynchronization**:
  - *Symptom*: Healer restarts while microservices continue running as orphaned processes.
  - *Remedy*: ServiceManager checks port availability and terminates existing occupants before spawning new instances.
- **Flapping or Cascading Timeouts**:
  - *Symptom*: Repeated retries exhaust client thread pools and degrade healthy services.
  - *Remedy*: `EdgePolicy` enforces `retryBudgetPct=20` and fast circuit-breaker trip on successive failures.
- **Python Diagnosis Service Unavailability**:
  - *Symptom*: `POST /diagnose` times out (> 1s) or connection refused.
  - *Remedy*: DiagnosisClient automatically falls back to built-in Java z-score statistical localizer and marks telemetry with badge `fallback`.
