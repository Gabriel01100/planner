"""Restricted benchmark only: single-day tasks, one window, no recurrence/dependencies.
Run: PYTHONPATH=backend python backend/experiments/compare_cpsat.py
Never used by the production planner.
"""
import json
import random
from datetime import date, datetime, timezone
from uuid import UUID
from ortools.sat.python import cp_model
from app.models import Activity, State, Window, PlanRequest
from app.planner import plan


def benchmark(seed):
    rng = random.Random(seed)
    day = date(2027, 1, 4)
    activities = [Activity(id=UUID(int=rng.getrandbits(128)), title=f'Tarea {i}', startDate=day,
        duration=rng.choice([30, 45, 60, 90]), priority=rng.randint(1, 4)) for i in range(8)]
    state = State(activities=activities, availability=[Window(start=540, end=720)])
    baseline = plan(PlanRequest(state=state, start=day, days=1, now=datetime(2027, 1, 1, tzinfo=timezone.utc)))
    model = cp_model.CpModel()
    present, starts, intervals = [], [], []
    for i, task in enumerate(activities):
        duration = task.duration // 15
        exists = model.new_bool_var(f'p{i}')
        start = model.new_int_var(0, 12 - duration, f's{i}')
        interval = model.new_optional_fixed_size_interval_var(start, duration, exists, f'i{i}')
        present.append(exists); starts.append(start); intervals.append(interval)
    model.add_no_overlap(intervals)
    model.maximize(sum(1000 * p + (5 - a.priority) * p for p, a in zip(present, activities)))
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 1
    solver.parameters.num_search_workers = 1
    solver.parameters.random_seed = seed
    status = solver.solve(model)
    selected = [(solver.value(starts[i]), solver.value(starts[i]) + task.duration // 15) for i, task in enumerate(activities) if solver.value(present[i])] if status in [cp_model.FEASIBLE, cp_model.OPTIMAL] else []
    assert all(0 <= s < e <= 12 for s, e in selected)
    assert all(e <= other_s or other_e <= s for i, (s, e) in enumerate(selected) for other_s, other_e in selected[:i])
    return {'seed': seed, 'baselineScheduled': len(baseline.schedule), 'cpsatScheduled': len(selected),
            'status': solver.status_name(status), 'baselineConflicts': len(baseline.conflicts)}

if __name__ == '__main__':
    cases = [benchmark(seed) for seed in range(20)]
    print(json.dumps({'scope': 'Single day, 8 independent tasks, 3-hour availability, 15-minute grid. Coverage-first objective differs from production priority-first heuristic.',
        'decision': 'Keep production engine: benchmark excludes recurrence, completed history, dependencies, timezone, buffers, stability and multiuser integration.',
        'baselineTotalScheduled': sum(c['baselineScheduled'] for c in cases),
        'cpsatTotalScheduled': sum(c['cpsatScheduled'] for c in cases), 'cases': cases}, indent=2))
