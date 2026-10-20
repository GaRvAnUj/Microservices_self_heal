import pytest
from fastapi.testclient import TestClient
from main import app

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
