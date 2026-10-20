"""
Python Diagnosis Service (FastAPI)
Exposes POST /diagnose on port 5000 (127.0.0.1).
Implements probabilistic fault localization with N1 link vs node disambiguation.
"""

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from typing import Dict, Optional, Any
import math
import uvicorn

app = FastAPI(title="Self-Healing Microservices Diagnosis Service", version="1.0.0")

class DiagnoseRequest(BaseModel):
    zScores: Dict[str, float] = Field(default_factory=dict)
    signals: Dict[str, float] = Field(default_factory=dict)

class DiagnoseResponse(BaseModel):
    anomalyDetected: bool
    targetEntity: Optional[str] = None
    faultType: Optional[str] = None
    isLinkFault: bool = False
    confidence: float = 0.0
    diagnosisSource: str = "PYTHON_FASTAPI"
    nodeProbabilities: Dict[str, float] = Field(default_factory=dict)
    linkProbabilities: Dict[str, float] = Field(default_factory=dict)

@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "UP", "service": "diagnosis-service", "version": "1.0.0"}

@app.get("/")
def root() -> Dict[str, str]:
    return {"message": "Diagnosis Service (FastAPI) is running on 127.0.0.1:5000"}

@app.post("/diagnose", response_model=DiagnoseResponse)
def diagnose(req: DiagnoseRequest) -> DiagnoseResponse:
    z_scores = req.zScores or {}
    if not z_scores:
        return DiagnoseResponse(
            anomalyDetected=False,
            targetEntity=None,
            faultType=None,
            isLinkFault=False,
            confidence=0.0
        )

    link_lat_z: Dict[str, float] = {}
    link_err_z: Dict[str, float] = {}
    node_cpu_z: Dict[str, float] = {}
    node_log_z: Dict[str, float] = {}

    for key, z in z_scores.items():
        if key.startswith("link:") and "->" in key:
            # e.g. "link:compose-post->text:avg_latency"
            try:
                colon_idx = key.index(":", 5)
                link_name = key[5:colon_idx]
                metric = key[colon_idx + 1:]
                if metric == "avg_latency":
                    link_lat_z[link_name] = max(link_lat_z.get(link_name, 0.0), z)
                elif metric == "error_rate":
                    link_err_z[link_name] = max(link_err_z.get(link_name, 0.0), z)
            except ValueError:
                pass
        elif key.startswith("node:"):
            # e.g. "node:text:cpu_process"
            parts = key.split(":")
            if len(parts) >= 3:
                node_name = parts[1]
                metric = parts[2]
                if "cpu" in metric:
                    node_cpu_z[node_name] = max(node_cpu_z.get(node_name, 0.0), z)
        elif key.startswith("log:"):
            # e.g. "log:text:DOWNSTREAM_ERROR"
            parts = key.split(":")
            if len(parts) >= 3:
                node_name = parts[1]
                node_log_z[node_name] = max(node_log_z.get(node_name, 0.0), z)

    # 1. Candidate link abnormality evaluation
    all_links = set(link_lat_z.keys()) | set(link_err_z.keys())
    worst_link: Optional[str] = None
    max_link_z: float = 3.99 # threshold z >= 4.0
    is_delay: bool = True

    for link in all_links:
        lat_z = link_lat_z.get(link, 0.0)
        err_z = link_err_z.get(link, 0.0)
        higher_z = max(lat_z, err_z)
        if higher_z > max_link_z:
            max_link_z = higher_z
            worst_link = link
            is_delay = (lat_z >= err_z)

    # 2. Candidate node abnormality evaluation
    all_nodes = set(node_cpu_z.keys()) | set(node_log_z.keys())
    worst_node: Optional[str] = None
    max_node_z: float = 3.99
    node_fault_type: str = "crash"

    for node in all_nodes:
        cpu_z = node_cpu_z.get(node, 0.0)
        log_z = node_log_z.get(node, 0.0)
        higher_z = max(cpu_z, log_z)
        if higher_z > max_node_z:
            max_node_z = higher_z
            worst_node = node
            node_fault_type = "cpu_hog" if cpu_z >= log_z else "crash"

    # Compute Softmax-like probabilities across candidates
    scores: Dict[str, float] = {}
    for l in all_links:
        scores[l] = max(0.0, max(link_lat_z.get(l, 0.0), link_err_z.get(l, 0.0)))
    for n in all_nodes:
        scores[n] = max(0.0, max(node_cpu_z.get(n, 0.0), node_log_z.get(n, 0.0)))

    # Compute softmax
    sum_exp = sum(math.exp(min(val, 20.0)) for val in scores.values()) if scores else 1.0
    node_probs: Dict[str, float] = {}
    link_probs: Dict[str, float] = {}

    for k, v in scores.items():
        prob = round(math.exp(min(v, 20.0)) / sum_exp, 4)
        if "->" in k:
            link_probs[k] = prob
        else:
            node_probs[k] = prob

    # 3. Disambiguation (N1): Link vs Callee Node
    if worst_link is not None:
        parts = worst_link.split("->")
        callee = parts[1].strip() if len(parts) > 1 else ""
        callee_cpu = node_cpu_z.get(callee, 0.0)
        callee_log = node_log_z.get(callee, 0.0)
        callee_abnormality = max(callee_cpu, callee_log)

        if callee_abnormality < 3.0:
            # Callee node is healthy -> Pure LINK FAULT
            fault_type = "link_delay" if is_delay else "link_loss"
            conf = min(0.99, max(0.80, max_link_z / 10.0))
            return DiagnoseResponse(
                anomalyDetected=True,
                targetEntity=worst_link,
                faultType=fault_type,
                isLinkFault=True,
                confidence=conf,
                nodeProbabilities=node_probs,
                linkProbabilities=link_probs
            )
        else:
            # Callee node itself is degraded -> Node fault
            fault_type = "cpu_hog" if callee_cpu >= callee_log else "crash"
            conf = min(0.99, max(0.80, callee_abnormality / 10.0))
            return DiagnoseResponse(
                anomalyDetected=True,
                targetEntity=callee,
                faultType=fault_type,
                isLinkFault=False,
                confidence=conf,
                nodeProbabilities=node_probs,
                linkProbabilities=link_probs
            )

    # 4. Pure Node Fault (No link anomaly)
    if worst_node is not None:
        conf = min(0.99, max(0.80, max_node_z / 10.0))
        return DiagnoseResponse(
            anomalyDetected=True,
            targetEntity=worst_node,
            faultType=node_fault_type,
            isLinkFault=False,
            confidence=conf,
            nodeProbabilities=node_probs,
            linkProbabilities=link_probs
        )

    # 5. Normal system behavior
    return DiagnoseResponse(
        anomalyDetected=False,
        targetEntity=None,
        faultType=None,
        isLinkFault=False,
        confidence=0.0,
        nodeProbabilities=node_probs,
        linkProbabilities=link_probs
    )

if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=5000, log_level="info")
