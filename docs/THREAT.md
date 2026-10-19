# THREAT: Threat Model & Security Posture

## 1. Asset & Boundary Inventory
- **Assets**: Healer control endpoints, Chaos injection API, EdgePolicy dynamic configuration endpoints, Microservice OS processes.
- **Network Boundaries**: Single host (`localhost` / `127.0.0.1`). No public exposure.

## 2. Threat Scenarios & Mitigations
- **Threat 1: Unauthorized Chaos Triggering or Process Termination**
  - *Risk*: An external actor triggers `/chaos/crash` or `/chaos/cpu`, causing denial of service.
  - *Mitigation*: All endpoints bind exclusively to `127.0.0.1`. Remote network interfaces are disabled.
- **Threat 2: Uncontrolled Process Spawning**
  - *Risk*: Healer spawns unlimited Java processes leading to RAM exhaustion.
  - *Mitigation*: ServiceManager enforces a single PID per service role, kills existing processes before restart, and pins `-Xmx128m` on each JVM.
- **Threat 3: Arbitrary Command Execution via ProcessBuilder**
  - *Risk*: Healer executes untrusted command strings.
  - *Mitigation*: Strict, hardcoded command arrays launching only `java.exe -jar service-app.jar` with validated arguments.
