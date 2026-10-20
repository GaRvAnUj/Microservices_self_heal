# FEATURE-006: Healer Dashboard UI & Server-Sent Events (SSE) Streaming

## Assumptions Log
| Item | Assumption Description | Status |
| :--- | :--- | :--- |
| A-1  | Healer exposes SSE stream at `GET /api/stream` pushing state every 1 second. | Verified |
| A-2  | Static web resources are served by Spring Boot Web from `healer/src/main/resources/static/` at `http://127.0.0.1:8080/`. | Verified |
| A-3  | Topology visualization renders a 6-node directed graph with live node status colors (UP: #0A8754 green, DOWN: #D93025 red, RESTARTING: #F59E0B amber) and link latency/chaos badges. | Verified |
| A-4  | Real-time sliding charts plot 60-second rolling history of p95 latency, average latency, and error rate using high-performance HTML5 Canvas. | Verified |
| A-5  | All UI elements strictly follow the Indeed Minimal design system tokens from `design.md` (#004FCB primary blue, white cards, #E5E7EB borders, 8px card radius, clean hierarchy). | Verified |
| A-6  | Zero CDNs, zero external dependencies or downloads; 100% offline capable. | Verified |
