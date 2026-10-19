# FEATURE-002: common-lib Resilience and Chaos Primitives

## Assumptions Log
| Item | Assumption Description | Status |
| :--- | :--- | :--- |
| A-1  | Link fault delays and drops apply before the actual HTTP network socket is opened. | Verified |
| A-2  | `OperatingSystemMXBean` can be cast to `com.sun.management.OperatingSystemMXBean` on HotSpot/Adoptium JDKs to get process and system CPU load. | Verified |
| A-3  | `POST /chaos/crash` should flush the HTTP response body before triggering `Runtime.getRuntime().halt(1)` so caller receives confirmation. | Verified |
| A-4  | `LinkClient` uses standard `java.net.http.HttpClient` avoiding bulky third-party HTTP client dependencies. | Verified |
