# RESTART CONTEXT FOR NEXT AGENT

> **Status**: Steps 1 to 7 are **100% Complete & Verified**.  
> **Last Commit**: `ab2033d` on branch `master`.  
> **Remaining Work**: **Step 8 Only** (End-to-End Validation & Final Defense Document).

---

## 1. Environment & Executable Paths
- **Operating System**: Windows (use `cmd.exe /c` or PowerShell `&`).
- **Java**: JDK 21 at `C:\Program Files\Java\jdk-21.0.12`.
- **Maven**: `C:\Users\Administrator\.maven\maven-3.10.0-rc-1\bin\mvn.cmd` *(mvn is not in default PATH)*.
- **Python**: Python 3.14 (pre-installed packages: `fastapi`, `uvicorn`, `pydantic`, `pytest`, `requests`).
- **All Tests Green**:
  - Java (38 tests): `& "C:\Users\Administrator\.maven\maven-3.10.0-rc-1\bin\mvn.cmd" test`
  - Python (6 tests): `python -m pytest diagnosis-service/test_diagnosis.py`

---

## 2. Completed Architecture (Steps 1–7)
1. **`common-lib`**: `EdgePolicy`, `LinkClient`, `ChaosRegistryCache`, `NodeMetricsCollector`, `LogEventTracker`, `CommonChaosController`.
2. **`service-app`**: Configurable single-jar microservice tree (`gateway:8081` $\to$ `compose-post:8082` $\to$ `text:8083`, `media:8084`, `user:8085`, `post-storage:8086 [CRIT]`).
3. **`healer` (Port 8080)**:
   - `ServiceManager`: Process management (`-Xmx128m` limit), restart, uptime/downtime.
   - `LoadGenerator`: 7 req/s synthetic traffic to gateway; SLO: $p95 < 400\text{ ms}$ & $\text{errorRate} < 3\%$.
   - `TelemetryCollectorService` & `BaselineProfileManager`: 3s sliding window, z-scores.
   - `ChaosRegistry` & `ChaosRegistryController`: Central link impairments store.
   - `DatabaseInitializer`: Embedded H2 (`./data/healerdb`), tables: `incidents`, `incident_actions`, `q_policy`, `labelled_samples`.
   - `HealingEngine` & `PolicyService`: Closed-loop remediation with dual modes (**BASELINE** vs **PROPOSED**), **N1** disambiguation, **N2** fault-type action dispatch, **N3** recovery-failure attribution (`WRONG_TARGET`, `INEFFECTIVE_ACTION`, `INCONCLUSIVE`).
   - `DashboardApiController`: SSE streaming (`/api/stream`), REST controls (`/api/status`, `/api/mode`, `/api/chaos/inject`, `/api/chaos/clear`).
   - Static Dashboard UI (`healer/src/main/resources/static/`): 100% offline, zero-CDN, Indeed Minimal design tokens (#004FCB), HTML5 Canvas topology graph, real-time sliding charts.
4. **`diagnosis-service` (Port 5000)**: Python FastAPI service (`main.py`) exposing `POST /diagnose` and `GET /health`.
5. **Scripts**: `scripts/start-all.bat` and `scripts/stop-all.bat` (manages ports 8080-8086, 5000).

---

## 3. What To Do in Step 8 (Next Tasks)
1. **End-to-End Validation**:
   - Run/verify the end-to-end resilience lifecycle:
     - Link delay fault on `compose-post -> text` $\to$ **N1** classifies link fault $\to$ **N2** tunes link policy via `PUT` with zero downtime $\to$ SLO recovers without restarting `compose-post`.
     - Node CPU hog on `text` $\to$ classifies node fault $\to$ process restart.
     - Recovery-failure attribution $\to$ verify `INEFFECTIVE_ACTION` lowers Q-score and `WRONG_TARGET` shifts suspects.
2. **Final Defense Documentation**:
   - Expand and complete `docs/defense/DEFENSE.md` addressing all 7 defense sections:
     - *How it works (sketch the flow)*
     - *Key decisions and why X over Y*
     - *Data structures and why*
     - *How a malicious actor could abuse it*
     - *Where it fails and how we notice*
     - *What I would change with more time*
3. **Commit & Wrap up**:
   - Update `docs/HANDOVER.md` and `docs/ROLLBACK.md`.
   - Commit cleanly to Git (local only, do not remote push).

---

## 4. Key Rules to Remember
- **Do NOT** make remote git pushes (local commits only).
- **Do NOT** add new external dependencies without pausing/asking.
- **Do NOT** make external network calls (system runs 100% offline).
- Management endpoints must bind to `127.0.0.1` only.
- Stop processes before/after full runs via: `cmd.exe /c scripts\stop-all.bat`.
