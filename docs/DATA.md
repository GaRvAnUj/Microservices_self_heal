# DATA: Data Structures and Persistence Schemas

## 1. Runtime Telemetry Payloads
### Microservice Telemetry Payload (`GET /internal/telemetry`)
```json
{
  "serviceName": "compose-post",
  "port": 8082,
  "timestamp": 1774000000000,
  "nodeMetrics": {
    "processCpuLoad": 0.04,
    "systemCpuLoad": 0.12,
    "heapUsedBytes": 32505856,
    "heapCommittedBytes": 67108864,
    "requestCount": 420
  },
  "edgeStats": [
    {
      "caller": "compose-post",
      "target": "post-storage",
      "callCount": 420,
      "errorCount": 0,
      "totalLatencyMs": 4200,
      "avgLatencyMs": 10.0,
      "errorRate": 0.0
    }
  ],
  "logEventCounters": {
    "LINK_TIMEOUT": 0,
    "DOWNSTREAM_ERROR": 0
  }
}
```

## 2. In-Memory & Centralized Structures
- **Chaos Registry**: `Map<String, LinkFault>` keyed by `"caller->target"` storing `{ delayMs: int, lossPct: double }`.
- **EdgePolicy**: `{ timeoutMs: int, maxRetries: int, retryBudgetPct: int, breakerOpen: boolean }`.
- **Baseline Profile**: `Map<String, MetricBaseline>` where each signal has `mean` and `std`.

## 3. H2 Embedded Database Tables
- **`incidents`**: `(id, started_at, resolved_at, trigger_reason, mode, root_cause_type, target_entity, final_status)`
- **`incident_actions`**: `(id, incident_id, step_num, action_type, target_component, started_at, completed_at, post_action_z, verdict)`
- **`q_policy`**: `(fault_type VARCHAR, action_name VARCHAR, q_value DOUBLE, update_count INT)`
  - Initialized with: `(cpu_hog, restart, 0.9)`, `(crash, restart, 0.9)`, `(link_delay, tune_link, 0.9)`, `(link_delay, breaker, 0.5)`, `(link_loss, breaker, 0.9)`, `(link_loss, tune_link, 0.5)`.
- **`labelled_samples`**: `(id, timestamp, features_json, true_faulty_component, fault_type)`
