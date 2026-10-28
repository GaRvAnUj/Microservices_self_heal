import pytest

from scripts.run_experiments import (
    make_attempt_rows,
    scenario_category,
    summary_metrics,
    target_for,
    validate_incident_targets,
    write_csv_row,
)
from scripts.result_archive import archive_existing_results


def test_rejects_action_with_null_target():
    with pytest.raises(RuntimeError, match="action 1 has no valid target"):
        validate_incident_targets({
            "actions": [{"targetComponent": None}],
        })


def test_rejects_diagnosis_without_candidate():
    with pytest.raises(RuntimeError, match="diagnosed incident has no valid candidate"):
        validate_incident_targets({
            "diagnosisSource": "MODIFy",
            "targetEntity": None,
            "actions": [],
        })


def test_allows_undetected_incident_without_actions():
    validate_incident_targets({
        "diagnosisSource": "NA",
        "targetEntity": None,
        "actions": [],
    })


def test_attempt_rows_record_planted_wrong_target_only_for_proposed():
    rows = make_attempt_rows(
        "run-1",
        "PROPOSED",
        "planted_wrong_target",
        [{
            "actionType": "restart",
            "targetComponent": "compose-post",
            "verdict": "WRONG_TARGET",
        }],
        "text",
        "cpu_hog",
        planted=True,
    )

    assert rows[0]["mode"] == "PROPOSED"
    assert rows[0]["scenario"] == "planted_wrong_target"
    assert rows[0]["attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)"] == "WRONG_TARGET"
    assert rows[0]["ground_truth_cause(wrong_target|ineffective_action|NA)"] == "wrong_target"


def test_attempt_rows_derive_wrong_action_from_scenario_facts():
    rows = make_attempt_rows(
        "run-2",
        "PROPOSED",
        "link_loss_wrong_initial_Q",
        [{
            "actionType": "tune_link",
            "targetComponent": "gateway->compose-post",
            "verdict": "INEFFECTIVE_ACTION",
        }],
        "gateway->compose-post",
        "link_loss",
        planted=False,
    )

    assert rows[0]["ground_truth_cause(wrong_target|ineffective_action|NA)"] == "ineffective_action"


def test_non_attribution_modes_never_emit_attribution_verdicts():
    rows = make_attempt_rows(
        "run-3",
        "PROPOSED_NO_ATTRIB",
        "planted_wrong_target",
        [{
            "actionType": "restart",
            "targetComponent": "compose-post",
            "verdict": "NA",
        }],
        "text",
        "cpu_hog",
        planted=True,
    )

    assert rows[0]["attribution_verdict(WRONG_TARGET|INEFFECTIVE_ACTION|INCONCLUSIVE|NA)"] == "NA"
    assert rows[0]["ground_truth_cause(wrong_target|ineffective_action|NA)"] == "NA"


def test_critical_and_masked_scenario_targets_are_distinguished():
    assert target_for("link_loss@gateway->compose-post", 1, 1111) == (
        "gateway->compose-post", "link", "link_loss"
    )
    assert scenario_category("gateway->compose-post") == "critical"
    assert scenario_category("compose-post->media") == "masked_by_degradation"


def test_slo_recovery_metrics_are_measured_from_poll_samples():
    incident = {"status": "RESOLVED", "resolvedAt": 11000}
    samples = [
        (0.0, {"slo": {"sloHealthy": True}}),
        (2.0, {"slo": {"sloHealthy": False}}),
        (4.0, {"slo": {"sloHealthy": False}}),
        (6.0, {"slo": {"sloHealthy": True}}),
    ]

    metrics = summary_metrics(samples, {}, incident, 1000, {}, {})

    assert metrics["slo_violated"] is True
    assert metrics["slo_violation_start_ts"] == "1970-01-01T00:00:03.000Z"
    assert metrics["mttr_from_injection_s"] == 10.0
    assert metrics["mttr_from_slo_violation_s"] == 8.0
    assert metrics["slo_violation_s"] == 4.0


def test_archives_existing_results_without_touching_archive_history(tmp_path):
    results = tmp_path / "results"
    results.mkdir()
    original = results / "incidents.csv"
    original.write_text("run_id\nold-run\n", encoding="utf-8")
    prior_archive = results / "archive_2026-10-25"
    prior_archive.mkdir()
    (prior_archive / "previous.txt").write_text("preserve", encoding="utf-8")

    archived = archive_existing_results(results)

    assert archived is not None
    assert not original.exists()
    assert (archived / "incidents.csv").read_text(encoding="utf-8") == "run_id\nold-run\n"
    assert (prior_archive / "previous.txt").read_text(encoding="utf-8") == "preserve"


def test_csv_append_rejects_a_different_existing_header(tmp_path):
    path = tmp_path / "incidents.csv"
    path.write_text("old_header\nold-value\n", encoding="utf-8")

    with pytest.raises(RuntimeError, match="header does not match"):
        write_csv_row(path, ("new_header",), {"new_header": "new-value"})
