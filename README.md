# Self-Healing Microservices

A Java microservices platform with a Python FastAPI diagnosis service and a
healer dashboard/controller.

## Optional MODIFy model assets

MODIFy source code, the trained model checkpoint, and training datasets are not
included in this repository. The inference adapter expects the source under
`MODIFy-main/codes/`, the checkpoint at
`diagnosis-service/models/modify-shm/best_model.pt`, and model metadata and
training chunks under `MODIFy-main/codes/chunks/SHM/`.

Without those assets, signal-based MODIFy diagnosis requests return HTTP 503.
The assets must be obtained or generated separately from a source you are
authorized to use. Training also requires the omitted chunk datasets.
