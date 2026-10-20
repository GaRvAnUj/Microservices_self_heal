# PROJECT STATUS & RESTART GUIDE

> **Status**: **100% COMPLETE & VERIFIED** across all 8 development phases.  
> **Repository Branch**: `master`.  
> **All Tests Passing**: 41 Java tests (`mvn test`), 6 Python tests (`pytest`).  
> **Jars Packaged**: `service-app/target/service-app.jar` and `healer/target/healer.jar`.

---

## 1. Quick Verification Commands
```powershell
# Run full Java multi-module tests (41 tests)
& "C:\Users\Administrator\.maven\maven-3.10.0-rc-1\bin\mvn.cmd" test

# Run Python FastAPI tests (6 tests)
python -m pytest diagnosis-service/test_diagnosis.py
```

---

## 2. Running the Complete Platform
To start all 6 microservices, Python diagnosis service, and Healer control plane:
```cmd
cmd.exe /c scripts\start-all.bat
```

To stop all services cleanly (ports 8080–8086, 5000):
```cmd
cmd.exe /c scripts\stop-all.bat
```

### Endpoints
- **Healer Real-Time Dashboard**: [http://localhost:8080](http://localhost:8080)
- **Gateway Workload**: [http://localhost:8081/work](http://localhost:8081/work)
- **Python Diagnosis Health**: [http://localhost:5000/health](http://localhost:5000/health)

---

## 3. Project Architecture Summary
- **6 Microservices (`service-app.jar`)**:
  - `gateway (8081)` $\to$ `compose-post (8082)` $\to$ `text (8083)`, `media (8084)`, `user (8085)`, `post-storage (8086) [CRITICAL]`.
  - Configurable downstream links with fail-fast non-critical degradation and critical downstream HTTP 500 triggering.
- **Resilience Library (`common-lib.jar`)**:
  - `EdgePolicy` (dynamic timeout, retry budget, circuit breaking).
  - `LinkClient` (active chaos interceptor, latency stats, error counters).
  - Telemetry endpoints (`GET /internal/telemetry`).
- **Control Plane (`healer.jar`, port 8080)**:
  - `ServiceManager`: Native Windows `ProcessBuilder` with `-Xmx128m` limit and uptime/downtime tracking.
  - `LoadGenerator`: Synthetic 7 req/s workload on `gateway:8081`; SLO evaluated ($p95 < 400\text{ ms}$ & $\text{errorRate} < 3\%$).
  - `Collector` & `BaselineProfileManager`: Rolling 3-window sliding estimator (9s smoothing) with statistical z-score triggers.
  - `ChaosRegistry`: In-memory link fault store polled by microservices every 1s.
  - `HealingEngine` & `PolicyService`: Dual modes (**BASELINE** vs **PROPOSED**).
    - **N1**: Link vs node disambiguation.
    - **N2**: Fault-type driven action dispatch (`tune_link` zero-downtime policy update vs node process restart).
    - **N3**: Recovery-failure attribution (`SUCCESS`, `WRONG_TARGET`, `INEFFECTIVE_ACTION`, `INCONCLUSIVE`) with reinforcement learning Q-policy updates stored in embedded H2 (`./data/healerdb`).
  - `Dashboard UI`: Indeed Minimal design system (#004FCB), SSE streaming (`/api/stream`), offline canvas topology map, and real-time sliding metric charts.
- **Python Diagnosis Service (`diagnosis-service`, port 5000)**:
  - FastAPI service providing probabilistic root-cause diagnosis.
- **Defense Documentation**:
  - Comprehensive architectural defense in `docs/defense/DEFENSE.md`.
