# CONSTRAINTS

## 1. Environment & Runtimes
- **Java**: Java 17, Spring Boot 3.x, Maven multi-module.
- **Python**: Python 3.10 / 3.11 only for the standalone `diagnosis-service` (FastAPI).
- **Target OS & Spec**: Windows, 8 GB RAM, no GPU available.
- **Process Memory Limits**: Each service JVM runs constrained with `-Xmx128m`.
- **Infrastructure Constraints**: No Docker, no Kubernetes, no Kafka, no Spring Cloud.
- **Process Management**: Services run as native OS processes started and stopped by the Healer via Java `ProcessBuilder`. Windows `.bat` scripts provided for batch operations.

## 2. Network & Dashboard Constraints
- **Offline Dashboard**: Dashboard must work completely offline. Vendor Chart.js and vis-network into static files; NO CDNs.
- **Binding Security**: Chaos and Healer management endpoints MUST bind to `localhost` / `127.0.0.1` only.
- **Design System**: Adhere to the design specifications defined in `design.md` (Indeed Minimal: clean, trust-forward, #004FCB primary blue, light gray borders, clean card hierarchy).

## 3. Strict Prohibitions (NEVER)
- NEVER read, edit, or commit `.env` or secret files.
- NEVER run `rm -rf` or destructive directory purges.
- NEVER force-push git branches.
- NEVER pipe downloads directly into a shell (e.g., `curl | bash`).
- NEVER disable, skip, or comment out tests to force a build to pass.
- NEVER delete or modify files outside the repository workspace.

## 4. Pause and Ask First Rules
Always pause and obtain user approval before:
- Adding any new dependency (Maven, pip, or JavaScript).
- Changing service or dashboard port allocations.
- Editing the parent `pom.xml`.
- Making any network call to an external host.
- Modifying more than 5 files in a single change/step.

## 5. Research & Scientific Integrity
- **No Invented MODIFy Results**: Do not fabricate or invent MODIFy diagnosis metrics or outputs.
- **Mark Approximations**: Every approximation (e.g. telemetry metric mapping, statistical link detector) must be explicitly marked as an approximation in the documentation, code comments, and UI badges.
