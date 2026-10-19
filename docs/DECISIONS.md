# Architectural Decision Log

## D-001: Project Layout and Technology Foundation
Date: 2026-10-19   Model: Gemini 2.5 Pro / pair-engineer
Context: Initializing the Self-Healing Microservices Platform on Windows with 8 GB RAM and strict low-resource constraints.
Options:
1. Docker Compose / Minikube containerization.
2. Spring Cloud / Netflix Eureka / Gateway stack.
3. Lightweight Spring Boot 3.x multi-module project with one configurable `service-app` jar, native OS process management via `ProcessBuilder`, and no heavy middleware.
Chosen: Option 3.
Why: Fits within the 8 GB machine RAM constraint (JVMs restricted to `-Xmx128m`), avoids Docker/K8s overhead, gives Healer direct control over process lifecycle via standard OS signals, and satisfies the prompt constraints.
Trade-off: Process management and link interception must be explicitly managed within `common-lib` and `ProcessBuilder` rather than offloaded to a service mesh or container daemon.
Revisit if: Requirements mandate deployment onto a managed Kubernetes cluster in Lane B.

## D-002: Dashboard Design System and Offline Assets
Date: 2026-10-19   Model: Gemini 2.5 Pro / pair-engineer
Context: Dashboard must be self-contained, offline-capable, and adhere to `design.md`.
Options:
1. React/Vite with Tailwind and CDN scripts.
2. Vanilla HTML/JS with vendored Chart.js and vis-network served directly by Spring Boot Healer static resources.
Chosen: Option 2.
Why: Satisfies the zero-CDN constraint, minimizes footprint, requires no node build pipeline for the healer, and allows tight compliance with `design.md` styling tokens.
Trade-off: Component templating is done via standard JS/DOM instead of a React JSX compiler.
Revisit if: UI complexity grows beyond a single-page monitoring dashboard.

## D-003: Link Fault Distribution and In-Process Policy Enforcement
Date: 2026-10-19   Model: Gemini 2.5 Pro / pair-engineer
Context: Microservices must enforce link-level delays and drops dynamically, and policies must survive or reset according to domain invariants.
Options:
1. Store link faults locally in caller JVM memory.
2. Intercept at OS network layer (e.g. iptables/tc or Windows netsh/wfp).
3. Poll centralized Healer Chaos Registry (`/chaos/registry`) periodically (1s) and evaluate in `LinkClient`.
Chosen: Option 3.
Why: Satisfies the hard invariant that link faults do NOT reside in the caller's process memory, meaning restarting a caller JVM does not clear a link fault. Node faults remain strictly in-process and clear upon restart.
Trade-off: Up to 1-second propagation delay between Healer injecting a link fault and the caller adopting it.
Revisit if: Sub-millisecond fault injection precision is demanded.

## D-004: Critical vs Non-Critical Microservice Workload Handling
Date: 2026-10-19   Model: Gemini 2.5 Pro / pair-engineer
Context: Microservices calling downstream services need clear error contracts for partial failures vs fatal failures.
Options:
1. Fail hard (HTTP 500) if any downstream fails.
2. Ignore all failures and return partial data for everything.
3. Classify `post-storage` as critical (failure yields HTTP 500) and `text`, `media`, `user` as non-critical (fail-fast, set `degraded: true`, return partial data).
Chosen: Option 3.
Why: Matches the real-world microservice contract defined in the system specification. Demonstrates fault tolerance and graceful degradation under chaos.
Trade-off: Callers must inspect the `degraded` flag in responses.
Revisit if: Topology requirements change.

## D-005: Healer Core Architecture, Baseline Profiling, and SLO Tracking
Date: 2026-10-19   Model: Gemini 2.5 Pro / pair-engineer
Context: Designing Healer control plane (port 8080) under strict low-overhead constraints without external message brokers.
Options:
1. External metrics store (Prometheus / InfluxDB) and external load test tool (JMeter / k6).
2. Heavy Spring Batch / Quartz scheduling.
3. In-process scheduled executors, rolling sliding-window statistical estimators, and embedded H2 file database.
Chosen: Option 3.
Why: Runs completely within single JVM process, zero external dependencies, fits inside Windows 8 GB RAM environment, offline capable.
Trade-off: Rolling windows and baseline profiles are stored in-memory with H2 checkpointing rather than distributed timeseries stores.
Revisit if: Multi-node distributed deployment is required.
