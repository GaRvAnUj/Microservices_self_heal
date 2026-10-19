# System Execution Flow

## 1. Request Flow (Workload Execution)
```
LoadGenerator (Healer) 
  --> [HTTP GET /work] --> gateway (8081)
    --> [LinkClient -> HTTP /work] --> compose-post (8082)
      --> [LinkClient] --> text (8083) [leaf, non-critical]
      --> [LinkClient] --> media (8084) [leaf, non-critical]
      --> [LinkClient] --> user (8085) [leaf, non-critical]
      --> [LinkClient] --> post-storage (8086) [leaf, CRITICAL]
```
- Non-critical link failures / timeouts result in graceful degradation (`degraded: true`).
- Critical link failure on `post-storage` triggers 500 error response back up the chain.

## 2. Telemetry & Incident Lifecycle Flow
```
1. Microservices collect telemetry (CPU, memory, EdgeStats, error counters) in memory.
2. Healer Collector polls GET /internal/telemetry every 3s across all 6 services.
3. Telemetry Aggregator produces rolling 3-window sample means.
4. Detector evaluates z-scores against baseline:
   - If SLO breached (p95 > 400ms OR error rate > 3%) OR z > 4 for 2 windows -> Trigger Incident.
5. Diagnosis Dispatcher:
   - Baseline Mode: Pick service with max abnormality -> Dispatch Process Restart.
   - Proposed Mode: Joint link + service z-score ranking + callee disambiguation.
6. Healer executes Action:
   - Node fault -> ProcessBuilder restart.
   - Link fault -> PUT /internal/policy/{target} on caller (tune timeout/retries or open breaker).
7. Verification Phase (Wait 3 windows):
   - Evaluate post-action abnormality (zT) and system SLO.
   - N3 Attribution Verdict: SUCCESS | WRONG_TARGET | INEFFECTIVE_ACTION | INCONCLUSIVE.
   - Selective Update: Update Q-table or re-diagnose next candidate.
8. Real-time Dashboard:
   - Healer pushes state via SSE (/events) to Dashboard UI.
```
