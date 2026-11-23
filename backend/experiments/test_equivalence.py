from compare_equivalent import scenarios
from app.planner import plan
from app.optimizer_experiment import solve, metrics


def test_solver_and_baseline_satisfy_the_same_domain_validator():
    cases = list(scenarios())
    assert len(cases) == 9
    for name, request in cases:
        before = request.model_dump_json()
        baseline = plan(request)
        candidate, status = solve(request, seconds=3)
        assert request.model_dump_json() == before, name
        assert not baseline.conflicts, (name, baseline.conflicts)
        assert not candidate.conflicts, (name, candidate.conflicts)
        assert status in ["OPTIMAL", "FEASIBLE"], (name, status)
        assert metrics(request, candidate)["conflicts"] == 0
