# FEATURE-004: Healer Core Engine & Centralized Services

## Assumptions Log
| Item | Assumption Description | Status |
| :--- | :--- | :--- |
| A-1  | Embedded H2 database file can be created in `./data/healerdb` without requiring standalone database server. | Verified |
| A-2  | `ServiceManager` locates `service-app.jar` at `../service-app/target/service-app.jar` or `./service-app/target/service-app.jar`. | Verified |
| A-3  | SLO is defined as: healthy if `p95 < 400ms` AND `errorRate < 3%` (0.03). | Verified |
| A-4  | A sample is the arithmetic mean of signals across the last 3 telemetry windows (9 seconds). | Verified |
| A-5  | Baseline profile computes signal standard deviation with minimum floor ($\epsilon = 0.001$) to prevent division by zero in z-score calculations. | Verified |
