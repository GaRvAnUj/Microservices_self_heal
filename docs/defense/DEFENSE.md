# DEFENSE: Self-Healing Microservices Platform

## 1. What it does (2 sentences)
A lightweight self-healing platform for Java microservices demonstrating link-level fault localization, non-disruptive policy tuning, and recovery-failure attribution. It directly benchmarks against traditional blind service-restarting baselines under strict single-machine resource limits (Windows, 8 GB RAM, -Xmx128m per JVM).

---

## 2. How it works (sketch the flow)

### A. Request & Topology Flow
```
LoadGenerator (Healer:8080)
      │ [HTTP GET /work ~7 req/s]
      ▼
   gateway (8081)
      │ [LinkClient]
      ▼
 compose-post (8082)
      ├── [LinkClient] ──► text (8083) [leaf]
      ├── [LinkClient] ──► media (8084) [leaf]
      ├── [LinkClient] ──► user (8085) [leaf]
      └── [LinkClient] ──► post-storage (8086) [leaf, CRITICAL]
```

### B. Closed-Loop MAPE-K Control Cycle
```
┌────────────────────────────────────────────────────────────────────────┐
│                        MONITOR (Telemetry & SLO)                       │
│  - LoadGenerator monitors p95 (<400ms) and Error Rate (<3%)            │
│  - TelemetryCollectorService polls GET /internal/telemetry every 3s    │
│  - Aggregates rolling 3-window means (9s sample smoothing)             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        ANALYZE (Detector & N1)                         │
│  - Incident triggers if SLO breached OR any signal z >= 4 for 2 windows │
│  - StatisticalLocalizer & Python FastAPI (/diagnose) joint ranking     │
│  - N1 Disambiguation: If link (A->B) z >= 4 but callee B CPU z < 3     │
│    --> Classified as pure LINK FAULT (link_delay or link_loss)         │
│    If callee B CPU z >= 3 --> Classified as NODE FAULT (cpu_hog/crash) │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        PLAN & EXECUTE (N2)                             │
│  - Baseline Mode: Blindly restarts highest-abnormality service         │
│  - Proposed Mode: Queries Q-policy table from embedded H2              │
│    - Node Fault: Restarts process via ServiceManager (ProcessBuilder)  │
│    - Link Fault: PUT /internal/policy/{target} on caller (250ms timeout│
│      zero retries, or circuit breaker) --> ZERO DOWNTIME               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                        KNOWLEDGE (N3 Attribution)                      │
│  - Verification window: Waits 3 telemetry windows (9s)                 │
│  - If SLO recovered: SUCCESS -> Rewards Q (q <- q + 0.3*(1-q)), saves   │
│    training sample to labelled_samples                                 │
│  - If SLO still violated:                                              │
│    - Acted on link & callee z >= 4 --> WRONG_TARGET (shift suspect)    │
│    - Target z >= 4 & no competitor --> INEFFECTIVE_ACTION (q <- q*0.3, │
│      tries alternative action e.g. breaker)                            │
│    - Target z < 2.5 & other node z >= 4 --> WRONG_TARGET (pivot)       │
│    - Otherwise --> INCONCLUSIVE                                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Key decisions and why X over Y

1. **Embedded H2 Database over Prometheus / InfluxDB**:
   - *Chosen*: File-based H2 database (`./data/healerdb`).
   - *Why*: Strict 8 GB RAM environment with zero external infrastructure. H2 runs completely in-process within the Healer JVM with zero socket/daemon overhead and persists incidents, Q-tables, and labelled samples reliably.
   - *Trade-off*: Lacks distributed multi-node clustering, but perfectly suited for single-node low-overhead control planes.

2. **In-Process Scheduled Executors over Spring Batch / Quartz**:
   - *Chosen*: Lightweight `ScheduledExecutorService` with non-blocking concurrency.
   - *Why*: Eliminates database locks and multi-megabyte dependency footprints. Provides deterministic sub-millisecond dispatch for telemetry polling (3s) and load generation (7 req/s).

3. **Native OS Process Management over Docker / Kubernetes**:
   - *Chosen*: `ProcessBuilder` with `-Xmx128m` memory limits.
   - *Why*: Docker Desktop on Windows consumes 2–4 GB of idle RAM (WSL2 VM), exceeding system limits. Native OS processes run with minimal memory footprint (~65 MB heap per service).

4. **Self-Contained HTML5 Canvas & SVG over CDN Libraries (Chart.js / vis-network)**:
   - *Chosen*: Bespoke canvas topology and sliding-chart renderers in `app.js` and `style.css`.
   - *Why*: Complies with 100% offline requirements (no CDN downloads permitted). Tailored specifically to the **Indeed Minimal** design system (`#004FCB` primary blue, clean cards, 8px radius) with zero external JavaScript dependencies.

5. **Decoupled Python FastAPI Service over In-Process JNI / Jython**:
   - *Chosen*: Standalone FastAPI service on port 5000 with 1s timeout and graceful Java fallback.
   - *Why*: Clean language boundary. Avoids brittle native C/C++ compilation for JNI while enabling standard Python data science tooling (`scipy`, `numpy`, `PyTorch`). Healer's `DiagnosisClient` gracefully falls back to built-in Java statistical localization if Python is stopped.

---

## 4. Data structures and why

1. **`ConcurrentLinkedDeque<RequestRecord>` (Sliding Window in LoadGenerator)**:
   - *Why*: Lock-free double-ended queue tracking millisecond timestamps and latencies. Enables $O(1)$ push to the tail and $O(1)$ pruning of stale records older than 10 seconds without thread contention.
2. **`ConcurrentHashMap<String, LinkFaultEntry>` (ChaosRegistry)**:
   - *Why*: Keyed by `"caller->target"`. Provides atomic reads for microservices polling link impairments every 1 second without holding synchronization locks during HTTP requests.
3. **`Deque<Map<String, Double>>` (Rolling 3-Window Buffer in Collector)**:
   - *Why*: Maintains raw snapshots across the last 3 telemetry cycles (9 seconds). Computes an arithmetic mean smoothing out network jitter and temporary GC pauses before evaluating statistical z-scores.
4. **H2 `q_policy` Table `(fault_type, action_name, q_value, update_count)`**:
   - *Why*: Compact, indexed relational representation of the reinforcement learning policy matrix. Supports instant queries for highest Q-score action and atomic reward/penalty updates.

---

## 5. How a malicious actor could abuse it

1. **Adversarial Noise Injection (Flapping & Resource Exhaustion)**:
   - *Attack*: An attacker sends intermittent traffic bursts designed to artificially drive $z$-scores above 4.0, attempting to trigger continuous process restarts of microservices.
   - *Defense*: The detector enforces a dual-condition guard: SLO must be breached OR an anomaly must persist for 2 consecutive 3-window samples (6 seconds). Additionally, the incident state machine limits restart attempts to 3 before escalating, preventing infinite flapping.
2. **Port Hijacking & Unauthorized Chaos Injection**:
   - *Attack*: Triggering `POST /chaos/crash` or `/chaos/link` to cause platform denial-of-service.
   - *Defense*: All microservice control endpoints and the Healer API strictly bind to loopback (`127.0.0.1`). External network interfaces are completely closed.
3. **Poisoning the Q-Policy Table**:
   - *Attack*: Generating synthetic incidents that force bad attribution outcomes, reducing Q-scores for effective actions.
   - *Defense*: Ineffective action penalties are bounded by a minimum floor, and successful recovery rewards increase confidence towards 1.0 ($q \leftarrow q + 0.3(1-q)$). Training samples saved to `labelled_samples` require complete end-to-end SLO recovery verification before being committed.

---

## 6. Where it fails and how we notice

1. **Multi-Point Correlated Failures (Simultaneous Link and Node Fault)**:
   - *Failure Mode*: If a link degrades while the callee node is also experiencing a memory leak, link latency may mask callee degradation.
   - *Detection & Recovery*: **N3 Attribution** handles this. If tuning the link fails to restore system SLO after 3 evaluation windows, post-action inspection detects callee node $z \ge 4.0$. The outcome is classified as `WRONG_TARGET`, shifting the suspect to the callee node and restarting it on the next step.
2. **Concept Drift / Legitimate Organic Traffic Spikes**:
   - *Failure Mode*: A permanent 3x increase in genuine customer load causes baseline z-scores to stay elevated, triggering false-positive incident alarms.
   - *Detection*: Noticed when incidents open while error rates remain near zero and gateway throughput is elevated.
   - *Mitigation*: The `BaselineProfileManager` supports retraining after warmup traffic under new steady-state conditions.
3. **Ambiguous Transient Metrics**:
   - *Failure Mode*: If a metric fluctuates between $z = 2.5$ and $z = 4.0$ during the verification phase.
   - *Handling*: N3 attribution returns `INCONCLUSIVE`, avoiding erroneous Q-table penalties until a definitive signal state is reached.

---

## 7. What I would change with more time

1. **eBPF / Windows Filtering Platform (WFP) Link Impairment**:
   - Replace application-level HTTP interceptors with kernel-level packet delay and loss injection for true socket-layer chaos experimentation.
2. **Dynamic Mesh Routing & Backpressure**:
   - Allow `compose-post` to dynamically route around degraded links using alternative path replicas rather than relying solely on link policy timeouts and fallbacks.
3. **Deep Learning Online Adaptation**:
   - Ingest `labelled_samples` into a continuous PyTorch neural network training loop in the `diagnosis-service`, migrating from tabular Q-learning to deep reinforcement learning.
4. **Automated Baseline Refresh Trigger**:
   - Implement an automated Mann-Whitney or Kolmogorov-Smirnov drift test that automatically triggers baseline retraining when underlying traffic distribution permanently shifts without errors.
