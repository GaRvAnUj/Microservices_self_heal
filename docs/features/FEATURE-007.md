# FEATURE-007: Python Diagnosis Service (FastAPI) & Process Integration

## Assumptions Log
| Item | Assumption Description | Status |
| :--- | :--- | :--- |
| A-1  | Python 3.10+ is available with pre-installed `fastapi`, `uvicorn`, and `pydantic`. | Verified |
| A-2  | Service strictly binds to `127.0.0.1:5000` to prevent exposure on external interfaces. | Verified |
| A-3  | `POST /diagnose` accepts `zScores` and raw `signals` maps from Java `DiagnosisClient` and completes within 1000ms. | Verified |
| A-4  | Disambiguation (N1) verifies callee node metrics before classifying a link anomaly as a link fault. | Verified |
| A-5  | `scripts/start-all.bat` and `scripts/stop-all.bat` launch and terminate the Python process cleanly on Windows. | Verified |
