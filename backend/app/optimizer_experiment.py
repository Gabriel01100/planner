"""Optional CP-SAT, same request, hard rules and published scoring for both engines.
Not selected by production. Bounded size and time; results independently validated.
"""

from datetime import timedelta, datetime
from zoneinfo import ZoneInfo
from .models import Slot, PlanResult, Pending
from .planner import planning_jobs, validate
from .planning_rules import slot_errors


def metrics(request, result):
    activities = {a.id: a for a in request.state.activities}
    live = [
        s
        for s in result.schedule
        if not s.completed
        and activities[s.activityId].status != "Completada"
        and request.start <= s.date < request.start + timedelta(days=request.days)
    ]
    priority = sum(5 - activities[s.activityId].priority for s in live)
    old = {
        (s.activityId, s.date, s.start, s.end)
        for s in request.state.schedule
        if not s.completed
        and request.start <= s.date < request.start + timedelta(days=request.days)
    }
    new = {(s.activityId, s.date, s.start, s.end) for s in result.schedule}
    moves = len(old - new)
    deviation = sum(
        abs(s.start - activities[s.activityId].preferredStart) for s in live
    )
    peak = max(
        [
            sum(
                s.end - s.start
                for s in live
                if s.date == request.start + timedelta(days=offset)
            )
            for offset in range(request.days)
        ]
        or [0]
    )
    return {
        "conflicts": len(validate(request.state, result.schedule)),
        "scheduled": len(live),
        "priorityScore": priority,
        "movedOrRemoved": moves,
        "preferenceDeviationMinutes": deviation,
        "peakDailyMinutes": peak,
        "score": priority * 1_000_000
        + len(live) * 10_000
        - moves * 100
        - deviation
        - peak * 10,
    }


def solve(request, seconds=2):
    from ortools.sat.python import cp_model

    state = request.state
    retained, jobs, pending = planning_jobs(request)
    if len(jobs) > 100 or request.days > 31:
        raise ValueError("Experimento limitado a 100 ocurrencias y 31 días")
    model = cp_model.CpModel()
    now = request.now or datetime.now(ZoneInfo(state.preferences.timezone))
    if now.tzinfo is None:
        now = now.replace(tzinfo=ZoneInfo(state.preferences.timezone))
    items, intervals, daily, objective = (
        [],
        [],
        {i: [] for i in range(request.days)},
        [],
    )
    oldslots = state.schedule
    for a, first, last in jobs:
        if a.frequency == "Única" and any(s.activityId == a.id for s in retained):
            continue
        starts = []
        for offset in range((last - first).days + 1):
            day = first + timedelta(days=offset)
            for start in (
                [a.fixedStart]
                if a.fixedStart is not None
                else range(0, 1440 - a.duration + 1, 5)
            ):
                if start + a.duration > 1440:
                    continue
                slot = Slot(
                    activityId=a.id, date=day, start=start, end=start + a.duration
                )
                if not slot_errors(
                    state, a, slot, retained, now=now, check_dependencies=False
                ):
                    starts.append((day - request.start).days * 1440 + start)
        if not starts:
            pending.append(
                Pending(
                    activityId=a.id,
                    date=first,
                    reason="Sin candidatos que cumplan las restricciones comunes",
                )
            )
            continue
        number = len(items)
        selected = model.NewBoolVar(f"on{number}")
        start = model.NewIntVarFromDomain(
            cp_model.Domain.FromValues(starts), f"s{number}"
        )
        dayvar = model.NewIntVar(0, request.days - 1, f"d{number}")
        model.AddDivisionEquality(dayvar, start, 1440)
        end = model.NewIntVar(0, request.days * 1440, f"e{number}")
        model.AddMinEquality(
            end,
            [start + a.duration + state.preferences.transition, (dayvar + 1) * 1440],
        )
        size = model.NewIntVar(
            a.duration, a.duration + state.preferences.transition, f"z{number}"
        )
        model.Add(size == end - start)
        intervals.append(
            model.NewOptionalIntervalVar(start, size, end, selected, f"i{number}")
        )
        local = model.NewIntVar(0, 1439, f"local{number}")
        model.Add(local == start - dayvar * 1440)
        deviation = model.NewIntVar(0, 1440, f"dev{number}")
        model.AddAbsEquality(deviation, local - a.preferredStart)
        paiddev = model.NewIntVar(0, 1440, f"pdev{number}")
        model.Add(paiddev == deviation).OnlyEnforceIf(selected)
        model.Add(paiddev == 0).OnlyEnforceIf(selected.Not())
        objective.extend([selected * ((5 - a.priority) * 1_000_000 + 10_000), -paiddev])
        for offset in set(v // 1440 for v in starts):
            onday = model.NewBoolVar(f"{number}day{offset}")
            model.Add(dayvar == offset).OnlyEnforceIf(onday)
            model.AddImplication(onday, selected)
            daily[offset].append((onday, a.duration))
        model.Add(
            sum(
                pair[0]
                for pairs in daily.values()
                for pair in pairs
                if pair[0].Name().startswith(f"{number}day")
            )
            == selected
        )
        previous = next(
            (
                s
                for s in oldslots
                if s.activityId == a.id and (a.frequency == "Única" or s.date == first)
            ),
            None,
        )
        if previous:
            unchanged = model.NewBoolVar(f"keep{number}")
            model.AddImplication(unchanged, selected)
            model.Add(
                start == (previous.date - request.start).days * 1440 + previous.start
            ).OnlyEnforceIf(unchanged)
            objective.append(unchanged * 100)
        items.append((a, first, last, selected, start))
    model.AddNoOverlap(intervals)
    peak = model.NewIntVar(0, 1440, f"peak")
    for day, pairs in daily.items():
        retained_load = sum(
            s.end - s.start
            for s in retained
            if s.date == request.start + timedelta(days=day)
        )
        load = sum(flag * duration for flag, duration in pairs)
        model.Add(load <= max(0, state.preferences.maxDailyMinutes - retained_load))
        # Same published peak criterion excludes completed historical sessions.
        model.Add(peak >= load)
    objective.append(-peak * 10)
    by_id = {a.id: a for a in state.activities}
    for a, first, last, selected, start in items:
        for dependency in a.dependencies:
            if by_id[dependency].status == "Completada":
                continue
            available = []
            for prev in retained:
                if prev.activityId == dependency:
                    eligible = model.NewBoolVar("retained-predecessor")
                    model.Add(
                        start >= (prev.date - request.start).days * 1440 + prev.end
                    ).OnlyEnforceIf(eligible)
                    available.append(eligible)
            for predecessor, _, _, chosen, otherstart in items:
                if predecessor.id == dependency:
                    eligible = model.NewBoolVar("predecessor")
                    model.AddImplication(eligible, chosen)
                    model.Add(otherstart + predecessor.duration <= start).OnlyEnforceIf(
                        eligible
                    )
                    available.append(eligible)
            if available:
                model.AddBoolOr(available).OnlyEnforceIf(selected)
            else:
                model.Add(selected == 0)
    model.Maximize(sum(objective))
    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = seconds
    solver.parameters.num_search_workers = 1
    solver.parameters.random_seed = 0
    status = solver.Solve(model)
    name = solver.StatusName(status)
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return PlanResult(
            schedule=retained,
            pending=pending
            + [
                Pending(
                    activityId=a.id,
                    date=first,
                    reason="El solver no obtuvo solución factible dentro del límite",
                )
                for a, first, _, _, _ in items
            ],
            conflicts=validate(state, retained),
            changes=[],
        ), name
    result = retained[:]
    for a, first, last, selected, start in items:
        if solver.Value(selected):
            value = solver.Value(start)
            result.append(
                Slot(
                    activityId=a.id,
                    date=request.start + timedelta(days=value // 1440),
                    start=value % 1440,
                    end=value % 1440 + a.duration,
                )
            )
        else:
            pending.append(
                Pending(
                    activityId=a.id,
                    date=first,
                    reason="No seleccionada dentro de las restricciones y del objetivo comparado",
                )
            )
    result.sort(key=lambda s: (s.date, s.start, str(s.activityId)))
    return PlanResult(
        schedule=result,
        pending=pending,
        conflicts=validate(state, result),
        changes=["Experimento CP-SAT; sin aplicación automática"],
    ), name
