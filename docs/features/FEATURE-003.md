# FEATURE-003: service-app Configurable Workload Engine

## Assumptions Log
| Item | Assumption Description | Status |
| :--- | :--- | :--- |
| A-1  | Leaf services (`text`, `media`, `user`, `post-storage`) receive no `--svc.downstream` argument and return terminal JSON. | Verified |
| A-2  | `compose-post` calls downstream services concurrently or sequentially via `LinkClient` without blocking on non-critical failures. | Verified |
| A-3  | `post-storage` is designated as the sole critical downstream; failure yields HTTP 500 up to gateway. | Verified |
