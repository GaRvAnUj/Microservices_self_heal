# FEATURE-005: Detection, Diagnosis, and Closed-Loop Remediation Engine

## Assumptions Log
| Item | Assumption Description | Status |
| :--- | :--- | :--- |
| A-1  | Incident triggers when LoadGenerator SLO is breached ($p95 \ge 400\text{ ms}$ OR $\text{errorRate} \ge 3\%$) OR any signal has $z > 4$ for 2 consecutive samples. | Verified |
| A-2  | `DiagnosisClient` calls `http://127.0.0.1:5000/diagnose` with 1s timeout; falls back to built-in Java statistical localizer (marked as an approximation). | Verified |
| A-3  | Disambiguation classifies as `LINK_FAULT` if link $(A \to B)$ has $z \ge 4$ and node $B$ CPU & error logs have $z < 3$. Otherwise blames node $B$. | Verified |
| A-4  | Link remediation uses `PUT http://127.0.0.1:{callerPort}/internal/policy/{target}` to update timeouts or trip circuit breaker with zero downtime. | Verified |
| A-5  | Verification phase evaluates for 3 telemetry windows (9 seconds) post-action before determining attribution outcome (`SUCCESS`, `WRONG_TARGET`, `INEFFECTIVE_ACTION`, `INCONCLUSIVE`). | Verified |
| A-6  | Q-policy table update rule: success: $q \leftarrow q + 0.3(1 - q)$; failure (`INEFFECTIVE_ACTION`): $q \leftarrow q \times 0.3$. | Verified |
