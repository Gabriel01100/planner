"""Identical requests, hard rules and scoring, including edge cases omitted in 1.1."""

import json
from datetime import date, datetime, timezone
from uuid import UUID
from pathlib import Path
from app.models import Activity, State, Window, Preferences, Slot, PlanRequest
from app.planner import plan
from app.optimizer_experiment import solve, metrics


def scenarios():
    day = date(2027, 1, 4)
    now = datetime(2027, 1, 1, tzinfo=timezone.utc)

    def activity(index, **kw):
        return Activity(id=UUID(int=index), title=f"Tarea {index}", startDate=day, **kw)

    a = activity(1, duration=30)
    b = activity(2, duration=60, dependencies=[a.id], priority=1)
    requests = [
        ("dependencias", State(activities=[b, a])),
        (
            "recurrencias",
            State(
                activities=[
                    activity(3, frequency="Diaria", days=[0, 2, 4]),
                    activity(4, frequency="Semanal", days=[1, 3]),
                ]
            ),
        ),
        (
            "bloqueos_buffers",
            State(
                activities=[activity(5, fixedStart=600), activity(6)],
                blocked=[Slot(activityId=UUID(int=99), date=day, start=480, end=540)],
                preferences=Preferences(transition=15),
            ),
        ),
        (
            "carga",
            State(
                activities=[
                    activity(i, duration=90, priority=(i % 4) + 1) for i in range(7, 12)
                ],
                preferences=Preferences(maxDailyMinutes=180),
            ),
        ),
        (
            "fechas_y_exclusiones",
            State(
                activities=[
                    activity(12, deadline=day, excluded=[Window(start=480, end=1000)]),
                    activity(13, skippedDates=[day], days=[1]),
                ]
            ),
        ),
        (
            "estabilidad",
            State(
                activities=[a],
                schedule=[Slot(activityId=a.id, date=day, start=600, end=630)],
            ),
        ),
        (
            "historial",
            State(
                activities=[a],
                schedule=[
                    Slot(activityId=a.id, date=day, start=300, end=330, completed=True)
                ],
            ),
        ),
    ]
    for name, state in requests:
        yield name, PlanRequest(state=state, start=day, days=5, now=now)
    march = date(2027, 3, 14)
    yield (
        "dst",
        PlanRequest(
            state=State(
                activities=[
                    Activity(
                        id=UUID(int=14), title="DST", startDate=march, fixedStart=150
                    )
                ],
                preferences=Preferences(timezone="America/New_York"),
                availability=[Window(start=0, end=1440)],
            ),
            start=march,
            days=1,
            now=now,
        ),
    )
    end = date(2027, 1, 31)
    yield (
        "fin_de_mes",
        PlanRequest(
            state=State(
                activities=[
                    Activity(
                        id=UUID(int=15),
                        title="Mensual",
                        startDate=end,
                        frequency="Mensual",
                    )
                ]
            ),
            start=date(2027, 2, 27),
            days=3,
            now=now,
        ),
    )


def compare():
    cases = []
    for name, request in scenarios():
        base = plan(request)
        candidate, status = solve(request, seconds=3)
        cases.append(
            {
                "name": name,
                "input": request.model_dump(mode="json"),
                "baseline": metrics(request, base),
                "cpsat": metrics(request, candidate),
                "solverStatus": status,
                "sameConstraints": True,
                "sameScoring": True,
            }
        )
    return {
        "decision": "Keep greedy in production; bounded sample does not demonstrate general equivalence or improvement. CP-SAT remains optional.",
        "criteria": "priorityScore*1000000 + scheduled*10000 - movedOrRemoved*100 - preferenceDeviationMinutes - peakDailyMinutes*10",
        "cases": cases,
    }


if __name__ == "__main__":
    report = compare()
    target = (
        Path(__file__).resolve().parents[2]
        / "docs/benchmarks/equivalent-evaluation.json"
    )
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {
                "cases": len(report["cases"]),
                "invalidBaseline": sum(
                    c["baseline"]["conflicts"] for c in report["cases"]
                ),
                "invalidCpsat": sum(c["cpsat"]["conflicts"] for c in report["cases"]),
                "decision": report["decision"],
            }
        )
    )
