#!/usr/bin/env python3
"""Compute paper tables and PAPER_FACTS.md from captured experiment artifacts."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

try:
    from .result_archive import archive_paths
except ImportError:
    from result_archive import archive_paths


ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"
DOC = ROOT / "docs" / "PAPER_FACTS.md"

SUMMARY_HEADER = (
    "scenario_category", "scenario", "mode", "n", "slo_violation_count",
    "recovery_rate", "first_attempt_rate", "mttr_from_slo_violation_mean_s",
    "mttr_from_slo_violation_median_s", "mttr_from_slo_violation_std_s",
    "mean_healthy_restarts", "mean_healthy_downtime_s", "mean_attempts",
    "mean_slo_violation_s",
)
ATTRIBUTION_HEADER = (
    "ground_truth_cause", "attribution_verdict", "count", "accuracy",
    "inconclusive_rate", "wrong_rate", "n",
)
LOCALIZATION_HEADER = ("mode/diagnosis_source", "fault_target_type", "top1_accuracy", "n")
STAT_TEST_HEADER = ("test", "metric", "n", "statistic", "p_value", "median_difference")


def read_csv(name: str) -> list[dict[str, str]]:
    path = RESULTS / name
    if not path.exists():
        raise FileNotFoundError(f"Required results file does not exist: {path}")
    with path.open(newline="", encoding="utf-8-sig") as stream:
        return list(csv.DictReader(stream))


def write_csv(name: str, header: tuple[str, ...], rows: list[dict[str, Any]]) -> None:
    path = RESULTS / name
    archive_paths([path], RESULTS, ROOT)
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=header, extrasaction="ignore")
        writer.writeheader()
        for row in rows:
            writer.writerow({
                column: "NA" if row.get(column) is None or row.get(column) == "" else row[column]
                for column in header
            })


def number(value: Any) -> float | None:
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return None
    return parsed if math.isfinite(parsed) else None


def truth(value: Any) -> bool | None:
    if str(value).lower() == "true":
        return True
    if str(value).lower() == "false":
        return False
    return None


def mean(values: list[float]) -> float | str:
    return statistics.mean(values) if values else "NA"


def summarize_scenarios(
    incidents: list[dict[str, str]],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    groups: dict[tuple[str, str, str], list[dict[str, str]]] = defaultdict(list)
    excluded = []
    for row in incidents:
        if "injection_failed:" in row.get("notes", ""):
            excluded.append(row)
            continue
        groups[(
            row.get("scenario_category(critical|masked_by_degradation)", "NA"),
            row.get("scenario", "NA"),
            row.get("mode", "NA"),
        )].append(row)

    summaries = []
    for (category, scenario, mode), rows in sorted(groups.items()):
        is_critical = category == "critical"
        recovered = sum(truth(row.get("recovered")) is True for row in rows)
        first_attempts = [
            truth(row.get("first_attempt_success"))
            for row in rows
            if truth(row.get("first_attempt_success")) is not None
        ]
        successful_mttr = [
            value for row in rows
            if truth(row.get("recovered")) is True
            if (value := number(row.get("mttr_from_slo_violation_s"))) is not None
        ]
        healthy_restarts = [value for row in rows if (value := number(row.get("healthy_service_restarts"))) is not None]
        healthy_downtime = [value for row in rows if (value := number(row.get("healthy_service_downtime_s"))) is not None]
        attempts = [value for row in rows if (value := number(row.get("attempts"))) is not None]
        slo_times = [value for row in rows if (value := number(row.get("slo_violation_s"))) is not None]
        summaries.append({
            "scenario_category": category,
            "scenario": scenario,
            "mode": mode,
            "n": len(rows),
            "slo_violation_count": sum(truth(row.get("slo_violated")) is True for row in rows),
            "recovery_rate": recovered / len(rows) if rows and is_critical else "NA",
            "first_attempt_rate": (
                sum(value is True for value in first_attempts) / len(first_attempts)
                if first_attempts and is_critical else "NA"
            ),
            "mttr_from_slo_violation_mean_s": mean(successful_mttr) if is_critical else "NA",
            "mttr_from_slo_violation_median_s": (
                statistics.median(successful_mttr) if successful_mttr and is_critical else "NA"
            ),
            "mttr_from_slo_violation_std_s": (
                statistics.stdev(successful_mttr) if len(successful_mttr) > 1 and is_critical else "NA"
            ),
            "mean_healthy_restarts": mean(healthy_restarts),
            "mean_healthy_downtime_s": mean(healthy_downtime),
            "mean_attempts": mean(attempts),
            "mean_slo_violation_s": mean(slo_times),
        })
    return summaries, excluded


def summarize_attribution(attempts: list[dict[str, str]]) -> list[dict[str, Any]]:
    eligible = [
        row for row in attempts
        if row.get("mode") == "PROPOSED"
        and row.get("ground_truth_cause(wrong_target|ineffective_action|NA)")
        in ("wrong_target", "ineffective_action")
        and row.get("attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)")
        in ("WRONG_TARGET", "INEFFECTIVE_ACTION", "INCONCLUSIVE")
    ]
    by_cause: dict[str, list[dict[str, str]]] = defaultdict(list)
    for row in eligible:
        by_cause[row["ground_truth_cause(wrong_target|ineffective_action|NA)"]].append(row)
    output = []
    for cause, rows in sorted(by_cause.items()):
        counts = Counter(row["attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)"] for row in rows)
        correct = sum(
            row["ground_truth_cause(wrong_target|ineffective_action|NA)"].upper()
            == row["attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)"]
            for row in rows
        )
        for verdict, count in sorted(counts.items()):
            output.append({
                "ground_truth_cause": cause,
                "attribution_verdict": verdict,
                "count": count,
                "accuracy": correct / len(rows) if rows else "NA",
                "inconclusive_rate": counts["INCONCLUSIVE"] / len(rows) if rows else "NA",
                "wrong_rate": counts["WRONG_TARGET"] / len(rows) if rows else "NA",
                "n": len(rows),
            })
    return output


def summarize_localization(incidents: list[dict[str, str]], excluded: list[dict[str, str]]) -> list[dict[str, Any]]:
    eligible = [
        row for row in incidents
        if row not in excluded
        and truth(row.get("localization_correct(true|false)")) is not None
        and row.get("top_candidate") not in ("", "NA")
    ]
    groups: dict[tuple[str, str], list[dict[str, str]]] = defaultdict(list)
    for row in eligible:
        source = row.get("diagnosis_source(MODIFY|FALLBACK)", "NA")
        key = (f"{row.get('mode', 'NA')}/{source}", row.get("fault_target_type(node|link)", "NA"))
        groups[key].append(row)
    return [
        {
            "mode/diagnosis_source": key[0],
            "fault_target_type": key[1],
            "top1_accuracy": sum(
                truth(row.get("localization_correct(true|false)")) is True for row in rows
            ) / len(rows),
            "n": len(rows),
        }
        for key, rows in sorted(groups.items())
    ]


def paired_rows(incidents: list[dict[str, str]], control_mode: str) -> tuple[
    dict[tuple[str, str, str, str], dict[str, str]],
    dict[tuple[str, str, str, str], dict[str, str]],
]:
    control: dict[tuple[str, str, str, str], dict[str, str]] = {}
    proposed: dict[tuple[str, str, str, str], dict[str, str]] = {}
    for row in incidents:
        if row.get("scenario_category(critical|masked_by_degradation)") != "critical":
            continue
        if "injection_failed:" in row.get("notes", ""):
            continue
        if row.get("mode") not in (control_mode, "PROPOSED"):
            continue
        key = (
            row.get("scenario", ""),
            row.get("fault_target", ""),
            row.get("repetition", ""),
            row.get("seed", ""),
        )
        (control if row["mode"] == control_mode else proposed)[key] = row
    shared = control.keys() & proposed.keys()
    return (
        {key: control[key] for key in shared},
        {key: proposed[key] for key in shared},
    )


def paired_values(incidents: list[dict[str, str]], metric: str,
                  require_success: bool,
                  control_mode: str) -> tuple[list[float], list[float]]:
    control_rows, proposed_rows = paired_rows(incidents, control_mode)
    base: dict[tuple[str, str, str, str], float] = {}
    proposed: dict[tuple[str, str, str, str], float] = {}
    for key, row in control_rows.items():
        if require_success and truth(row.get("recovered")) is not True:
            continue
        value = number(row.get(metric))
        if value is not None:
            base[key] = value
    for key, row in proposed_rows.items():
        if require_success and truth(row.get("recovered")) is not True:
            continue
        value = number(row.get(metric))
        if value is not None:
            proposed[key] = value
    shared = sorted(base.keys() & proposed.keys())
    return [base[key] for key in shared], [proposed[key] for key in shared]


def statistical_tests(incidents: list[dict[str, str]]) -> list[dict[str, Any]]:
    tests = []
    try:
        from scipy.stats import fisher_exact, wilcoxon
    except ImportError:
        for control_mode in ("NO_HEAL", "BASELINE"):
            for metric in ("mttr_from_slo_violation_s", "healthy_service_restarts", "recovered"):
                tests.append({
                    "test": f"{control_mode} vs PROPOSED (SciPy unavailable)",
                    "metric": metric,
                    "n": 0,
                    "statistic": "NA",
                    "p_value": "NA",
                    "median_difference": "NA",
                })
        print("Statistical tests: SciPy is not installed; wrote NA, no dependency installed.", flush=True)
        return tests

    for control_mode in ("NO_HEAL", "BASELINE"):
        for metric, require_success in (
            ("mttr_from_slo_violation_s", True),
            ("healthy_service_restarts", False),
        ):
            control, proposed = paired_values(
                incidents, metric, require_success, control_mode
            )
            if len(control) < 6:
                tests.append({
                    "test": f"Wilcoxon {control_mode} vs PROPOSED",
                    "metric": metric,
                    "n": len(control),
                    "statistic": "NA",
                    "p_value": "insufficient n",
                    "median_difference": "NA",
                })
                print(
                    f"Wilcoxon {control_mode} vs PROPOSED {metric}: "
                    f"insufficient n={len(control)} (requires 6).",
                    flush=True,
                )
                continue
            result = wilcoxon(
                control,
                proposed,
                zero_method="zsplit",
                alternative="two-sided",
                method="exact",
            )
            tests.append({
                "test": f"Wilcoxon {control_mode} vs PROPOSED",
                "metric": metric,
                "n": len(control),
                "statistic": result.statistic,
                "p_value": result.pvalue,
                "median_difference": statistics.median(
                    control_value - proposed_value
                    for control_value, proposed_value in zip(control, proposed)
                ),
            })
            print(
                f"Wilcoxon {control_mode} vs PROPOSED {metric}: "
                f"n={len(control)}, statistic={result.statistic}, p={result.pvalue}",
                flush=True,
            )

        control_rows, proposed_rows = paired_rows(incidents, control_mode)
        control_recovered = sum(truth(row.get("recovered")) is True for row in control_rows.values())
        proposed_recovered = sum(truth(row.get("recovered")) is True for row in proposed_rows.values())
        pair_count = min(len(control_rows), len(proposed_rows))
        if pair_count:
            odds_ratio, p_value = fisher_exact([
                [control_recovered, pair_count - control_recovered],
                [proposed_recovered, pair_count - proposed_recovered],
            ])
        else:
            odds_ratio, p_value = "NA", "NA"
        tests.append({
            "test": f"Fisher exact {control_mode} vs PROPOSED",
            "metric": "recovered",
            "n": pair_count,
            "statistic": odds_ratio,
            "p_value": p_value,
            "median_difference": "NA",
        })
    return tests


def csv_counts(name: str) -> int:
    path = RESULTS / name
    if not path.exists():
        return 0
    with path.open(newline="", encoding="utf-8-sig") as stream:
        return max(0, sum(1 for _ in csv.reader(stream)) - 1)


def facts_document(incidents: list[dict[str, str]], attempts: list[dict[str, str]],
                   excluded: list[dict[str, str]]) -> str:
    setup_path = RESULTS / "setup.json"
    setup = json.loads(setup_path.read_text(encoding="utf-8")) if setup_path.exists() else {}
    data = read_csv("dataset_stats.csv") if (RESULTS / "dataset_stats.csv").exists() else []
    dataset = data[0] if data else {}
    eval_rows = read_csv("diagnosis_eval.csv") if (RESULTS / "diagnosis_eval.csv").exists() else []
    completed_sessions = [
        item for item in setup.get("execution_sessions", [])
        if item.get("status") == "completed"
    ]
    execution_durations = [
        f"- `{item.get('started_at_utc')}`: elapsed_s={item.get('elapsed_s')}, "
        f"reps={item.get('reps')}, seed={item.get('seed')}, "
        f"learning={item.get('learning_enabled')}"
        for item in completed_sessions
    ] or ["- NA: no completed runner session recorded"]
    diagnosis_counts = Counter(
        row.get("diagnosis_source(MODIFY|FALLBACK)", "NA") for row in incidents
        if row.get("diagnosis_source(MODIFY|FALLBACK)") in ("MODIFY", "FALLBACK")
        and "injection_failed:" not in row.get("notes", "")
    )
    by_mode_scenario = Counter(
        (row.get("mode", "NA"), row.get("scenario", "NA"))
        for row in incidents if "injection_failed:" not in row.get("notes", "")
    )
    timeouts = [
        row for row in incidents
        if "hard_timeout" in row.get("notes", "") or "no_incident_detected" in row.get("notes", "")
    ]
    failed = [
        row for row in incidents
        if truth(row.get("recovered")) is False and "injection_failed:" not in row.get("notes", "")
    ]
    mode_scenario_lines = [
        f"- `{mode}` / `{scenario}`: {count}"
        for (mode, scenario), count in sorted(by_mode_scenario.items())
    ] or ["- NA: no experiment rows"]
    eval_lines = [
        f"- `{row.get('diagnosis_source')}` / `{row.get('split(train|test)')}` / "
        f"`{row.get('fault_type')}`: top1={row.get('top1_accuracy')}, n={row.get('n')}"
        for row in eval_rows
    ] or ["- NA: no diagnosis evaluation rows"]
    anomaly_lines = [
        f"- `{row.get('run_id', 'NA')}` / `{row.get('scenario', 'NA')}` / "
        f"`{row.get('mode', 'NA')}`: status={row.get('recovered', 'NA')}; "
        f"reason=`{row.get('notes', '') or 'non-recovered trial'}`"
        for row in incidents
        if (
            "hard_timeout" in row.get("notes", "")
            or "no_incident_detected" in row.get("notes", "")
            or "injection_failed:" in row.get("notes", "")
            or truth(row.get("recovered")) is False
        )
    ] or ["- None recorded."]
    decisions_path = ROOT / "docs" / "DECISIONS.md"
    decision_lines = (
        [
            line.strip() for line in decisions_path.read_text(encoding="utf-8").splitlines()
            if line.strip().startswith("## DEC-")
        ]
        if decisions_path.is_file() else []
    )
    change_lines = decision_lines or ["- No decision IDs recorded."]

    systems = [
        "- `healer` control plane: port 8080",
        "- `gateway`: 8081 -> `compose-post`: 8082",
        "- `compose-post`: 8082 -> `text`: 8083, `media`: 8084, `user`: 8085, `post-storage`: 8086",
        "- `diagnosis-service` (FastAPI/Uvicorn): port 5000",
        "- Critical downstream: `post-storage`; other listed downstreams use non-critical degradation handling.",
        "- Call graph source: `healer/src/main/java/com/selfheal/healer/service/ServiceManager.java`, `service-app/src/main/java/com/selfheal/service/controller/WorkController.java`.",
    ]
    telemetry = [
        "- Node inputs: process/system CPU load, used/committed heap bytes, cumulative request count.",
        "- Link inputs: average latency, error rate, call count.",
        "- Log counters: `LINK_TIMEOUT`, `DOWNSTREAM_ERROR`.",
        f"- Telemetry poll interval: {setup.get('telemetry_poll_interval_ms', 'NA')} ms; runtime config: {setup.get('window_length_s', 'NA')} s SLO window.",
        "- Collector smooths the last three telemetry snapshots; baseline is mean and population standard deviation with epsilon 0.001.",
        "- MODIFy projection approximations: outgoing-or-incoming mean edge latency; calls from outgoing edge count or node request count; rx/tx estimated as calls*1024/calls*512; event features are threshold-derived and multiplied by chunk length.",
        "- Source: `healer/src/main/java/com/selfheal/healer/collector/TelemetryCollectorService.java`, `healer/src/main/java/com/selfheal/healer/collector/BaselineProfileManager.java`, `diagnosis-service/modify_inferencer.py`.",
    ]
    detect = setup.get("detector_thresholds", {})
    attribution = setup.get("attribution_thresholds", {})
    q_update = [
        "- Success: `q <- q + 0.3 * (1 - q)`.",
        "- Full attribution: penalize only `INEFFECTIVE_ACTION` (`q <- 0.3*q`); `WRONG_TARGET`/`INCONCLUSIVE` do not penalize.",
        "- `PROPOSED_NO_ATTRIB`: failed attempt attribution verdict is `NA`; every failure penalizes uniformly.",
        "- Link actions: `tune_link` and `breaker`; `tune_edge` is recorded as an alias of `tune_link`.",
    ]
    fault_rows = [
        f"- CPU hog: `POST /chaos/cpu?on=true`; requested busy-loop thread count is {setup.get('fault_parameters', {}).get('cpu_hog_threads', 'NA')}.",
        "- Crash: delayed `Runtime.halt(1)` on target service.",
        f"- Link delay: injected delay {setup.get('fault_parameters', {}).get('link_delay_ms', 'NA')} ms.",
        f"- Link loss: injected loss {setup.get('fault_parameters', {}).get('link_loss_pct', 'NA')}%.",
        "- Wrong initial link-loss Q prior: `tune_link=0.9`, `breaker=0.5`; normal prior reverses those values.",
        "- Planted-wrong-target: deterministic 30% selection by fixed seed among the main planted-target matrix; target is replaced with a neighboring topology component.",
    ]
    not_measured = [
        "- Live collection duration for the existing MODIFy chunks: NA (no collection-run duration is recorded).",
        "- Fallback localizer held-out accuracy: NA (no matching held-out z-score dataset).",
        "- Diagnosis source is NA for BASELINE because that mode bypasses diagnosis.",
        "- Any setup/runtime field equal to `NA` in `results/setup.json` was unavailable from the running host.",
    ]
    lines = [
        "# Paper facts",
        "",
        "## System",
        *systems,
        "",
        "## Telemetry",
        *telemetry,
        "",
        "## Detection",
        f"- SLO: p95 < {setup.get('slo_definition', {}).get('p95_latency_ms_strictly_below', 'NA')} ms; error rate < {setup.get('slo_definition', {}).get('error_rate_strictly_below', 'NA')}.",
        f"- Incident trigger: SLO unhealthy or at least two consecutive samples at z >= {detect.get('anomaly_z', 'NA')}.",
        f"- Statistical link-candidate gate: z >= {detect.get('link_candidate_z', 'NA')}; callee internal abnormality threshold: {detect.get('callee_abnormality_z', 'NA')}.",
        "",
        "## Diagnosis",
        f"- Diagnosis source used by incident rows: MODIFY={diagnosis_counts['MODIFY']}; FALLBACK={diagnosis_counts['FALLBACK']}; BASELINE/undetected=NA.",
        f"- MODIFy assets present: {setup.get('modify_assets', {}).get('present', 'NA')}. Checkpoint metadata only: {json.dumps(setup.get('modify_assets', {}).get('checkpoint', {}), sort_keys=True)}.",
        "- Python link candidate path is statistical, not learned; MODIFy is used for node diagnosis when the link path does not return a candidate. Java statistical localizer is the service-client fallback.",
        *eval_lines,
        "",
        "## Healing",
        "- BASELINE: service-level max-abnormality target, process restart, up to three attempts.",
        "- PROPOSED_NO_LINK: MODIFy/statistical service-level diagnosis; link candidate selection disabled; fault-type policy remains enabled.",
        "- PROPOSED_NO_ATTRIB: link-capable diagnosis and policy actions; failure feedback uniformly penalizes.",
        "- PROPOSED: link-capable diagnosis, policy action, attribution and selective Q update.",
        *q_update,
        "",
        "## Attribution",
        f"- HIGH threshold: z >= {attribution.get('high_z', 'NA')}.",
        f"- LOW threshold: target z < {attribution.get('low_z', 'NA')}.",
        f"- Dominance threshold: max other z <= {attribution.get('dominance_ratio', 'NA')} * target z.",
        "- Link action is WRONG_TARGET when its callee z reaches HIGH; target HIGH with no dominant other component is INEFFECTIVE_ACTION; target LOW with another component HIGH is WRONG_TARGET; otherwise INCONCLUSIVE.",
        "",
        "## Fault injection definitions",
        *fault_rows,
        "",
        "## Experiment protocol",
        f"- Seed: {setup.get('random_seed', 'NA')}; main incident rows: {len(incidents)}; attempts rows: {len(attempts)}.",
        f"- Modes/scenarios/repetitions: {json.dumps(setup.get('experiment_protocol', {}).get('modes', []))} / {json.dumps(setup.get('experiment_protocol', {}).get('scenarios', []))} / see `incidents.csv`.",
        f"- Per-incident timeout: {setup.get('experiment_protocol', {}).get('incident_timeout_s', 'NA')} s; healthy reset: {setup.get('experiment_protocol', {}).get('healthy_reset_s', 'NA')} consecutive seconds.",
        *execution_durations,
        "- Main run order is shuffled with the recorded seed; CSV run IDs are idempotency keys. Learning curve retains Q values within each mode/seed sequence and resets Q at sequence start.",
        "- `mttr_s` is fault-injection timestamp to recovery timestamp. SLO violation time is sampled from API polling intervals and is not an interpolated event timestamp.",
        "- All service restarts and chaos clearing are done before the stable healthy reset interval; the final trial is followed by the same reset.",
        "",
        "## Dataset",
        f"- Existing MODIFy chunks: windows={dataset.get('windows_collected', 'NA')}; window_length_s={dataset.get('window_length_s', 'NA')}.",
        f"- Fault-run counts: `{dataset.get('faults_injected_by_type', 'NA')}`.",
        f"- Service labels: `{dataset.get('labels_by_service', 'NA')}`.",
        f"- Train/test windows: `{dataset.get('train_test_split', 'NA')}`.",
        f"- Live collection duration: {dataset.get('collection_duration_min', 'NA')} min; no experiment-run collection duration recorded.",
        "",
        "## Environment",
        f"- Git commit: `{setup.get('git_commit_hash', 'NA')}`; worktree dirty at capture: {setup.get('git_worktree_dirty', 'NA')}.",
        f"- Date UTC: {setup.get('date_utc', 'NA')}; OS: {setup.get('os', 'NA')}.",
        f"- CPU/RAM: `{json.dumps(setup.get('cpu', {}), sort_keys=True)}`.",
        f"- Java: `{setup.get('java_version', 'NA')}`; Maven: `{setup.get('maven_version', 'NA')}`; Python: `{setup.get('python_version', 'NA')}`.",
        f"- Diagnosis packages: `{json.dumps(setup.get('diagnosis_service_library_versions', {}), sort_keys=True)}`.",
        f"- JVM flags: `{json.dumps(setup.get('jvm_flags', 'NA'))}`; JVM max memory bytes: {setup.get('jvm_max_memory_bytes', 'NA')}.",
        f"- Ports: `{json.dumps(setup.get('ports', {}), sort_keys=True)}`; load target: {setup.get('load_rate_req_s', 'NA')} req/s.",
        f"- Baseline profile duration from collector start: {setup.get('baseline_profile_duration_s', 'NA')} s.",
        "",
        "## Not measured",
        *not_measured,
        "",
        "## Known problems and anomalies seen in captured runs",
        f"- Timed-out/no-detection rows: {len(timeouts)}.",
        f"- Non-recovered injected trials: {len(failed)}.",
        *anomaly_lines,
        "",
        "## Code changes and decisions",
        *change_lines,
        "- See Healer and microservice logs under `logs/` for raw events; any log anomaly requiring interpretation must be added here with its observed run ID before paper submission.",
        "",
    ]
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    RESULTS.mkdir(exist_ok=True)
    incidents = read_csv("incidents.csv")
    attempts = read_csv("attempts.csv")
    scenarios, excluded = summarize_scenarios(incidents)
    attribution = summarize_attribution(attempts)
    localization = summarize_localization(incidents, excluded)
    tests = statistical_tests(incidents)
    write_csv("summary_by_scenario_mode.csv", SUMMARY_HEADER, scenarios)
    write_csv("attribution_confusion.csv", ATTRIBUTION_HEADER, attribution)
    write_csv("localization_summary.csv", LOCALIZATION_HEADER, localization)
    write_csv("stat_tests.csv", STAT_TEST_HEADER, tests)
    DOC.parent.mkdir(exist_ok=True)
    archive_paths([DOC], RESULTS, ROOT)
    DOC.write_text(facts_document(incidents, attempts, excluded), encoding="utf-8")

    source_counts = Counter(
        row.get("diagnosis_source(MODIFY|FALLBACK)", "NA")
        for row in incidents if row.get("diagnosis_source(MODIFY|FALLBACK)") in ("MODIFY", "FALLBACK")
    )
    timed_out = sum(
        "hard_timeout" in row.get("notes", "") or "no_incident_detected" in row.get("notes", "")
        for row in incidents
    )
    failed = sum(
        truth(row.get("recovered")) is False and "injection_failed:" not in row.get("notes", "")
        for row in incidents
    )
    print(f"CSV incidents.csv: rows={len(incidents)}", flush=True)
    print(f"CSV attempts.csv: rows={len(attempts)}", flush=True)
    for row in scenarios:
        print(
            f"COUNT mode={row['mode']} scenario={row['scenario']} n={row['n']} "
            f"recovery_rate={row['recovery_rate']}",
            flush=True,
        )
    print(f"Timed out/no detection={timed_out}; failed non-recovered={failed}", flush=True)
    print(
        f"Diagnosis source rows: MODIFY={source_counts['MODIFY']}, "
        f"FALLBACK={source_counts['FALLBACK']}",
        flush=True,
    )
    print(f"Excluded injection-failed rows={len(excluded)}", flush=True)
    for name in (
        "summary_by_scenario_mode.csv", "attribution_confusion.csv",
        "localization_summary.csv", "stat_tests.csv",
    ):
        print(f"CSV {name}: rows={csv_counts(name)}", flush=True)
    print(f"Markdown docs/PAPER_FACTS.md: bytes={DOC.stat().st_size}", flush=True)


if __name__ == "__main__":
    main()
