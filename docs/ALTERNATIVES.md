# Rejected Alternatives & Trade-offs

## A-001: Docker / Containerized Environment
- **Considered**: Packaging each microservice and Healer into Docker containers.
- **Rejected because**: Constraint of 8 GB RAM target machine with Windows OS. Container engine overhead and memory virtualization would risk out-of-memory thrashing when running 7 distinct JVMs plus Python.
- **Alternative Adopted**: Native Java OS processes started and stopped via `java -Xmx128m -jar service-app.jar` using Java's `ProcessBuilder`.

## A-002: Service Mesh / Envoy Proxy for Link Fault Injection
- **Considered**: Using Envoy or Istio to intercept link delays and drops.
- **Rejected because**: Incompatible with lightweight Windows host environment and prohibited external container platforms.
- **Alternative Adopted**: `LinkClient` in `common-lib` polls the Healer's centralized Chaos Registry to simulate link latency, packet loss, and circuit breaking in-process before executing standard HTTP calls.

## A-003: CDN-Loaded UI Libraries (vis-network & Chart.js)
- **Considered**: Loading Chart.js and vis-network from cdnjs or unpkg.
- **Rejected because**: Hard constraint requires the dashboard to function entirely offline without external network calls.
- **Alternative Adopted**: Vendoring library JS files directly into `healer/src/main/resources/static/vendor/`.
