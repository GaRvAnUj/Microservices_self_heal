"""Runtime adapter for the unmodified MODIFy graph model."""

import importlib.util
import math
import threading
from pathlib import Path
from typing import Any

import numpy as np


ROOT_DIR = Path(__file__).resolve().parents[1]
MODIFY_CODES_DIR = ROOT_DIR / "MODIFy-main" / "codes"
DATA_DIR = MODIFY_CODES_DIR / "chunks" / "SHM"
DEFAULT_CHECKPOINT = ROOT_DIR / "diagnosis-service" / "models" / "modify-shm" / "best_model.pt"
SERVICE_NAMES = ("gateway", "compose-post", "text", "media", "user", "post-storage")


def _number(value: Any) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return 0.0
    return number if math.isfinite(number) else 0.0


def _make_snapshot(signals: dict[str, float]) -> dict[str, dict[str, float]]:
    outgoing: dict[str, list[tuple[str, float, float]]] = {name: [] for name in SERVICE_NAMES}
    incoming: dict[str, list[tuple[str, float, float]]] = {name: [] for name in SERVICE_NAMES}
    service_set = set(SERVICE_NAMES)

    for key, value in signals.items():
        if not key.startswith("link:") or not key.endswith(":avg_latency"):
            continue
        link = key[5:-len(":avg_latency")]
        if "->" not in link:
            continue
        caller, target = (part.strip() for part in link.split("->", maxsplit=1))
        if caller not in service_set or target not in service_set:
            continue
        prefix = f"link:{caller}->{target}:"
        calls = _number(signals.get(prefix + "call_count", 0.0))
        edge = (target, max(0.0, _number(value)), max(0.0, calls))
        outgoing[caller].append(edge)
        incoming[target].append((caller, edge[1], edge[2]))

    snapshots: dict[str, dict[str, float]] = {}
    for name in SERVICE_NAMES:
        prefix = f"node:{name}:"
        available = any(key.startswith(prefix) for key in signals)
        cpu = max(0.0, _number(signals.get(prefix + "cpu_process", 0.0)))
        cpu_system = max(0.0, _number(signals.get(prefix + "cpu_system", 0.0)))
        memory = max(0.0, _number(signals.get(prefix + "heap_used", 0.0)))
        committed = max(0.0, _number(signals.get(prefix + "heap_committed", 0.0)))
        request_count = max(0.0, _number(signals.get(prefix + "request_count", 0.0)))
        edges_for_service = outgoing[name] or incoming[name]
        latency = (
            sum(edge[1] for edge in edges_for_service) / len(edges_for_service)
            if edges_for_service else 0.0
        )
        calls = sum(edge[2] for edge in outgoing[name])
        if not calls:
            calls = request_count
        calls = min(calls, 300.0)
        snapshots[name] = {
            "cpu_usage_system": cpu_system,
            "cpu_usage_total": cpu,
            "cpu_usage_user": max(0.0, cpu - cpu_system),
            "memory_usage": memory,
            "memory_working_set": committed,
            "rx_bytes": calls * 1024.0,
            "tx_bytes": calls * 512.0,
            "avg_latency": latency,
            "call_count": calls,
            "available": float(available),
        }
    return snapshots


def _make_graph_inputs(
    signals: dict[str, float], metadata: dict[str, Any]
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    chunk_len = int(metadata["chunk_lenth"])
    metric_names = metadata["metric_names"]
    metric_scale = np.asarray(metadata["metric_scale"], dtype=np.float32)
    trace_scale = np.asarray(metadata["trace_scale"], dtype=np.float32)
    snapshots = _make_snapshot(signals)
    metrics = np.zeros((len(SERVICE_NAMES), chunk_len, len(metric_names)), dtype=np.float32)
    traces = np.zeros((len(SERVICE_NAMES), chunk_len, 2), dtype=np.float32)
    logs = np.zeros((len(SERVICE_NAMES), 9), dtype=np.float32)

    for node_id, name in enumerate(SERVICE_NAMES):
        snapshot = snapshots[name]
        raw_metrics = np.asarray(
            [snapshot.get(metric, 0.0) for metric in metric_names], dtype=np.float32
        )
        metrics[node_id] = raw_metrics / metric_scale
        traces[node_id, :, 0] = snapshot["avg_latency"] / trace_scale[0]
        traces[node_id, :, 1] = snapshot["call_count"] / trace_scale[1]

        cpu = snapshot["cpu_usage_total"]
        memory = snapshot["memory_usage"]
        latency = snapshot["avg_latency"]
        events = np.asarray(
            [
                max(0.0, cpu - 0.5),
                max(0.0, cpu - 0.8),
                max(0.0, memory / 1e8 - 0.5),
                max(0.0, latency / 1000.0 - 0.1),
                float(cpu > 0.9),
                float(memory > 5e8),
                float(latency > 2000.0),
                snapshot["available"],
                0.0,
            ],
            dtype=np.float32,
        )
        logs[node_id] = events * chunk_len
    return logs, metrics, traces


class ModifyInferencer:
    def __init__(
        self,
        checkpoint_path: Path = DEFAULT_CHECKPOINT,
        data_dir: Path = DATA_DIR,
    ) -> None:
        import torch
        import dgl

        checkpoint_path = Path(checkpoint_path)
        data_dir = Path(data_dir)
        if not checkpoint_path.is_file():
            raise FileNotFoundError(f"MODIFy checkpoint not found: {checkpoint_path}")
        with (data_dir / "metadata.json").open(encoding="utf-8") as stream:
            self.metadata = __import__("json").load(stream)

        self.device = torch.device("cpu")
        checkpoint = torch.load(checkpoint_path, map_location=self.device)
        if checkpoint.get("metadata") != self.metadata:
            raise ValueError("MODIFy checkpoint and active dataset metadata do not match.")

        model_path = MODIFY_CODES_DIR / "model.py"
        spec = importlib.util.spec_from_file_location("modify_runtime_model", model_path)
        if spec is None or spec.loader is None:
            raise RuntimeError(f"Unable to load MODIFy model source: {model_path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        model_cls = module.MainModel
        self.model = model_cls(
            int(self.metadata["event_num"]),
            int(self.metadata["metric_num"]),
            int(self.metadata["node_num"]),
            self.device,
            **checkpoint["model_parameters"],
        ).to(self.device)
        self.model.load_state_dict(checkpoint["model_state_dict"])
        self.model.eval()
        self.edges = self.metadata["edges"]
        self.torch = torch
        self.dgl = dgl
        self._lock = threading.Lock()

    def diagnose(self, signals: dict[str, float]) -> dict[str, Any]:
        torch = self.torch
        logs, metrics, traces = _make_graph_inputs(signals, self.metadata)
        graph = self.dgl.graph(
            (self.edges[0], self.edges[1]), num_nodes=int(self.metadata["node_num"])
        )
        graph.ndata["logs"] = torch.from_numpy(logs)
        graph.ndata["metrics"] = torch.from_numpy(metrics)
        graph.ndata["traces"] = torch.from_numpy(traces)

        with self._lock, torch.random.fork_rng(devices=[]):
            torch.manual_seed(42)
            with torch.inference_mode():
                embeddings = self.model.encoder(graph.to(self.device))
                detection_probabilities = torch.softmax(
                    self.model.detector(embeddings), dim=-1
                )[0]
                node_probabilities = torch.softmax(
                    self.model.locator(embeddings), dim=-1
                )[0]

        detection_confidence = float(detection_probabilities[1].item())
        node_id = int(node_probabilities.argmax().item())
        anomaly_detected = detection_confidence >= 0.5
        target = SERVICE_NAMES[node_id] if anomaly_detected else None
        return {
            "anomalyDetected": anomaly_detected,
            "targetEntity": target,
            "faultType": None,
            "isLinkFault": False,
            "confidence": (
                detection_confidence * float(node_probabilities[node_id].item())
                if anomaly_detected else 0.0
            ),
            "diagnosisSource": "MODIFy",
            "nodeProbabilities": {
                name: round(float(node_probabilities[index].item()), 6)
                for index, name in enumerate(SERVICE_NAMES)
            },
            "linkProbabilities": {},
            "detectionConfidence": detection_confidence,
        }
