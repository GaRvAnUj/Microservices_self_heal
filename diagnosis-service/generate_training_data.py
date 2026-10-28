"""
generate_training_data.py
=========================
Generates MODIFy-compatible training chunks from our self-healing microservices platform.

Strategy:
  1. Run N chaos experiments (inject each known fault type onto each service).
  2. Poll /internal/telemetry every second to capture metrics + trace snapshots.
  3. Package all snapshots into MODIFy's chunk format (.pkl + metadata.json).

Output:
  ../MODIFy-main/codes/chunks/SHM/
      chunk_train.pkl
      chunk_test.pkl
      metadata.json

Usage:
  python generate_training_data.py [--experiments 3] [--duration 40] [--chunk-len 6]

Requirements:
  - Platform must be running (java -jar healer.jar on port 8080)
  - pip install requests numpy
"""

import argparse
import json
import os
import pickle
import random
import string
import time
from collections import defaultdict
from typing import Dict, List, Optional, Tuple

import numpy as np
import requests

# ---------------------------------------------------------------------------
# Platform topology: 6 microservices, 0-indexed
# ---------------------------------------------------------------------------
SERVICES = [
    "gateway",       # 0
    "compose-post",  # 1
    "text",          # 2
    "media",         # 3
    "user",          # 4
    "post-storage",  # 5
]
SERVICE2NID = {s: i for i, s in enumerate(SERVICES)}
NODE_NUM = len(SERVICES)

SERVICE_PORTS = {
    "gateway":       8081,
    "compose-post":  8082,
    "text":          8083,
    "media":         8084,
    "user":          8085,
    "post-storage":  8086,
}

EDGE_INFO = {
    "gateway":       ["compose-post"],
    "compose-post":  ["text", "media", "user", "post-storage"],
    "text":          [],
    "media":         [],
    "user":          [],
    "post-storage":  [],
}

EDGE_SRC, EDGE_DST = [], []
for src_svc, dst_svcs in EDGE_INFO.items():
    for dst_svc in dst_svcs:
        EDGE_SRC.append(SERVICE2NID[src_svc])
        EDGE_DST.append(SERVICE2NID[dst_svc])

FAULT_TYPES = ["cpu_stress", "memory_leak", "network_delay", "thread_exhaustion", "crash"]

METRIC_NAMES = [
    "cpu_usage_system", "cpu_usage_total", "cpu_usage_user",
    "memory_usage", "memory_working_set", "rx_bytes", "tx_bytes",
]
METRIC_NUM = len(METRIC_NAMES)
METRIC_SCALE = [1.0, 1.0, 1.0, 1e8, 1e8, 1e5, 1e5]
TRACE_NAMES = ["avg_latency_ms", "call_count"]
TRACE_SCALE = [1000.0, 300.0]

LOG_TEMPLATE_NUM = 8
EVENT_NUM = LOG_TEMPLATE_NUM + 1  # +1 for unseen

HEALER_BASE = "http://127.0.0.1:8080"


# ---------------------------------------------------------------------------
# Utilities
# ---------------------------------------------------------------------------

def _gen_chunk_id(used: set) -> str:
    src = string.ascii_letters + string.digits
    while True:
        cid = "".join(random.choices(src, k=8))
        if cid not in used:
            used.add(cid)
            return cid


# ---------------------------------------------------------------------------
# Telemetry polling
# ---------------------------------------------------------------------------

def poll_service_telemetry(port: int) -> Optional[Dict]:
    try:
        r = requests.get(f"http://127.0.0.1:{port}/internal/telemetry", timeout=2)
        if r.status_code == 200:
            return r.json()
    except Exception:
        pass
    return None


def collect_snapshot(t: int) -> Dict[str, Dict]:
    telemetry = {}
    for svc, port in SERVICE_PORTS.items():
        telemetry[svc] = poll_service_telemetry(port)

    edges = []
    for source_data in telemetry.values():
        if source_data:
            edges.extend(source_data.get("edgeStats", []))

    snapshot = {}
    for svc, data in telemetry.items():
        node = (data or {}).get("nodeMetrics", {})
        cpu_total = float(node.get("processCpuLoad", 0.0) or 0.0)
        cpu_system = float(node.get("systemCpuLoad", 0.0) or 0.0)
        mem_usage = float(node.get("heapUsedBytes", 0) or 0)
        working_set = float(node.get("heapCommittedBytes", 0) or 0)
        req_count = float(node.get("requestCount", 0) or 0)
        outgoing = [edge for edge in edges if edge.get("caller") == svc]
        incoming = [edge for edge in edges if edge.get("target") == svc]
        relevant_edges = outgoing or incoming
        avg_lat = (
            sum(float(edge.get("avgLatencyMs", 0) or 0) for edge in relevant_edges)
            / len(relevant_edges)
            if relevant_edges else 0.0
        )
        call_count = sum(float(edge.get("callCount", 0) or 0) for edge in outgoing)
        if not call_count:
            call_count = req_count
        snapshot[svc] = {
            "cpu_usage_system": cpu_system,
            "cpu_usage_total": cpu_total,
            "cpu_usage_user": max(0.0, cpu_total - cpu_system),
            "memory_usage": mem_usage,
            "memory_working_set": working_set,
            "rx_bytes": req_count * 1024.0,
            "tx_bytes": req_count * 512.0,
            "call_count": call_count,
            "avg_latency": avg_lat,
            "available": data is not None,
        }
    return snapshot


def inject_fault(service: str, fault_type: str) -> bool:
    try:
        payload = {"serviceName": service, "faultType": fault_type, "duration": 0}
        r = requests.post(f"{HEALER_BASE}/api/chaos/inject", json=payload, timeout=3)
        return r.status_code in (200, 201, 202)
    except Exception as e:
        print(f"  [WARN] Fault inject failed: {e}")
        return False


def stop_all_faults() -> None:
    try:
        requests.post(f"{HEALER_BASE}/api/chaos/stop-all", timeout=3)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Single experiment (LIVE — requires platform running)
# ---------------------------------------------------------------------------

def run_experiment(
    duration: int,
    culprit_service: Optional[str],
    fault_type: Optional[str],
) -> Tuple[List[int], List[Dict]]:
    timestamps, snapshots = [], []
    warmup = min(5, duration)

    print(f"  Warm-up ({warmup}s)...")
    for _ in range(warmup):
        t = int(time.time())
        timestamps.append(t)
        snapshots.append(collect_snapshot(t))
        time.sleep(1.0)

    if culprit_service and fault_type:
        print(f"  Injecting {fault_type} on {culprit_service}...")
        inject_fault(culprit_service, fault_type)

    remaining = duration - len(timestamps)
    for i in range(remaining):
        t = int(time.time())
        timestamps.append(t)
        snapshots.append(collect_snapshot(t))
        if (i + 1) % 10 == 0:
            print(f"  {i+1}/{remaining}s collected...")
        time.sleep(1.0)

    stop_all_faults()
    return timestamps, snapshots


# ---------------------------------------------------------------------------
# Single experiment (SYNTHETIC — no platform needed)
# ---------------------------------------------------------------------------

_RNG = random.Random(42)

def _base_snapshot() -> Dict[str, Dict]:
    """Generate a normal-traffic snapshot for all 6 services."""
    snap = {}
    for svc in SERVICES:
        cpu  = _RNG.uniform(0.05, 0.30)
        mem  = _RNG.uniform(50e6, 150e6)
        lat  = _RNG.uniform(5.0, 80.0)
        reqs = _RNG.uniform(50, 300)
        snap[svc] = {
            "cpu_usage_system":   cpu * 0.3,
            "cpu_usage_total":    cpu,
            "cpu_usage_user":     cpu * 0.7,
            "memory_usage":       mem,
            "memory_working_set": mem * 0.8,
            "rx_bytes":           reqs * 1024.0,
            "tx_bytes":           reqs * 512.0,
            "call_count":         reqs,
            "avg_latency":        lat,
        }
    return snap


def _inject_synthetic_fault(snap: Dict, svc: str, fault_type: str) -> Dict:
    """Apply synthetic anomaly patterns to a snapshot for a given fault type."""
    s = snap[svc]
    if fault_type == "cpu_stress":
        s["cpu_usage_total"]    = _RNG.uniform(0.85, 0.99)
        s["cpu_usage_system"]   = s["cpu_usage_total"] * 0.3
        s["cpu_usage_user"]     = s["cpu_usage_total"] * 0.7
        s["avg_latency"]        = _RNG.uniform(300, 800)
    elif fault_type == "memory_leak":
        s["memory_usage"]       = _RNG.uniform(450e6, 600e6)
        s["memory_working_set"] = s["memory_usage"] * 0.9
        s["cpu_usage_total"]    = _RNG.uniform(0.40, 0.65)
    elif fault_type == "network_delay":
        s["avg_latency"]        = _RNG.uniform(800, 3000)
        s["call_count"]         = _RNG.uniform(5, 30)   # dropped connections
        s["rx_bytes"]           = s["call_count"] * 1024.0
        s["tx_bytes"]           = s["call_count"] * 512.0
    elif fault_type == "thread_exhaustion":
        s["cpu_usage_total"]    = _RNG.uniform(0.70, 0.90)
        s["avg_latency"]        = _RNG.uniform(500, 2000)
        s["call_count"]         = _RNG.uniform(1, 10)
    elif fault_type == "crash":
        for key in s:
            s[key] = 0.0
        s["avg_latency"] = 0.0
    return snap


def synthetic_experiment(
    duration: int,
    culprit_service: Optional[str],
    fault_type: Optional[str],
) -> Tuple[List[int], List[Dict]]:
    """Generate synthetic telemetry without hitting live services."""
    timestamps, snapshots = [], []
    warmup = min(5, duration)
    base_t = int(time.time())

    for i in range(duration):
        t = base_t + i
        timestamps.append(t)
        snap = _base_snapshot()
        # Apply fault after warm-up
        if culprit_service and fault_type and i >= warmup:
            snap = _inject_synthetic_fault(snap, culprit_service, fault_type)
        snapshots.append(snap)

    return timestamps, snapshots


# ---------------------------------------------------------------------------
# Convert snapshots to MODIFy arrays
# ---------------------------------------------------------------------------

def snapshots_to_metrics(snapshots: List[Dict]) -> np.ndarray:
    T = len(snapshots)
    arr = np.zeros((T, NODE_NUM, METRIC_NUM))
    for t_i, snap in enumerate(snapshots):
        for nid, svc in enumerate(SERVICES):
            for m_i, mname in enumerate(METRIC_NAMES):
                arr[t_i, nid, m_i] = snap.get(svc, {}).get(mname, 0.0)
    return arr / np.asarray(METRIC_SCALE, dtype=np.float32)


def snapshots_to_traces(snapshots: List[Dict]) -> np.ndarray:
    T = len(snapshots)
    arr = np.zeros((T, NODE_NUM, 2))
    for t_i, snap in enumerate(snapshots):
        for nid, svc in enumerate(SERVICES):
            arr[t_i, nid, 0] = snap.get(svc, {}).get("avg_latency", 0.0)
            arr[t_i, nid, 1] = snap.get(svc, {}).get("call_count",  0.0)
    return arr / np.asarray(TRACE_SCALE, dtype=np.float32)


def snapshots_to_logs(snapshots: List[Dict]) -> np.ndarray:
    T = len(snapshots)
    arr = np.zeros((T, NODE_NUM, EVENT_NUM))
    for t_i, snap in enumerate(snapshots):
        for nid, svc in enumerate(SERVICES):
            d   = snap.get(svc, {})
            cpu = d.get("cpu_usage_total", 0.0)
            mem = d.get("memory_usage",    0.0)
            lat = d.get("avg_latency",     0.0)
            arr[t_i, nid, 0] = max(0.0, cpu - 0.5)
            arr[t_i, nid, 1] = max(0.0, cpu - 0.8)
            arr[t_i, nid, 2] = max(0.0, mem / 1e8 - 0.5)
            arr[t_i, nid, 3] = max(0.0, lat / 1000.0 - 0.1)
            arr[t_i, nid, 4] = 1.0 if cpu > 0.9 else 0.0
            arr[t_i, nid, 5] = 1.0 if mem > 5e8 else 0.0
            arr[t_i, nid, 6] = 1.0 if lat > 2000 else 0.0
            arr[t_i, nid, 7] = 1.0 if d.get("available", True) else 0.0
    return arr


# ---------------------------------------------------------------------------
# Build sliding-window chunks
# ---------------------------------------------------------------------------

def build_chunks(
    snapshots:       List[Dict],
    culprit_service: Optional[str],
    fault_start_idx: int,
    chunk_len:       int,
    chunk_ids_used:  set,
    split_name:      str,
    experiment_id:   str,
    fault_type:      Optional[str] = None,
) -> Dict[str, Dict]:
    T = len(snapshots)
    culprit_nid = SERVICE2NID[culprit_service] if culprit_service else -1

    metrics_raw = snapshots_to_metrics(snapshots)
    traces_raw  = snapshots_to_traces(snapshots)
    logs_raw    = snapshots_to_logs(snapshots)

    chunks = {}

    for s in range(T - chunk_len + 1):
        e = s + chunk_len - 1
        cid = _gen_chunk_id(chunk_ids_used)

        label = culprit_nid if (culprit_nid >= 0 and s >= fault_start_idx) else -1

        # Shapes expected by MODIFy chunkDataset:
        #   metrics: [node_num, chunk_len, metric_num]
        #   traces:  [node_num, chunk_len, 2]
        #   logs:    [node_num, event_num]
        chunk_metrics = metrics_raw[s:e+1].transpose(1, 0, 2)   # [N, L, M]
        chunk_traces  = traces_raw[s:e+1].transpose(1, 0, 2)    # [N, L, 2]
        chunk_logs    = logs_raw[s:e+1].sum(axis=0)             # [N, E]

        chunks[cid] = {
            "metrics":  chunk_metrics,
            "traces":   chunk_traces,
            "logs":     chunk_logs,
            "culprit":  label,
            "split":    split_name,
            "experiment_id": experiment_id,
            "fault_type": fault_type,
            "interval": (s, e),
        }

    return chunks


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def generate(args) -> None:
    if args.experiments < 2 or args.normal_runs < 2:
        raise ValueError("Use at least 2 experiments and 2 normal runs for separate train/test splits.")
    if args.duration <= args.chunk_len:
        raise ValueError("--duration must be greater than --chunk-len.")
    if not 0.0 < args.test_ratio < 1.0:
        raise ValueError("--test-ratio must be between 0 and 1.")

    print("=" * 60)
    print("  MODIFy Training Data Generator")
    if args.synthetic:
        print("  Mode: SYNTHETIC (no platform needed)")
    else:
        print("  Mode: LIVE (platform must be running on 8081-8086)")
    print("=" * 60)

    # Select experiment runner
    exp_fn = synthetic_experiment if args.synthetic else run_experiment

    all_chunks: Dict[str, Dict] = {}
    used_ids:   set = set()

    # Normal runs
    print(f"\n[NORMAL] Collecting {args.normal_runs} normal run(s)...")
    normal_test_runs = max(1, round(args.normal_runs * args.test_ratio))
    for i in range(args.normal_runs):
        print(f"  Normal run {i+1}/{args.normal_runs}")
        _, snaps = exp_fn(args.duration, None, None)
        split_name = "test" if i >= args.normal_runs - normal_test_runs else "train"
        new = build_chunks(
            snaps,
            None,
            len(snaps) + 1,
            args.chunk_len,
            used_ids,
            split_name,
            f"normal-{i}",
        )
        all_chunks.update(new)
        print(f"  → {len(new)} chunks")

    # Fault runs
    combos = [(svc, ft) for svc in SERVICES for ft in FAULT_TYPES]
    fault_test_runs = max(1, round(args.experiments * args.test_ratio))
    print(f"\n[FAULT] {len(combos)} scenarios × {args.experiments} repeat(s)...")
    for idx, (svc, ft) in enumerate(combos):
        for rep in range(args.experiments):
            print(f"  [{idx+1}/{len(combos)}] {ft} on {svc} (rep {rep+1})")
            _, snaps = exp_fn(args.duration, svc, ft)
            split_name = "test" if rep >= args.experiments - fault_test_runs else "train"
            new = build_chunks(
                snaps,
                svc,
                5,
                args.chunk_len,
                used_ids,
                split_name,
                f"fault-{svc}-{ft}-{rep}",
                ft,
            )
            all_chunks.update(new)
            labeled = sum(1 for c in new.values() if c["culprit"] >= 0)
            print(f"  → {len(new)} chunks, {labeled} labeled")

    # Save
    out_dir = os.path.join(
        os.path.dirname(os.path.abspath(__file__)),
        "..", "MODIFy-main", "codes", "chunks", "SHM"
    )
    os.makedirs(out_dir, exist_ok=True)

    train_chunks = {k: v for k, v in all_chunks.items() if v["split"] == "train"}
    test_chunks  = {k: v for k, v in all_chunks.items() if v["split"] == "test"}

    with open(os.path.join(out_dir, "chunk_train.pkl"), "wb") as fw:
        pickle.dump(train_chunks, fw)
    with open(os.path.join(out_dir, "chunk_test.pkl"), "wb") as fw:
        pickle.dump(test_chunks, fw)

    metadata = {
        "node_num":    NODE_NUM,
        "metric_num":  METRIC_NUM,
        "event_num":   EVENT_NUM,
        "chunk_lenth": args.chunk_len,
        "chunk_num":   len(all_chunks),
        "edges":       [EDGE_SRC, EDGE_DST],
        "services":    SERVICES,
        "service2nid": SERVICE2NID,
        "metric_names": METRIC_NAMES,
        "metric_scale": METRIC_SCALE,
        "trace_names": TRACE_NAMES,
        "trace_scale": TRACE_SCALE,
    }
    with open(os.path.join(out_dir, "metadata.json"), "w") as fw:
        json.dump(metadata, fw, indent=2)

    # Summary
    label_dist: Dict[int, int] = defaultdict(int)
    for c in all_chunks.values():
        label_dist[c["culprit"]] += 1

    train_f = sum(1 for c in train_chunks.values() if c["culprit"] >= 0)
    test_f  = sum(1 for c in test_chunks.values()  if c["culprit"] >= 0)

    print("\n" + "=" * 60)
    print("  DATA GENERATION COMPLETE")
    print("=" * 60)
    print(f"  Output dir  : {out_dir}")
    print(f"  Total chunks: {len(all_chunks)}")
    print(f"  Train       : {len(train_chunks)} ({train_f} faulty)")
    print(f"  Test        : {len(test_chunks)}  ({test_f} faulty)")
    print(f"  Normal      : {label_dist.get(-1, 0)}")
    for nid, svc in enumerate(SERVICES):
        print(f"  Node {nid} ({svc:20s}): {label_dist.get(nid, 0)} faulty")
    print()
    print("  Next: run train_modify.py with .venv-modify-training\\Scripts\\python.exe")
    print("=" * 60)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Generate MODIFy training data")
    parser.add_argument("--synthetic",    action="store_true", help="Generate data synthetically (no live platform needed)")
    parser.add_argument("--experiments",  type=int,   default=3,   help="Repeats per fault scenario")
    parser.add_argument("--normal-runs",  type=int,   default=5,   help="Normal (no fault) runs")
    parser.add_argument("--duration",     type=int,   default=40,  help="Seconds (or ticks) per experiment")
    parser.add_argument("--chunk-len",    type=int,   default=6,   help="Window length (seconds)")
    parser.add_argument("--test-ratio",   type=float, default=0.3, help="Test split ratio")
    args = parser.parse_args()
    generate(args)
