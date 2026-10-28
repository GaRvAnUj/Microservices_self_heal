"""Train the unmodified MODIFy model on the platform's SHM chunk dataset."""

import argparse
import json
import random
from pathlib import Path
from typing import Any

import numpy as np
import torch
from torch.utils.data import DataLoader, Dataset


ROOT_DIR = Path(__file__).resolve().parents[1]
MODIFY_CODES_DIR = ROOT_DIR / "MODIFy-main" / "codes"
DATA_DIR = MODIFY_CODES_DIR / "chunks" / "SHM"
MODEL_PATH = ROOT_DIR / "diagnosis-service" / "models" / "modify-shm" / "best_model.pt"


class ChunkDataset(Dataset):
    def __init__(self, chunks: dict[str, dict[str, Any]], metadata: dict[str, Any]):
        import dgl

        src, dst = metadata["edges"]
        self.graphs = []
        self.labels = []
        self.node_num = int(metadata["node_num"])
        for chunk in chunks.values():
            graph = dgl.graph((src, dst), num_nodes=self.node_num)
            graph.ndata["logs"] = torch.as_tensor(chunk["logs"], dtype=torch.float32)
            graph.ndata["metrics"] = torch.as_tensor(chunk["metrics"], dtype=torch.float32)
            graph.ndata["traces"] = torch.as_tensor(chunk["traces"], dtype=torch.float32)
            self.graphs.append(graph)
            self.labels.append(int(chunk["culprit"]))

    def __len__(self) -> int:
        return len(self.labels)

    def __getitem__(self, index: int) -> tuple[Any, int]:
        return self.graphs[index], self.labels[index]


def collate_batch(batch: list[tuple[Any, int]]) -> tuple[Any, torch.Tensor]:
    import dgl

    graphs, labels = zip(*batch)
    return dgl.batch(list(graphs)), torch.tensor(labels, dtype=torch.long)


def load_model_class():
    import importlib.util

    model_path = MODIFY_CODES_DIR / "model.py"
    spec = importlib.util.spec_from_file_location("modify_base_model", model_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Unable to load MODIFy model source at {model_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.MainModel


def model_parameters(metadata: dict[str, Any]) -> dict[str, Any]:
    return {
        "node_feat_dim": 64,
        "chunk_lenth": int(metadata["chunk_lenth"]),
        "self_attn": True,
        "fuse_dim": 128,
        "alpha": 0.5,
        "beta": 0.1,
        "locate_hiddens": [64],
        "detect_hiddens": [64],
        "detector_rank": 16,
        "locator_rank": 16,
        "log_dim": 16,
        "trace_kernel_sizes": [2],
        "trace_hiddens": [64],
        "metric_kernel_sizes": [2],
        "metric_hiddens": [64],
        "graph_hiddens": [64],
        "attn_head": 4,
        "activation": 0.2,
        "use_transformer": True,
        "use_CGLU": True,
        "use_TraceDifussion": True,
    }


def evaluate(model: torch.nn.Module, loader: DataLoader, device: torch.device) -> dict[str, float]:
    model.eval()
    true_negative = false_positive = true_positive = false_negative = 0
    localized = faulty = 0

    with torch.inference_mode():
        for graph, labels in loader:
            graph = graph.to(device)
            labels = labels.to(device)
            embeddings = model.encoder(graph)
            detected = model.detector(embeddings).argmax(dim=1) == 1
            predicted_nodes = model.locator(embeddings).argmax(dim=1)
            actual_faults = labels >= 0

            true_positive += int((detected & actual_faults).sum().item())
            false_positive += int((detected & ~actual_faults).sum().item())
            true_negative += int((~detected & ~actual_faults).sum().item())
            false_negative += int((~detected & actual_faults).sum().item())
            localized += int((predicted_nodes[actual_faults] == labels[actual_faults]).sum().item())
            faulty += int(actual_faults.sum().item())

    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)
    return {
        "accuracy": (true_positive + true_negative) / max(1, true_positive + true_negative + false_positive + false_negative),
        "precision": precision,
        "recall": recall,
        "f1": 2 * precision * recall / max(1e-12, precision + recall),
        "hr_at_1": localized / max(1, faulty),
    }


def load_dataset() -> tuple[dict[str, Any], dict[str, dict[str, Any]], dict[str, dict[str, Any]]]:
    import pickle

    required = [DATA_DIR / name for name in ("metadata.json", "chunk_train.pkl", "chunk_test.pkl")]
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError("Missing MODIFy dataset files: " + ", ".join(missing))

    with (DATA_DIR / "metadata.json").open(encoding="utf-8") as stream:
        metadata = json.load(stream)
    with (DATA_DIR / "chunk_train.pkl").open("rb") as stream:
        train_chunks = pickle.load(stream)
    with (DATA_DIR / "chunk_test.pkl").open("rb") as stream:
        test_chunks = pickle.load(stream)

    if not train_chunks or not test_chunks:
        raise ValueError("Both training and test datasets must contain chunks.")
    if metadata.get("services") != [
        "gateway", "compose-post", "text", "media", "user", "post-storage"
    ]:
        raise ValueError("Dataset service order does not match the application topology.")
    return metadata, train_chunks, test_chunks


def train(args: argparse.Namespace) -> None:
    random.seed(args.seed)
    np.random.seed(args.seed)
    torch.manual_seed(args.seed)
    torch.set_num_threads(args.cpu_threads)
    device = torch.device("cpu")

    metadata, train_chunks, test_chunks = load_dataset()
    print(f"Dataset: {len(train_chunks)} training / {len(test_chunks)} test chunks")

    train_data = ChunkDataset(train_chunks, metadata)
    test_data = ChunkDataset(test_chunks, metadata)
    train_loader = DataLoader(
        train_data, batch_size=args.batch_size, shuffle=True, collate_fn=collate_batch
    )
    test_loader = DataLoader(
        test_data, batch_size=args.batch_size, shuffle=False, collate_fn=collate_batch
    )

    model_cls = load_model_class()
    params = model_parameters(metadata)
    model = model_cls(
        int(metadata["event_num"]),
        int(metadata["metric_num"]),
        int(metadata["node_num"]),
        device,
        **params,
    ).to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=args.lr)
    best_score = -1.0
    best_metrics: dict[str, float] = {}
    best_epoch = 0
    stale_epochs = 0

    MODEL_PATH.parent.mkdir(parents=True, exist_ok=True)
    for epoch in range(1, args.epochs + 1):
        model.train()
        loss_sum = 0.0
        batch_count = 0
        for graph, labels in train_loader:
            graph = graph.to(device)
            labels = labels.to(device)
            optimizer.zero_grad(set_to_none=True)
            loss = model(graph, labels)["loss"]
            if not torch.isfinite(loss):
                raise RuntimeError(f"Non-finite training loss at epoch {epoch}: {loss.item()}")
            loss.backward()
            optimizer.step()
            loss_sum += float(loss.item())
            batch_count += 1

        metrics = evaluate(model, test_loader, device)
        score = (metrics["f1"] + metrics["hr_at_1"]) / 2
        mean_loss = loss_sum / max(1, batch_count)
        print(
            f"Epoch {epoch}/{args.epochs}: loss={mean_loss:.4f}, "
            f"accuracy={metrics['accuracy']:.4f}, f1={metrics['f1']:.4f}, "
            f"HR@1={metrics['hr_at_1']:.4f}"
        )

        if score > best_score:
            best_score = score
            best_metrics = metrics
            best_epoch = epoch
            stale_epochs = 0
            torch.save(
                {
                    "model_state_dict": model.state_dict(),
                    "model_parameters": params,
                    "metrics": metrics,
                    "epoch": epoch,
                    "metadata": metadata,
                },
                MODEL_PATH,
            )
        else:
            stale_epochs += 1
            if args.patience and stale_epochs >= args.patience:
                print(f"Early stopping after {stale_epochs} epochs without improvement.")
                break

    print(f"Best checkpoint: {MODEL_PATH}")
    print(f"Best epoch: {best_epoch}; validation metrics: {best_metrics}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Train the MODIFy model on SHM data.")
    parser.add_argument("--epoches", "--epochs", dest="epochs", type=int, default=30)
    parser.add_argument("--lr", type=float, default=0.001)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--patience", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--cpu-threads", type=int, default=4)
    args = parser.parse_args()
    if args.epochs < 1 or args.batch_size < 2 or args.cpu_threads < 1:
        parser.error("epochs and cpu-threads must be positive; batch-size must be at least 2.")
    return args


if __name__ == "__main__":
    train(parse_args())
