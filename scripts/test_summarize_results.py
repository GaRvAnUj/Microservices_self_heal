from types import ModuleType, SimpleNamespace

import pytest

from scripts.summarize_results import statistical_tests, summarize_scenarios


def test_masked_scenarios_are_counted_but_excluded_from_recovery_and_mttr():
    summaries, excluded = summarize_scenarios([
        {
            "scenario_category(critical|masked_by_degradation)": "critical",
            "scenario": "cpu_hog@compose-post",
            "mode": "PROPOSED",
            "recovered": "true",
            "first_attempt_success": "true",
            "mttr_from_slo_violation_s": "5.0",
            "healthy_service_restarts": "0",
            "healthy_service_downtime_s": "0",
            "attempts": "1",
            "slo_violation_s": "5.0",
            "slo_violated": "true",
            "notes": "",
        },
        {
            "scenario_category(critical|masked_by_degradation)": "masked_by_degradation",
            "scenario": "cpu_hog@media",
            "mode": "PROPOSED",
            "recovered": "false",
            "first_attempt_success": "false",
            "mttr_from_slo_violation_s": "NA",
            "healthy_service_restarts": "0",
            "healthy_service_downtime_s": "0",
            "attempts": "0",
            "slo_violation_s": "0.0",
            "slo_violated": "false",
            "notes": "",
        },
    ])

    by_category = {row["scenario_category"]: row for row in summaries}
    assert not excluded
    assert by_category["critical"]["n"] == 1
    assert by_category["critical"]["recovery_rate"] == 1.0
    assert by_category["critical"]["mttr_from_slo_violation_mean_s"] == 5.0
    assert by_category["masked_by_degradation"]["n"] == 1
    assert by_category["masked_by_degradation"]["recovery_rate"] == "NA"
    assert by_category["masked_by_degradation"]["mttr_from_slo_violation_mean_s"] == "NA"


def test_statistical_tests_report_exact_wilcoxon_and_median_difference(monkeypatch):
    scipy = ModuleType("scipy")
    stats = ModuleType("scipy.stats")
    wilcoxon_calls = []

    def wilcoxon(control, proposed, **kwargs):
        wilcoxon_calls.append(kwargs)
        return SimpleNamespace(statistic=2.0, pvalue=0.03125)

    stats.wilcoxon = wilcoxon
    stats.fisher_exact = lambda table: (1.5, 0.4)
    scipy.stats = stats
    monkeypatch.setitem(__import__("sys").modules, "scipy", scipy)
    monkeypatch.setitem(__import__("sys").modules, "scipy.stats", stats)

    incidents = []
    for repetition in range(1, 7):
        common = {
            "scenario_category(critical|masked_by_degradation)": "critical",
            "scenario": "cpu_hog@compose-post",
            "fault_target": "compose-post",
            "repetition": str(repetition),
            "seed": "20261025",
            "recovered": "true",
            "mttr_from_slo_violation_s": str(20 - repetition),
            "healthy_service_restarts": str(repetition),
        }
        incidents.append({**common, "mode": "NO_HEAL"})
        incidents.append({
            **common,
            "mode": "PROPOSED",
            "mttr_from_slo_violation_s": str(10 - repetition),
            "healthy_service_restarts": "0",
        })

    results = statistical_tests(incidents)
    mttr_result = next(
        row for row in results
        if row["test"] == "Wilcoxon NO_HEAL vs PROPOSED"
        and row["metric"] == "mttr_from_slo_violation_s"
    )

    assert mttr_result["n"] == 6
    assert mttr_result["p_value"] == 0.03125
    assert mttr_result["median_difference"] == 10.0
    assert wilcoxon_calls[0]["method"] == "exact"


def test_statistical_tests_report_insufficient_n_without_p_value(monkeypatch):
    scipy = ModuleType("scipy")
    stats = ModuleType("scipy.stats")
    stats.wilcoxon = lambda *args, **kwargs: pytest.fail("Wilcoxon must not run below n=6")
    stats.fisher_exact = lambda table: (1.0, 1.0)
    scipy.stats = stats
    monkeypatch.setitem(__import__("sys").modules, "scipy", scipy)
    monkeypatch.setitem(__import__("sys").modules, "scipy.stats", stats)

    results = statistical_tests([])
    wilcoxon_results = [row for row in results if row["test"].startswith("Wilcoxon")]

    assert len(wilcoxon_results) == 4
    assert all(row["n"] == 0 for row in wilcoxon_results)
    assert all(row["p_value"] == "insufficient n" for row in wilcoxon_results)
