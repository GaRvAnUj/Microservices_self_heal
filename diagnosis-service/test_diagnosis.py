import pytest
from fastapi.testclient import TestClient
from main import app
from modify_inferencer import DATA_DIR, _make_graph_inputs
import json

client = TestClient(app)

def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"

def test_normal_system():
    payload = {
        "zScores": {
            "node:gateway:cpu_process": 0.5,
            "link:gateway->compose-post:avg_latency": 0.2
        }
    }
    response = client.post("/diagnose", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert not data["anomalyDetected"]
    assert data["targetEntity"] is None

def test_link_delay_disambiguation():
    # Link (compose-post -> text) latency is high (z = 6.5)
    # Callee text CPU is normal (z = 0.4)
    payload = {
        "zScores": {
            "link:compose-post->text:avg_latency": 6.5,
            "node:text:cpu_process": 0.4,
            "node:compose-post:cpu_process": 0.6
        }
    }
    response = client.post("/diagnose", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["anomalyDetected"]
    assert data["isLinkFault"]
    assert data["targetEntity"] == "compose-post->text"
    assert data["faultType"] == "link_delay"
    assert data["confidence"] >= 0.80

def test_link_loss_disambiguation():
    payload = {
        "zScores": {
            "link:compose-post->media:error_rate": 5.8,
            "link:compose-post->media:avg_latency": 0.5,
            "node:media:cpu_process": 0.2
        }
    }
    response = client.post("/diagnose", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["anomalyDetected"]
    assert data["isLinkFault"]
    assert data["targetEntity"] == "compose-post->media"
    assert data["faultType"] == "link_loss"

def test_callee_degraded_blames_node():
    # Link latency is high (z = 6.0), BUT callee text CPU is also high (z = 7.5)
    payload = {
        "zScores": {
            "link:compose-post->text:avg_latency": 6.0,
            "node:text:cpu_process": 7.5,
            "node:compose-post:cpu_process": 0.5
        }
    }
    response = client.post("/diagnose", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["anomalyDetected"]
    assert not data["isLinkFault"]
    assert data["targetEntity"] == "text"
    assert data["faultType"] == "cpu_hog"

def test_node_crash_diagnosis():
    payload = {
        "zScores": {
            "log:post-storage:DOWNSTREAM_ERROR": 6.2,
            "node:gateway:cpu_process": 0.3
        }
    }
    response = client.post("/diagnose", json=payload)
    assert response.status_code == 200
    data = response.json()
    assert data["anomalyDetected"]
    assert not data["isLinkFault"]
    assert data["targetEntity"] == "post-storage"
    assert data["faultType"] == "crash"

def test_modify_inference_is_used_when_signals_are_available(monkeypatch):
    class FakeInferencer:
        def diagnose(self, signals):
            assert signals == {"node:text:cpu_process": 0.95}
            return {
                "anomalyDetected": True,
                "targetEntity": "text",
                "confidence": 0.91,
                "diagnosisSource": "MODIFy",
                "nodeProbabilities": {"text": 0.98},
                "linkProbabilities": {},
            }

    monkeypatch.setattr("main._get_inferencer", lambda: FakeInferencer())
    response = client.post(
        "/diagnose",
        json={
            "zScores": {"node:text:cpu_process": 6.0},
            "signals": {"node:text:cpu_process": 0.95},
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["diagnosisSource"] == "MODIFy"
    assert data["anomalyDetected"]
    assert data["targetEntity"] == "text"
    assert data["faultType"] == "cpu_hog"

def test_link_fault_keeps_existing_disambiguation_with_modify_enabled(monkeypatch):
    def unexpected_model_load():
        raise AssertionError("MODIFy should not replace the existing pure-link path")

    monkeypatch.setattr("main._get_inferencer", unexpected_model_load)
    response = client.post(
        "/diagnose",
        json={
            "zScores": {
                "link:compose-post->text:avg_latency": 6.5,
                "node:text:cpu_process": 0.4,
            },
            "signals": {"node:text:cpu_process": 0.2},
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["diagnosisSource"] == "PYTHON_FASTAPI"
    assert data["isLinkFault"]
    assert data["targetEntity"] == "compose-post->text"
    assert data["faultType"] == "link_delay"

def test_link_candidates_can_be_disabled_without_disabling_modify(monkeypatch):
    class FakeInferencer:
        def diagnose(self, signals):
            return {
                "anomalyDetected": True,
                "targetEntity": "text",
                "confidence": 0.91,
                "diagnosisSource": "MODIFy",
                "nodeProbabilities": {"text": 0.98},
                "linkProbabilities": {},
            }

    monkeypatch.setattr("main._get_inferencer", lambda: FakeInferencer())
    response = client.post(
        "/diagnose",
        json={
            "zScores": {
                "link:compose-post->text:avg_latency": 6.5,
                "node:text:cpu_process": 0.4,
            },
            "signals": {"node:text:cpu_process": 0.2},
            "allowLinkDiagnosis": False,
        },
    )

    assert response.status_code == 200
    data = response.json()
    assert data["diagnosisSource"] == "MODIFy"
    assert not data["isLinkFault"]
    assert data["targetEntity"] == "text"
    assert data["faultType"] == "link_delay"

def test_modify_feature_projection_matches_training_shapes():
    metadata = json.loads((DATA_DIR / "metadata.json").read_text(encoding="utf-8"))
    signals = {
        f"node:{service}:{metric}": value
        for service in ("gateway", "compose-post", "text", "media", "user", "post-storage")
        for metric, value in (
            ("cpu_process", 0.2),
            ("cpu_system", 0.1),
            ("heap_used", 100_000_000),
            ("heap_committed", 120_000_000),
            ("request_count", 60),
        )
    }
    signals["link:gateway->compose-post:avg_latency"] = 50.0
    signals["link:gateway->compose-post:call_count"] = 60.0

    logs, metrics, traces = _make_graph_inputs(signals, metadata)

    assert logs.shape == (6, 9)
    assert metrics.shape == (6, 6, 7)
    assert traces.shape == (6, 6, 2)
    assert metrics[0, 0, 3] == pytest.approx(1.0)
    assert traces[0, 0, 0] == pytest.approx(0.05)
    assert logs[0, 7] == metadata["chunk_lenth"]

def test_modify_service_failure_is_reported_as_unavailable(monkeypatch):
    def fail_to_load():
        raise RuntimeError("checkpoint is missing")

    monkeypatch.setattr("main._get_inferencer", fail_to_load)
    response = client.post(
        "/diagnose",
        json={"signals": {"node:text:cpu_process": 0.2}},
    )

    assert response.status_code == 503
    assert "MODIFy inference is unavailable" in response.json()["detail"]
