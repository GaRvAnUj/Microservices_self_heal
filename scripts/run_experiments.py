#!/usr/bin/env python3
"""Run and resume live resilience experiments against the local Healer API."""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.metadata
import json
import math
import os
import pickle
import platform
import random
import shutil
import subprocess
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

try:
    from .result_archive import archive_existing_results, archive_paths
except ImportError:
    from result_archive import archive_existing_results, archive_paths


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"
DIAGNOSIS_DIR = ROOT / "diagnosis-service"
MODIFY_DATA = ROOT / "MODIFy-main" / "codes" / "chunks" / "SHM"
CHECKPOINT = ROOT / "diagnosis-service" / "models" / "modify-shm" / "best_model.pt"

MODES = ("NO_HEAL", "BASELINE", "PROPOSED_NO_LINK", "PROPOSED_NO_ATTRIB", "PROPOSED")
CRITICAL_SCENARIOS = (
    "cpu_hog@compose-post",
    "cpu_hog@post-storage",
    "crash@compose-post",
    "crash@post-storage",
    "link_delay@gateway->compose-post",
    "link_delay@compose-post->post-storage",
    "link_loss@gateway->compose-post",
    "link_loss@compose-post->post-storage",
    "wrong_initial_action",
    "planted_wrong_target@compose-post",
)
MASKED_SCENARIOS = (
    "cpu_hog@media",
    "cpu_hog@text",
    "cpu_hog@user",
    "link_delay@compose-post->media",
)
SCENARIOS = CRITICAL_SCENARIOS + MASKED_SCENARIOS
SERVICES = ("gateway", "compose-post", "text", "media", "user", "post-storage")
LINKS = (
    ("gateway", "compose-post"),
    ("compose-post", "text"),
    ("compose-post", "media"),
    ("compose-post", "user"),
    ("compose-post", "post-storage"),
)
CRITICAL_TARGETS = frozenset({
    "compose-post", "post-storage", "gateway->compose-post", "compose-post->post-storage",
})
LINK_DELAY_MS = 800
LINK_LOSS_PCT = 25.0
CPU_HOG_THREADS = 1
DEFAULT_SEED = 20261025
DEFAULT_HEALTHY_RESET_SECONDS = 30
DEFAULT_INCIDENT_TIMEOUT_SECONDS = 120

INCIDENT_HEADER = (
    "run_id", "timestamp", "mode", "scenario", "repetition", "seed", "fault_type",
    "fault_target", "fault_target_type(node|link)", "fault_start_ts", "detected_ts",
    "detection_delay_s", "diagnosis_source(MODIFY|FALLBACK)", "top_candidate",
    "top_candidate_type", "localization_correct(true|false)", "fault_type_inferred",
    "fault_type_correct", "attempts", "first_attempt_success", "recovered", "recovery_ts",
    "mttr_s", "slo_violated", "slo_violation_start_ts", "mttr_from_injection_s",
    "mttr_from_slo_violation_s", "scenario_category(critical|masked_by_degradation)",
    "fault_intensity", "actions(semicolon list)", "total_restarts", "healthy_service_restarts",
    "healthy_service_downtime_s", "e2e_p95_ms_before", "e2e_p95_ms_during",
    "e2e_p95_ms_after", "error_rate_before", "error_rate_during", "error_rate_after",
    "slo_violation_s", "notes",
)
ATTEMPT_HEADER = (
    "run_id", "mode", "scenario", "attempt_no", "action", "target", "target_type", "fault_type_assumed",
    "outcome(recovered|failed)", "attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)",
    "ground_truth_cause(wrong_target|ineffective_action|NA)", "suspect_after", "q_before", "q_after",
)
LEARNING_HEADER = ("mode", "seed", "incident_index", "first_attempt_success", "attempts")
DATASET_HEADER = (
    "windows_collected", "window_length_s", "faults_injected_by_type", "labels_by_service",
    "train_test_split", "collection_duration_min",
)
DIAGNOSIS_EVAL_HEADER = ("diagnosis_source", "split(train|test)", "fault_type", "top1_accuracy", "n")
DIAGNOSIS_LATENCY_HEADER = ("diagnosis_source", "n_calls", "mean_ms", "p95_ms", "timeouts", "fallback_count")
SUMMARY_HEADER = (
    "scenario", "mode", "n", "recovery_rate", "first_attempt_rate", "mttr_mean_s",
    "mttr_median_s", "mttr_std_s", "mean_healthy_restarts", "mean_healthy_downtime_s",
    "mean_attempts", "mean_slo_violation_s",
)
ATTRIBUTION_HEADER = (
    "ground_truth_cause", "attribution_verdict", "count", "accuracy", "inconclusive_rate",
    "wrong_rate", "n",
)
LOCALIZATION_HEADER = ("mode/diagnosis_source", "fault_target_type", "top1_accuracy", "n")
STAT_TEST_HEADER = ("test", "metric", "n", "statistic", "p_value")


def timestamp_iso(epoch_ms: int | float | None = None) -> str:
    moment = datetime.now(timezone.utc) if epoch_ms is None else datetime.fromtimestamp(
        float(epoch_ms) / 1000.0, timezone.utc
    )
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def field_value(row: dict[str, Any], key: str) -> str:
    value = row.get(key)
    return "NA" if value is None or value == "" else str(value)


def write_csv_row(path: Path, header: tuple[str, ...], row: dict[str, Any]) -> None:
    new_file = not path.exists() or path.stat().st_size == 0
    if not new_file:
        with path.open(newline="", encoding="utf-8-sig") as stream:
            existing_header = tuple(next(csv.reader(stream), ()))
        if existing_header != header:
            raise RuntimeError(
                f"Existing CSV header does not match {path}; archive or migrate it before appending."
            )
    with path.open("a", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=header, extrasaction="ignore")
        if new_file:
            writer.writeheader()
        writer.writerow({column: field_value(row, column) for column in header})

def write_single_csv(path: Path, header: tuple[str, ...], row: dict[str, Any]) -> None:
    archive_paths([path], RESULTS, ROOT)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=header, extrasaction="ignore")
        writer.writeheader()
        writer.writerow({column: field_value(row, column) for column in header})


def read_csv(path: Path) -> list[dict[str, str]]:
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8-sig") as stream:
        return list(csv.DictReader(stream))


def api_request(base_url: str, path: str, method: str = "GET",
                body: dict[str, Any] | None = None, timeout: float = 10.0) -> dict[str, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"} if data is not None else {},
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8")
    except HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} returned HTTP {exc.code}: {response_body}") from exc
    except URLError as exc:
        raise RuntimeError(f"{method} {path} failed: {exc.reason}") from exc
    if not payload:
        return {}
    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"{method} {path} returned invalid JSON: {payload[:500]}") from exc
    if not isinstance(parsed, dict):
        raise RuntimeError(f"{method} {path} returned unexpected JSON: {type(parsed).__name__}")
    return parsed


def service_ready(status: dict[str, Any]) -> bool:
    services = status.get("services", {})
    return bool(services) and all(service.get("state") == "UP" for service in services.values())


def wait_for_clean_system(base_url: str, healthy_seconds: int) -> dict[str, Any]:
    started = time.monotonic()
    healthy_since: float | None = None
    last_status: dict[str, Any] = {}
    last_reported = -1
    while True:
        last_status = api_request(base_url, "/api/status", timeout=10)
        ready = (
            last_status.get("baselineEstablished") is True
            and service_ready(last_status)
            and last_status.get("slo", {}).get("sloHealthy") is True
            and last_status.get("activeIncident") is None
            and not last_status.get("chaosFaults")
        )
        now = time.monotonic()
        if ready:
            if healthy_since is None:
                healthy_since = now
            elapsed = int(now - healthy_since)
            if elapsed >= healthy_seconds:
                return last_status
            if elapsed != last_reported and elapsed % 10 == 0:
                print(f"    Reset health: {elapsed}/{healthy_seconds}s stable", flush=True)
                last_reported = elapsed
        else:
            healthy_since = None
            if int(now - started) != last_reported and int(now - started) % 10 == 0:
                slo = last_status.get("slo", {})
                print(
                    "    Waiting for baseline, UP services, and healthy SLO "
                    f"(baseline={last_status.get('baselineEstablished')}, "
                    f"sloHealthy={slo.get('sloHealthy')})",
                    flush=True,
                )
                last_reported = int(now - started)
        time.sleep(1.0)


def prepare_run(base_url: str, mode: str, wrong_initial_q: bool, planted: bool,
                reset_q: bool, healthy_seconds: int, expected_fault_target: str,
                expected_fault_type: str, diagnosis_source_mode: str = "auto") -> dict[str, Any]:
    response = api_request(
        base_url,
        "/api/experiment/reset",
        method="POST",
        body={
            "mode": mode,
            "wrongInitialLinkLossQ": wrong_initial_q,
            "plantedWrongTarget": planted,
            "resetQPolicy": reset_q,
            "expectedFaultTarget": expected_fault_target,
            "expectedFaultType": expected_fault_type,
            "diagnosisSourceMode": diagnosis_source_mode,
        },
        timeout=180,
    )
    failed = [name for name, ok in response.get("restarts", {}).items() if not ok]
    if failed:
        raise RuntimeError(f"Full service reset failed for: {', '.join(failed)}")
    return wait_for_clean_system(base_url, healthy_seconds)


def service_counters(status: dict[str, Any]) -> dict[str, tuple[int, int]]:
    result = {}
    for name, item in status.get("services", {}).items():
        result[name] = (
            int(item.get("restartCount", 0)),
            int(item.get("totalDowntimeMs", 0)),
        )
    return result


def target_for(scenario: str, repetition: int, seed: int) -> tuple[str, str, str]:
    if scenario == "wrong_initial_action":
        return "gateway->compose-post", "link", "link_loss"
    if "@" in scenario:
        fault_type, target = scenario.split("@", 1)
        target_type = "link" if "->" in target else "node"
        if fault_type == "planted_wrong_target":
            return target, target_type, "cpu_hog"
        if fault_type in ("cpu_hog", "crash", "link_delay", "link_loss"):
            return target, target_type, fault_type
        raise ValueError(f"Unknown scenario: {scenario}")

    target_offset = random.Random(f"{seed}:{scenario}:targets").randrange(1000)
    if scenario in ("cpu_hog", "crash", "planted_wrong_target"):
        target = SERVICES[(target_offset + repetition - 1) % len(SERVICES)]
        return target, "node", "cpu_hog" if scenario != "crash" else "crash"
    link_index = (target_offset + repetition - 1) % len(LINKS)
    caller, callee = LINKS[link_index]
    fault_type = "link_delay" if scenario == "link_delay" else "link_loss"
    return f"{caller}->{callee}", "link", fault_type


def set_of_planted_runs(seed: int, reps: int) -> set[str]:
    schedule_reps = reps
    candidates = [
        f"PROPOSED|planted_wrong_target|{repetition}"
        for repetition in range(1, schedule_reps + 1)
    ]
    count = math.floor(len(candidates) * 0.30 + 0.5)
    return set(random.Random(seed ^ 0x5EED).sample(candidates, count))


def scenario_category(target: str) -> str:
    return "critical" if target in CRITICAL_TARGETS else "masked_by_degradation"


def inject_fault(base_url: str, fault_type: str, target: str,
                 cpu_hog_threads: int = CPU_HOG_THREADS,
                 link_delay_ms: int = LINK_DELAY_MS,
                 link_loss_pct: float = LINK_LOSS_PCT) -> dict[str, Any]:
    params: dict[str, Any] = {"type": fault_type, "target": target}
    if fault_type == "cpu_hog":
        params["threads"] = cpu_hog_threads
    elif fault_type in ("link_delay", "link_loss"):
        caller, callee = target.split("->", 1)
        params["caller"] = caller
        params["target"] = callee
        params["delayMs"] = link_delay_ms if fault_type == "link_delay" else 0
        params["lossPct"] = link_loss_pct if fault_type == "link_loss" else 0
    query = urlencode(params)
    return api_request(base_url, f"/api/chaos/inject?{query}", method="POST", timeout=10)


def incident_from_status(status: dict[str, Any], fault_start_ms: int,
                         seen_incident_id: str | None) -> dict[str, Any] | None:
    active = status.get("activeIncident")
    if active and int(active.get("startedAt", 0)) >= fault_start_ms:
        return active
    for item in reversed(status.get("incidentHistory", [])):
        if int(item.get("startedAt", 0)) >= fault_start_ms:
            if seen_incident_id is None or item.get("id") == seen_incident_id:
                return item
    return None


def wait_for_incident(base_url: str, fault_start_ms: int, timeout_seconds: int,
                      poll_seconds: float) -> tuple[dict[str, Any] | None, dict[str, Any], list[tuple[float, dict[str, Any]]], int]:
    started = time.monotonic()
    samples: list[tuple[float, dict[str, Any]]] = []
    current_incident: dict[str, Any] | None = None
    incident_id: str | None = None
    transient_status_errors = 0
    last_elapsed_print = -5

    while time.monotonic() - started < timeout_seconds:
        elapsed = time.monotonic() - started
        try:
            status = api_request(base_url, "/api/status", timeout=10)
        except RuntimeError as exc:
            transient_status_errors += 1
            print(f"    Status poll error at {elapsed:.1f}s: {exc}", flush=True)
            time.sleep(poll_seconds)
            continue
        samples.append((elapsed, status))
        candidate = incident_from_status(status, fault_start_ms, incident_id)
        if candidate is not None:
            current_incident = candidate
            incident_id = candidate.get("id")
            if candidate.get("status") in ("RESOLVED", "ESCALATED"):
                return candidate, status, samples, transient_status_errors
            for history_item in status.get("incidentHistory", []):
                if history_item.get("id") == incident_id and history_item.get("status") in ("RESOLVED", "ESCALATED"):
                    return history_item, status, samples, transient_status_errors
        elapsed_int = int(elapsed)
        if elapsed_int >= last_elapsed_print + 10:
            print(
                f"    Incident wait: {elapsed_int}/{timeout_seconds}s "
                f"(detected={'yes' if current_incident else 'no'})",
                flush=True,
            )
            last_elapsed_print = elapsed_int
        time.sleep(poll_seconds)

    final_status = api_request(base_url, "/api/status", timeout=10)
    samples.append((time.monotonic() - started, final_status))
    candidate = incident_from_status(final_status, fault_start_ms, incident_id)
    if candidate is not None:
        current_incident = candidate
    return current_incident, final_status, samples, transient_status_errors


def summary_metrics(samples: list[tuple[float, dict[str, Any]]], before: dict[str, Any],
                    incident: dict[str, Any] | None, fault_start_ms: int,
                    before_counters: dict[str, tuple[int, int]],
                    after: dict[str, Any]) -> dict[str, Any]:
    slo_values = []
    for elapsed, snapshot in samples:
        slo_values.append((elapsed, snapshot.get("slo", {})))
    violation_s = 0.0
    violation_start_ms: int | None = None
    for index in range(1, len(slo_values)):
        prior_elapsed, prior_slo = slo_values[index - 1]
        elapsed, _ = slo_values[index]
        if prior_slo.get("sloHealthy") is False:
            violation_s += max(0.0, elapsed - prior_elapsed)
            if violation_start_ms is None:
                violation_start_ms = int(fault_start_ms + prior_elapsed * 1000)

    during_slos = [snapshot.get("slo", {}) for _, snapshot in samples]
    if violation_start_ms is None:
        first_violation = next(
            (
                elapsed for elapsed, slo in slo_values
                if slo.get("sloHealthy") is False
            ),
            None,
        )
        if first_violation is not None:
            violation_start_ms = int(fault_start_ms + first_violation * 1000)
    p95_values = [float(item["p95LatencyMs"]) for item in during_slos if item.get("p95LatencyMs") is not None]
    error_values = [float(item["errorRate"]) for item in during_slos if item.get("errorRate") is not None]
    after_counters = service_counters(after)
    total_restarts = 0
    healthy_restarts = 0
    healthy_downtime_ms = 0
    ground_truth = incident.get("targetEntity") if incident else None
    for name, (restart_count, downtime) in after_counters.items():
        old_restart_count, old_downtime = before_counters.get(name, (0, 0))
        restart_delta = max(0, restart_count - old_restart_count)
        downtime_delta = max(0, downtime - old_downtime)
        total_restarts += restart_delta
        if name != ground_truth:
            healthy_restarts += restart_delta
            healthy_downtime_ms += downtime_delta

    before_slo = before.get("slo", {})
    after_slo = after.get("slo", {})
    resolved_at = (incident or {}).get("resolvedAt")
    recovered = (incident or {}).get("status") == "RESOLVED" and resolved_at is not None
    return {
        "e2e_p95_ms_before": before_slo.get("p95LatencyMs", "NA"),
        "e2e_p95_ms_during": max(p95_values) if p95_values else "NA",
        "e2e_p95_ms_after": after_slo.get("p95LatencyMs", "NA"),
        "error_rate_before": before_slo.get("errorRate", "NA"),
        "error_rate_during": max(error_values) if error_values else "NA",
        "error_rate_after": after_slo.get("errorRate", "NA"),
        "slo_violation_s": violation_s,
        "total_restarts": total_restarts,
        "healthy_service_restarts": healthy_restarts,
        "healthy_service_downtime_s": healthy_downtime_ms / 1000.0,
        "slo_violated": violation_start_ms is not None,
        "slo_violation_start_ts": timestamp_iso(violation_start_ms) if violation_start_ms else "NA",
        "mttr_from_injection_s": (
            (int(resolved_at) - fault_start_ms) / 1000.0 if recovered else "NA"
        ),
        "mttr_from_slo_violation_s": (
            (int(resolved_at) - violation_start_ms) / 1000.0
            if recovered and violation_start_ms is not None else "NA"
        ),
    }


def normalize_source(source: Any, mode: str) -> str:
    if mode == "BASELINE" or source in (None, "", "NA"):
        return "NA"
    return "MODIFY" if str(source).upper() == "MODIFY" else "FALLBACK"


def _is_valid_target(target: Any) -> bool:
    return (
        isinstance(target, str)
        and bool(target.strip())
        and target.strip().lower() not in ("null", "na")
    )


def validate_incident_targets(incident: dict[str, Any] | None) -> None:
    if incident is None:
        return

    actions = incident.get("actions") or []
    for index, action in enumerate(actions, start=1):
        target = action.get("targetComponent")
        if not _is_valid_target(target):
            raise RuntimeError(
                f"incident {incident.get('id', 'unknown')} action {index} has no valid target: {target!r}"
            )

    candidate = incident.get("targetEntity")
    diagnosis_source = incident.get("diagnosisSource")
    diagnosed = bool(actions) or diagnosis_source not in (None, "", "NA")
    if diagnosed and not _is_valid_target(candidate):
        raise RuntimeError(
            f"incident {incident.get('id', 'unknown')} diagnosed incident has no valid candidate: {candidate!r}"
        )


def ground_truth_cause(mode: str, scenario: str, planted: bool,
                       action: dict[str, Any], attempt_no: int,
                       fault_target: str, fault_type: str) -> str:
    if mode != "PROPOSED" or action.get("verdict") == "SUCCESS":
        return "NA"

    if planted and scenario == "planted_wrong_target" and attempt_no == 1:
        return "wrong_target"
    if action.get("targetComponent") != fault_target:
        return "wrong_target"

    expected_action = {
        "cpu_hog": "restart",
        "crash": "restart",
        "link_delay": "tune_link",
        "link_loss": "breaker",
    }.get(fault_type)
    if expected_action is not None and action.get("actionType") != expected_action:
        return "ineffective_action"
    return "NA"


def make_attempt_rows(run_id: str, mode: str, scenario: str,
                      actions: list[dict[str, Any]], fault_target: str,
                      fault_type: str, planted: bool) -> list[dict[str, Any]]:
    rows = []
    for index, action in enumerate(actions, start=1):
        verdict = action.get("verdict")
        allowed_verdicts = ("WRONG_TARGET", "INEFFECTIVE_ACTION", "INCONCLUSIVE")
        attribution = verdict if mode == "PROPOSED" and verdict in allowed_verdicts else "NA"
        target = action.get("targetComponent")
        success = verdict == "SUCCESS"
        cause = ground_truth_cause(mode, scenario, planted, action, index, fault_target, fault_type)
        rows.append({
            "run_id": run_id,
            "mode": mode,
            "scenario": scenario,
            "attempt_no": index,
            "action": action.get("actionType", "NA"),
            "target": target or "NA",
            "target_type": "link" if target and "->" in target else ("node" if target else "NA"),
            "fault_type_assumed": action.get("faultTypeAssumed", fault_type) or "NA",
            "outcome(recovered|failed)": "recovered" if success else "failed",
            "attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)": attribution,
            "ground_truth_cause(wrong_target|ineffective_action|NA)": cause,
            "suspect_after": action.get("suspectAfter") or "NA",
            "q_before": action.get("qBefore", "NA") if action.get("qBefore") is not None else "NA",
            "q_after": action.get("qAfter", "NA") if action.get("qAfter") is not None else "NA",
        })
    return rows


def make_incident_row(run_id: str, mode: str, scenario: str, repetition: int, seed: int,
                      fault_type: str, fault_target: str, target_type: str, fault_start_ms: int,
                      incident: dict[str, Any] | None, final_status: dict[str, Any],
                      samples: list[tuple[float, dict[str, Any]]],
                      before: dict[str, Any], before_counters: dict[str, tuple[int, int]],
                      after: dict[str, Any], transient_errors: int, planted: bool,
                      timeout: bool, fault_intensity: dict[str, Any]) -> dict[str, Any]:
    actions = (incident or {}).get("actions", [])
    candidate = (incident or {}).get("targetEntity")
    inferred = (incident or {}).get("rootCauseType")
    started_at = (incident or {}).get("startedAt")
    resolved_at = (incident or {}).get("resolvedAt")
    detected_ts = timestamp_iso(started_at) if started_at else "NA"
    recovered = (incident or {}).get("status") == "RESOLVED"
    first_success = None if not actions else actions[0].get("verdict") == "SUCCESS"
    source = normalize_source((incident or {}).get("diagnosisSource"), mode)
    correct = None if candidate is None else candidate == fault_target
    fault_type_correct = None if mode == "BASELINE" or inferred is None else inferred == fault_type
    finish_metrics = summary_metrics(samples, before, incident, fault_start_ms, before_counters, after)
    notes = []
    if planted:
        notes.append("planted_wrong_target=true")
    if timeout:
        notes.append(f"hard_timeout_{DEFAULT_INCIDENT_TIMEOUT_SECONDS}s")
    if incident is None:
        notes.append("no_incident_detected")
    if transient_errors:
        notes.append(f"status_poll_errors={transient_errors}")
    if (incident or {}).get("status") == "ESCALATED":
        notes.append("healer_escalated")
    actual_fault_start = timestamp_iso(fault_start_ms)
    detection_delay = (
        (int(started_at) - fault_start_ms) / 1000.0 if started_at is not None else "NA"
    )
    recovery_ts = timestamp_iso(resolved_at) if resolved_at else "NA"
    mttr = (int(resolved_at) - fault_start_ms) / 1000.0 if recovered and resolved_at else "NA"
    return {
        "run_id": run_id,
        "timestamp": timestamp_iso(),
        "mode": mode,
        "scenario": scenario,
        "repetition": repetition,
        "seed": seed,
        "fault_type": fault_type,
        "fault_target": fault_target,
        "fault_target_type(node|link)": target_type,
        "fault_start_ts": actual_fault_start,
        "detected_ts": detected_ts,
        "detection_delay_s": detection_delay,
        "diagnosis_source(MODIFY|FALLBACK)": source,
        "top_candidate": candidate or "NA",
        "top_candidate_type": "link" if candidate and "->" in candidate else ("node" if candidate else "NA"),
        "localization_correct(true|false)": correct if correct is not None else "NA",
        "fault_type_inferred": inferred or "NA",
        "fault_type_correct": fault_type_correct if fault_type_correct is not None else "NA",
        "attempts": len(actions),
        "first_attempt_success": first_success if first_success is not None else "NA",
        "recovered": recovered,
        "recovery_ts": recovery_ts,
        "mttr_s": mttr,
        "slo_violated": finish_metrics["slo_violated"],
        "slo_violation_start_ts": finish_metrics["slo_violation_start_ts"],
        "mttr_from_injection_s": finish_metrics["mttr_from_injection_s"],
        "mttr_from_slo_violation_s": finish_metrics["mttr_from_slo_violation_s"],
        "scenario_category(critical|masked_by_degradation)": scenario_category(fault_target),
        "fault_intensity": json.dumps(fault_intensity, sort_keys=True),
        "actions(semicolon list)": ";".join(
            f"{item.get('actionType', 'NA')}@{item.get('targetComponent', 'NA')}" for item in actions
        ) or "NA",
        **finish_metrics,
        "notes": ";".join(notes),
    }


def run_one(base_url: str, mode: str, scenario: str, repetition: int, seed: int,
            run_id: str, target: str, target_type: str, fault_type: str, planted: bool,
            reset_q: bool, healthy_seconds: int, timeout_seconds: int,
            poll_seconds: float, cpu_hog_threads: int = CPU_HOG_THREADS,
            link_delay_ms: int = LINK_DELAY_MS,
            link_loss_pct: float = LINK_LOSS_PCT,
            diagnosis_source_mode: str = "auto") -> tuple[dict[str, Any], list[dict[str, Any]]]:
    wrong_initial_q = scenario in ("link_loss_wrong_initial_Q", "wrong_initial_action")
    print(
        f"[{run_id}] reset; mode={mode}, scenario={scenario}, "
        f"target={target} ({target_type}), planted={planted}",
        flush=True,
    )
    before = prepare_run(
        base_url, mode, wrong_initial_q, planted, reset_q, healthy_seconds,
        target, fault_type, diagnosis_source_mode,
    )
    before_counters = service_counters(before)
    api_request(base_url, "/api/experiment/start", method="POST", timeout=10)
    fault_start_ms = int(time.time() * 1000)
    try:
        response = inject_fault(
            base_url, fault_type, target, cpu_hog_threads, link_delay_ms, link_loss_pct
        )
        if response.get("status") != "INJECTED":
            raise RuntimeError(f"Fault injection not accepted: {response}")
    except RuntimeError as exc:
        now_status = api_request(base_url, "/api/status", timeout=10)
        row = {
            "run_id": run_id,
            "timestamp": timestamp_iso(),
            "mode": mode,
            "scenario": scenario,
            "repetition": repetition,
            "seed": seed,
            "fault_type": fault_type,
            "fault_target": target,
            "fault_target_type(node|link)": target_type,
            "fault_start_ts": timestamp_iso(fault_start_ms),
            "recovered": "NA",
            "notes": f"injection_failed: {exc}",
            "e2e_p95_ms_before": before.get("slo", {}).get("p95LatencyMs", "NA"),
            "error_rate_before": before.get("slo", {}).get("errorRate", "NA"),
        }
        return row, []

    if fault_type == "cpu_hog":
        fault_intensity = {"cpu_hog_threads": response.get("threads", cpu_hog_threads)}
    elif fault_type == "link_delay":
        fault_intensity = {"delay_ms": link_delay_ms}
    elif fault_type == "link_loss":
        fault_intensity = {"loss_pct": link_loss_pct}
    else:
        fault_intensity = {"crash": "process_halt"}

    incident, final_status, samples, transient_errors = wait_for_incident(
        base_url, fault_start_ms, timeout_seconds, poll_seconds
    )
    validate_incident_targets(incident)
    timeout = incident is None or incident.get("status") not in ("RESOLVED", "ESCALATED")
    if timeout and incident is not None:
        print(f"    TIMEOUT; active incident status={incident.get('status')}", flush=True)
    elif incident is None:
        print("    TIMEOUT; no incident was detected", flush=True)
    else:
        print(
            f"    Incident finished: status={incident.get('status')}, "
            f"attempts={len(incident.get('actions', []))}",
            flush=True,
        )

    row = make_incident_row(
        run_id, mode, scenario, repetition, seed, fault_type, target, target_type,
        fault_start_ms, incident, final_status, samples, before, before_counters,
        final_status, transient_errors, planted, timeout, fault_intensity,
    )
    attempts = make_attempt_rows(
        run_id, mode, scenario, (incident or {}).get("actions", []),
        target, fault_type, planted,
    )
    return row, attempts


def append_trial(incident_row: dict[str, Any], attempts: list[dict[str, Any]]) -> None:
    attempt_path = RESULTS / "attempts.csv"
    existing = {(row["run_id"], row["attempt_no"]) for row in read_csv(attempt_path)}
    for attempt in attempts:
        key = (str(attempt["run_id"]), str(attempt["attempt_no"]))
        if key not in existing:
            write_csv_row(attempt_path, ATTEMPT_HEADER, attempt)
    write_csv_row(RESULTS / "incidents.csv", INCIDENT_HEADER, incident_row)


def command_output(command: list[str], timeout: int = 15) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"NA ({exc})"
    output = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
    return output if result.returncode == 0 or output else f"NA (exit={result.returncode})"


def maven_version() -> str:
    executable = shutil.which("mvn") or shutil.which("mvn.cmd")
    if executable:
        command = ["cmd.exe", "/c", executable, "-v"] if executable.lower().endswith(".cmd") else [executable, "-v"]
        return command_output(command)
    candidate = Path.home() / ".maven" / "maven-3.10.0-rc-1" / "bin" / "mvn.cmd"
    if candidate.is_file():
        return command_output(["cmd.exe", "/c", str(candidate), "-v"])
    return "NA (Maven executable not found)"


def system_ram_bytes() -> int | str:
    if os.name != "nt":
        return "NA (RAM query implemented for Windows runner)"
    output = command_output([
        "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
        "(Get-CimInstance -ClassName Win32_ComputerSystem).TotalPhysicalMemory",
    ])
    try:
        return int(output.splitlines()[-1].strip())
    except (ValueError, IndexError):
        return f"NA ({output})"


def package_versions() -> dict[str, str]:
    names = ("fastapi", "uvicorn", "pydantic", "torch", "dgl", "numpy", "scipy", "requests")
    versions = {}
    for name in names:
        try:
            versions[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            versions[name] = "NOT_INSTALLED"
    return versions


def git_facts() -> tuple[str, bool]:
    commit = command_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"])
    dirty = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--quiet"],
        capture_output=True,
        check=False,
    ).returncode != 0
    return commit, dirty


def dataset_stats() -> dict[str, Any]:
    train_file = MODIFY_DATA / "chunk_train.pkl"
    test_file = MODIFY_DATA / "chunk_test.pkl"
    metadata_file = MODIFY_DATA / "metadata.json"
    if not (train_file.is_file() and test_file.is_file() and metadata_file.is_file()):
        return {
            "windows_collected": "NA",
            "window_length_s": "NA",
            "faults_injected_by_type": "NA",
            "labels_by_service": "NA",
            "train_test_split": "NA",
            "collection_duration_min": "NA",
        }

    with train_file.open("rb") as stream:
        train_chunks = pickle.load(stream)
    with test_file.open("rb") as stream:
        test_chunks = pickle.load(stream)
    metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    chunks = list(train_chunks.values()) + list(test_chunks.values())
    fault_runs: dict[str, set[str]] = defaultdict(set)
    label_counts: Counter[str] = Counter()
    split_counts: Counter[str] = Counter()
    for chunk in chunks:
        split_counts[str(chunk.get("split", "unknown"))] += 1
        label = int(chunk.get("culprit", -1))
        if label >= 0:
            service = SERVICES[label] if label < len(SERVICES) else f"unknown:{label}"
            label_counts[service] += 1
            fault_type = str(chunk.get("fault_type", "unknown"))
            fault_runs[fault_type].add(str(chunk.get("experiment_id", "unknown")))
    return {
        "windows_collected": len(chunks),
        "window_length_s": metadata.get("chunk_lenth", "NA"),
        "faults_injected_by_type": json.dumps(
            {name: len(ids) for name, ids in sorted(fault_runs.items())}, sort_keys=True
        ),
        "labels_by_service": json.dumps(dict(sorted(label_counts.items())), sort_keys=True),
        "train_test_split": json.dumps(dict(sorted(split_counts.items())), sort_keys=True),
        "collection_duration_min": "NA",
    }


def append_dataset_stats() -> None:
    path = RESULTS / "dataset_stats.csv"
    row = dataset_stats()
    write_single_csv(path, DATASET_HEADER, row)
    print(
        "Dataset files: "
        f"windows={row['windows_collected']}, window_length_s={row['window_length_s']}, "
        f"collection_duration_min={row['collection_duration_min']}",
        flush=True,
    )


def evaluate_modify_dataset() -> None:
    path = RESULTS / "diagnosis_eval.csv"
    archive_paths([path], RESULTS, ROOT)
    if not (CHECKPOINT.is_file() and (MODIFY_DATA / "chunk_test.pkl").is_file()):
        write_csv_row(path, DIAGNOSIS_EVAL_HEADER, {
            "diagnosis_source": "MODIFY",
            "split(train|test)": "test",
            "fault_type": "NA",
            "top1_accuracy": "NA",
            "n": 0,
        })
        print("Diagnosis evaluation: NA; checkpoint or held-out chunk file is absent.", flush=True)
        return

    sys.path.insert(0, str(DIAGNOSIS_DIR))
    try:
        import numpy as np
        from modify_inferencer import ModifyInferencer

        inferencer = ModifyInferencer()
        results: dict[tuple[str, str], list[int]] = defaultdict(lambda: [0, 0])
        for split, filename in (("train", "chunk_train.pkl"), ("test", "chunk_test.pkl")):
            with (MODIFY_DATA / filename).open("rb") as stream:
                chunks = pickle.load(stream)
            for chunk in chunks.values():
                label = int(chunk.get("culprit", -1))
                if label < 0:
                    continue
                graph = inferencer.dgl.graph(
                    (inferencer.edges[0], inferencer.edges[1]),
                    num_nodes=int(inferencer.metadata["node_num"]),
                )
                graph.ndata["logs"] = inferencer.torch.from_numpy(
                    np.asarray(chunk["logs"], dtype=np.float32)
                )
                graph.ndata["metrics"] = inferencer.torch.from_numpy(
                    np.asarray(chunk["metrics"], dtype=np.float32)
                )
                graph.ndata["traces"] = inferencer.torch.from_numpy(
                    np.asarray(chunk["traces"], dtype=np.float32)
                )
                with inferencer._lock, inferencer.torch.random.fork_rng(devices=[]):
                    inferencer.torch.manual_seed(42)
                    with inferencer.torch.inference_mode():
                        embeddings = inferencer.model.encoder(graph.to(inferencer.device))
                        detect = inferencer.torch.softmax(
                            inferencer.model.detector(embeddings), dim=-1
                        )[0]
                        locate = inferencer.torch.softmax(
                            inferencer.model.locator(embeddings), dim=-1
                        )[0]
                predicted = int(locate.argmax().item())
                detected = float(detect[1].item()) >= 0.5
                key = (split, str(chunk.get("fault_type", "unknown")))
                results[key][1] += 1
                if detected and predicted == label:
                    results[key][0] += 1

        for (split, fault_type), (correct, total) in sorted(results.items()):
            write_csv_row(path, DIAGNOSIS_EVAL_HEADER, {
                "diagnosis_source": "MODIFY",
                "split(train|test)": split,
                "fault_type": fault_type,
                "top1_accuracy": correct / total if total else "NA",
                "n": total,
            })
        if not results:
            write_csv_row(path, DIAGNOSIS_EVAL_HEADER, {
                "diagnosis_source": "MODIFY",
                "split(train|test)": "test",
                "fault_type": "NA",
                "top1_accuracy": "NA",
                "n": 0,
            })
        print(f"Diagnosis evaluation: wrote {sum(len(v) for v in results.values())} labeled chunk predictions.", flush=True)
    except Exception as exc:
        path.unlink(missing_ok=True)
        write_csv_row(path, DIAGNOSIS_EVAL_HEADER, {
            "diagnosis_source": "MODIFY",
            "split(train|test)": "test",
            "fault_type": "NA",
            "top1_accuracy": "NA",
            "n": 0,
        })
        print(f"Diagnosis evaluation error (recorded NA, not fabricated): {type(exc).__name__}: {exc}", flush=True)
    finally:
        if str(DIAGNOSIS_DIR) in sys.path:
            sys.path.remove(str(DIAGNOSIS_DIR))


def setup_json(base_url: str, seed: int, healthy_seconds: int,
               runtime: dict[str, Any] | None = None,
               diagnosis_source: str = "auto",
               cpu_hog_threads: int = CPU_HOG_THREADS,
               link_delay_ms: int = LINK_DELAY_MS,
               link_loss_pct: float = LINK_LOSS_PCT) -> dict[str, Any]:
    runtime = runtime or api_request(base_url, "/api/experiment/runtime", timeout=10)
    commit, dirty = git_facts()
    ram = system_ram_bytes()
    assets = []
    for path in (ROOT / "MODIFy-main" / "codes", MODIFY_DATA):
        if path.is_dir():
            assets.extend(
                {"path": str(item.relative_to(ROOT)), "bytes": item.stat().st_size}
                for item in sorted(path.iterdir())
                if item.is_file()
            )
    checkpoint_info: dict[str, Any] = {"present": CHECKPOINT.is_file()}
    if CHECKPOINT.is_file():
        digest = hashlib.sha256()
        with CHECKPOINT.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(block)
        checkpoint_info.update({
            "file": str(CHECKPOINT.relative_to(ROOT)),
            "bytes": CHECKPOINT.stat().st_size,
            "sha256": digest.hexdigest(),
        })

    python_command = [sys.executable, "--version"]
    return {
        "git_commit_hash": commit,
        "git_worktree_dirty": dirty,
        "date_utc": timestamp_iso(),
        "os": platform.platform(),
        "cpu": {
            "processor": platform.processor() or "NA",
            "logical_processors": os.cpu_count() or "NA",
            "ram_bytes": ram,
        },
        "java_version": command_output(["java", "-version"]),
        "maven_version": maven_version(),
        "python_version": command_output(python_command),
        "diagnosis_service_library_versions": package_versions(),
        "jvm_flags": runtime.get("jvmFlags", "NA"),
        "jvm_max_memory_bytes": runtime.get("jvmMaxMemoryBytes", "NA"),
        "ports": runtime.get("ports", "NA"),
        "window_length_s": float(runtime.get("windowLengthMs", 0)) / 1000.0,
        "telemetry_poll_interval_ms": runtime.get("telemetryPollIntervalMs", "NA"),
        "baseline_warmup_samples": runtime.get("baselineWarmupSamples", "NA"),
        "telemetry_smoothing_windows": 3,
        "slo_definition": {
            "p95_latency_ms_strictly_below": runtime.get("sloP95LimitMs", "NA"),
            "error_rate_strictly_below": runtime.get("sloErrorLimit", "NA"),
        },
        "detector_thresholds": {
            "anomaly_z": runtime.get("anomalyZThreshold", "NA"),
            "link_candidate_z": runtime.get("linkCandidateZThreshold", "NA"),
            "callee_abnormality_z": runtime.get("calleeAbnormalityZThreshold", "NA"),
        },
        "attribution_thresholds": {
            "high_z": runtime.get("anomalyZThreshold", "NA"),
            "low_z": runtime.get("attributionLowZThreshold", "NA"),
            "dominance_ratio": runtime.get("attributionDominanceRatio", "NA"),
        },
        "initial_q_table_by_scenario": {
            "default": {
                "cpu_hog.restart": 0.9,
                "crash.restart": 0.9,
                "link_delay.tune_link": 0.9,
                "link_delay.breaker": 0.5,
                "link_loss.breaker": 0.9,
                "link_loss.tune_link": 0.5,
            },
            "link_loss_wrong_initial_Q": {
                "link_loss.breaker": 0.5,
                "link_loss.tune_link": 0.9,
                "action_alias": "tune_edge maps to the existing tune_link action",
            },
        },
        "load_rate_req_s": runtime.get("targetRps", "NA"),
        "baseline_profile_duration_s": (
            float(runtime["baselineProfileDurationMs"]) / 1000.0
            if isinstance(runtime.get("baselineProfileDurationMs"), (int, float))
            and runtime["baselineProfileDurationMs"] >= 0 else "NA"
        ),
        "baseline_reset_healthy_seconds": healthy_seconds,
        "fault_parameters": {
            "cpu_hog_threads": cpu_hog_threads,
            "link_delay_ms": link_delay_ms,
            "link_loss_pct": link_loss_pct,
            "crash": "Runtime.halt(1) after chaos endpoint response",
        },
        "random_seed": seed,
        "modify_assets": {
            "present": CHECKPOINT.is_file() and (MODIFY_DATA / "metadata.json").is_file(),
            "checkpoint": checkpoint_info,
            "files": assets,
        },
        "experiment_protocol": {
            "modes": list(MODES),
            "scenarios": list(SCENARIOS),
            "incident_timeout_s": DEFAULT_INCIDENT_TIMEOUT_SECONDS,
            "healthy_reset_s": healthy_seconds,
            "poll_interval_s": 0.5,
            "diagnosis_source_mapping": {
                "MODIFy": "MODIFY",
                "PYTHON_FASTAPI/JAVA_STATISTICAL_APPROXIMATION": "FALLBACK",
                "BASELINE": "NA (diagnosis bypassed)",
            },
            "diagnosis_source_mode": diagnosis_source,
        },
    }


def save_setup(base_url: str, args: argparse.Namespace) -> dict[str, Any]:
    runtime = api_request(base_url, "/api/experiment/runtime", timeout=10)
    facts = setup_json(
        base_url,
        args.seed,
        args.healthy_reset_seconds,
        runtime,
        args.diagnosis_source,
        args.cpu_hog_threads,
        args.link_delay_ms,
        args.link_loss_pct,
    )
    setup_path = RESULTS / "setup.json"
    if setup_path.exists():
        existing = json.loads(setup_path.read_text(encoding="utf-8"))
        facts["execution_sessions"] = existing.get("execution_sessions", [])
    archive_paths([setup_path], RESULTS, ROOT)
    setup_path.write_text(
        json.dumps(facts, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    return facts


def record_execution_session(started_at: str, started_clock: float,
                             args: argparse.Namespace, complete: bool) -> None:
    setup_path = RESULTS / "setup.json"
    setup = json.loads(setup_path.read_text(encoding="utf-8"))
    sessions = setup.setdefault("execution_sessions", [])
    if complete:
        if sessions and sessions[-1].get("started_at_utc") == started_at:
            sessions[-1].update({
                "completed_at_utc": timestamp_iso(),
                "elapsed_s": time.monotonic() - started_clock,
                "status": "completed",
            })
    else:
        if sessions and sessions[-1].get("status") == "running":
            sessions[-1]["status"] = "interrupted_before_resume"
        sessions.append({
            "started_at_utc": started_at,
            "completed_at_utc": "NA",
            "elapsed_s": "NA",
            "status": "running",
            **execution_config(args),
        })
    archive_paths([setup_path], RESULTS, ROOT)
    setup_path.write_text(json.dumps(setup, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def execution_config(args: argparse.Namespace) -> dict[str, Any]:
    return {
        "reps": args.reps,
        "seed": args.seed,
        "learning_enabled": args.learning and not args.skip_learning,
        "skip_main": args.skip_main,
        "diagnosis_source": args.diagnosis_source,
        "cpu_hog_threads": args.cpu_hog_threads,
        "link_delay_ms": args.link_delay_ms,
        "link_loss_pct": args.link_loss_pct,
        "healthy_reset_seconds": args.healthy_reset_seconds,
        "incident_timeout_seconds": args.incident_timeout_seconds,
        "poll_interval_seconds": args.poll_interval_seconds,
    }


def validate_resume(args: argparse.Namespace) -> None:
    setup_path = RESULTS / "setup.json"
    if not setup_path.is_file():
        raise RuntimeError("--resume requires an existing setup.json from an interrupted run.")
    setup = json.loads(setup_path.read_text(encoding="utf-8"))
    sessions = setup.get("execution_sessions", [])
    if not sessions or sessions[-1].get("status") != "running":
        raise RuntimeError("--resume requires the latest execution session to be marked running.")
    session = sessions[-1]
    mismatches = [
        key for key, value in execution_config(args).items()
        if session.get(key) != value
    ]
    if mismatches:
        raise RuntimeError(
            "--resume settings differ from the interrupted run: " + ", ".join(mismatches)
        )


def save_diagnosis_latency(base_url: str) -> None:
    metrics = api_request(base_url, "/api/experiment/diagnosis-metrics", timeout=10)
    path = RESULTS / "diagnosis_latency.csv"
    write_single_csv(path, DIAGNOSIS_LATENCY_HEADER, {
        "diagnosis_source": "all",
        "n_calls": metrics.get("n_calls", 0),
        "mean_ms": metrics.get("mean_ms", "NA"),
        "p95_ms": metrics.get("p95_ms", "NA"),
        "timeouts": metrics.get("timeouts", 0),
        "fallback_count": metrics.get("fallback_count", 0),
    })


def main_experiments(args: argparse.Namespace) -> None:
    completed = {row.get("run_id") for row in read_csv(RESULTS / "incidents.csv")}
    planted_ids = set_of_planted_runs(args.seed, args.reps)
    runs = [
        (mode, scenario, repetition)
        for mode in MODES
        for scenario in SCENARIOS
        for repetition in range(1, args.reps + 1)
    ]
    random.Random(args.seed).shuffle(runs)
    total = len(runs)
    for index, (mode, scenario, repetition) in enumerate(runs, start=1):
        run_id = f"{args.seed}|{mode}|{scenario}|rep{repetition}"
        if run_id in completed:
            print(f"[{index}/{total}] SKIP completed {run_id}", flush=True)
            continue
        target, target_type, fault_type = target_for(scenario, repetition, args.seed)
        planted = (
            f"{mode}|planted_wrong_target|{repetition}" in planted_ids
            if mode == "PROPOSED" and scenario.startswith("planted_wrong_target") else False
        )
        if scenario in ("link_loss_wrong_initial_Q", "wrong_initial_action"):
            fault_type = "link_loss"
        print(f"[{index}/{total}] Starting {run_id}", flush=True)
        row, attempt_rows = run_one(
            args.base_url, mode, scenario, repetition, args.seed, run_id,
            target, target_type, fault_type, planted, True, args.healthy_reset_seconds,
            args.incident_timeout_seconds, args.poll_interval_seconds,
            args.cpu_hog_threads, args.link_delay_ms, args.link_loss_pct,
            args.diagnosis_source,
        )
        append_trial(row, attempt_rows)
        completed.add(run_id)
        print(
            f"[{index}/{total}] SAVED {run_id}: recovered={row.get('recovered', 'NA')}, "
            f"attempts={row.get('attempts', 0)}, notes={row.get('notes', '') or 'none'}",
            flush=True,
        )
    print("Main experiment matrix complete; performing final clean reset.", flush=True)
    last_mode = runs[-1][0] if runs else "PROPOSED"
    last_scenario = runs[-1][1] if runs else "cpu_hog"
    api_request(
        args.base_url, "/api/experiment/reset", method="POST",
        body={
            "mode": last_mode,
            "wrongInitialLinkLossQ": last_scenario in ("link_loss_wrong_initial_Q", "wrong_initial_action"),
            "plantedWrongTarget": False,
            "resetQPolicy": True,
            "diagnosisSourceMode": args.diagnosis_source,
        },
        timeout=180,
    )
    wait_for_clean_system(args.base_url, args.healthy_reset_seconds)


def learning_curve_rows() -> set[tuple[str, str, str]]:
    return {
        (row.get("mode", ""), row.get("seed", ""), row.get("incident_index", ""))
        for row in read_csv(RESULTS / "learning_curve.csv")
    }


def run_learning_curves(args: argparse.Namespace) -> None:
    curve_path = RESULTS / "learning_curve.csv"
    if not curve_path.exists():
        with curve_path.open("w", newline="", encoding="utf-8") as stream:
            csv.writer(stream).writerow(LEARNING_HEADER)
    completed_incidents = {row.get("run_id") for row in read_csv(RESULTS / "incidents.csv")}
    completed_curves = learning_curve_rows()
    pairs = [(mode, args.seed + offset) for offset in range(5) for mode in (
        "PROPOSED_NO_ATTRIB", "PROPOSED"
    )]
    random.Random(args.seed ^ 0xC0FFEE).shuffle(pairs)
    links = list(LINKS)
    for mode, learning_seed in pairs:
        print(f"Learning sequence start: mode={mode}, seed={learning_seed}", flush=True)
        for incident_index in range(1, 26):
            run_id = f"learning|{mode}|{learning_seed}|incident{incident_index}"
            curve_key = (mode, str(learning_seed), str(incident_index))
            if run_id in completed_incidents:
                if curve_key not in completed_curves:
                    saved = next(
                        row for row in read_csv(RESULTS / "incidents.csv")
                        if row.get("run_id") == run_id
                    )
                    write_csv_row(curve_path, LEARNING_HEADER, {
                        "mode": mode,
                        "seed": learning_seed,
                        "incident_index": incident_index,
                        "first_attempt_success": saved.get("first_attempt_success", "NA"),
                        "attempts": saved.get("attempts", "NA"),
                    })
                    completed_curves.add(curve_key)
                print(f"  SKIP completed learning incident {incident_index}/25", flush=True)
                continue

            link = links[(learning_seed + incident_index - 2) % len(links)]
            target = f"{link[0]}->{link[1]}"
            row, attempts = run_one(
                args.base_url, mode, "link_loss_wrong_initial_Q", incident_index,
                learning_seed, run_id, target, "link", "link_loss", False,
                incident_index == 1, args.healthy_reset_seconds,
                args.incident_timeout_seconds, args.poll_interval_seconds,
                args.cpu_hog_threads, args.link_delay_ms, args.link_loss_pct,
                args.diagnosis_source,
            )
            append_trial(row, attempts)
            completed_incidents.add(run_id)
            curve_row = {
                "mode": mode,
                "seed": learning_seed,
                "incident_index": incident_index,
                "first_attempt_success": row.get("first_attempt_success", "NA"),
                "attempts": row.get("attempts", "NA"),
            }
            write_csv_row(curve_path, LEARNING_HEADER, curve_row)
            completed_curves.add(curve_key)
            print(
                f"  Learning {incident_index}/25: first_attempt_success="
                f"{curve_row['first_attempt_success']}, attempts={curve_row['attempts']}",
                flush=True,
            )
    print("Learning curves complete; performing final reset while preserving learned Q values.", flush=True)
    api_request(
        args.base_url, "/api/experiment/reset", method="POST",
        body={
            "mode": "PROPOSED",
            "wrongInitialLinkLossQ": True,
            "plantedWrongTarget": False,
            "resetQPolicy": False,
        },
        timeout=180,
    )
    wait_for_clean_system(args.base_url, args.healthy_reset_seconds)


def print_row_counts() -> None:
    for name in (
        "incidents.csv", "attempts.csv", "learning_curve.csv", "dataset_stats.csv",
        "diagnosis_eval.csv", "diagnosis_latency.csv", "setup.json",
    ):
        path = RESULTS / name
        if path.exists() and path.suffix == ".csv":
            rows = read_csv(path)
            print(f"RESULT {name}: data_rows={len(rows)}", flush=True)
        elif path.exists():
            print(f"RESULT {name}: present bytes={path.stat().st_size}", flush=True)
        else:
            print(f"RESULT {name}: absent", flush=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--reps", type=int, default=5)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--healthy-reset-seconds", type=int, default=DEFAULT_HEALTHY_RESET_SECONDS)
    parser.add_argument("--incident-timeout-seconds", type=int, default=DEFAULT_INCIDENT_TIMEOUT_SECONDS)
    parser.add_argument("--poll-interval-seconds", type=float, default=0.5)
    parser.add_argument("--diagnosis-source", choices=("modify", "fallback", "auto"), default="auto")
    parser.add_argument("--cpu-hog-threads", type=int, default=CPU_HOG_THREADS)
    parser.add_argument("--link-delay-ms", type=int, default=LINK_DELAY_MS)
    parser.add_argument("--link-loss-pct", type=float, default=LINK_LOSS_PCT)
    parser.add_argument("--learning", action="store_true", help="Run the 25-incident learning curves.")
    parser.add_argument("--skip-main", action="store_true", help="Run learning curves only.")
    parser.add_argument("--skip-learning", action="store_true", help="Run the main matrix only.")
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume a matching interrupted run; otherwise existing results are archived before a fresh run.",
    )
    args = parser.parse_args()
    if args.reps < 1:
        parser.error("--reps must be at least 1")
    if args.healthy_reset_seconds < 1 or args.incident_timeout_seconds < 1:
        parser.error("reset and incident timeouts must be positive")
    if args.poll_interval_seconds <= 0:
        parser.error("--poll-interval-seconds must be positive")
    if args.cpu_hog_threads < 1 or args.link_delay_ms < 0 or not 0.0 <= args.link_loss_pct <= 100.0:
        parser.error("fault intensities must be non-negative (CPU threads >= 1, link loss <= 100%)")
    if args.skip_main and args.skip_learning:
        parser.error("--skip-main and --skip-learning cannot both be set")
    if not args.skip_main and not args.learning:
        args.skip_learning = True
    if args.skip_main and not args.learning:
        parser.error("--skip-main requires --learning")
    return args


def main() -> None:
    args = parse_args()
    RESULTS.mkdir(exist_ok=True)
    run_started_at = timestamp_iso()
    run_started_clock = time.monotonic()
    print(
        f"Run configuration: reps={args.reps}, seed={args.seed}, "
        f"healthy_reset_seconds={args.healthy_reset_seconds}, "
        f"incident_timeout_seconds={args.incident_timeout_seconds}, "
        f"learning={args.learning}, skip_main={args.skip_main}, resume={args.resume}",
        flush=True,
    )
    api_request(args.base_url, "/api/status", timeout=10)
    if args.resume:
        validate_resume(args)
    else:
        archived = archive_existing_results(RESULTS)
        if archived is not None:
            print(f"Archived previous result set to {archived.relative_to(ROOT)}", flush=True)
    save_setup(args.base_url, args)
    record_execution_session(run_started_at, run_started_clock, args, complete=False)
    append_dataset_stats()
    evaluate_modify_dataset()
    if not args.skip_main:
        main_experiments(args)
    if args.learning and not args.skip_learning:
        run_learning_curves(args)
    save_diagnosis_latency(args.base_url)
    record_execution_session(run_started_at, run_started_clock, args, complete=True)
    print_row_counts()


if __name__ == "__main__":
    main()
