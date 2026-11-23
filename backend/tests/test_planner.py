from datetime import date, datetime, timedelta, timezone
from random import Random
from uuid import uuid4
import pytest
from app.models import Activity, PlanRequest, State, Slot, Window, Preferences
from app.planner import occurs, plan, validate

START = date(2027, 1, 4)
NOW = datetime(2027, 1, 1, tzinfo=timezone.utc)

def activity(**kw):
    return Activity(**{'title': 'Estudiar', 'startDate': START, **kw})

def run(state, days=7):
    return plan(PlanRequest(state=state, start=START, days=days, now=NOW))

@pytest.mark.parametrize('seed', range(40))
def test_random_schedule_invariants(seed):
    random = Random(seed)
    activities = [activity(id=uuid4(), title=f'Actividad {i}', duration=random.choice([30, 60, 90, 120]),
                           priority=random.randint(1, 4), frequency=random.choice(['Única', 'Diaria']),
                           deadline=START + timedelta(days=6)) for i in range(15)]
    state = State(activities=activities, availability=[Window(start=540, end=1020)])
    before = state.model_dump_json()
    result = run(state)
    assert state.model_dump_json() == before
    assert result == run(state)
    assert not validate(state, result.schedule)
    # Independent checks: pairwise occupancy and actual available bounds.
    for i, s in enumerate(result.schedule):
        assert 540 <= s.start < s.end <= 1020
        assert s.end - s.start == next(a.duration for a in activities if a.id == s.activityId)
        assert all(o.date != s.date or s.end <= o.start or o.end <= s.start for o in result.schedule[:i])


def test_completed_keeps_history_after_availability_changes():
    a = activity(status='Completada')
    slot = Slot(activityId=a.id, date=START, start=300, end=360, completed=True)
    result = run(State(activities=[a], schedule=[slot]))
    assert result.schedule == [slot]


def test_completed_occurrence_not_entire_series():
    a = activity(frequency='Diaria')
    slot = Slot(activityId=a.id, date=START, start=540, end=600, completed=True)
    result = run(State(activities=[a], schedule=[slot]), 2)
    assert len(result.schedule) == 2
    assert slot in result.schedule


def test_priority_and_impossible_duration():
    low = activity(priority=4)
    high = activity(priority=1)
    result = run(State(activities=[low, high], availability=[Window(start=540, end=600)]), 1)
    assert result.schedule[0].activityId == high.id
    assert result.pending[0].activityId == low.id


def test_fixed_blocks_and_transition():
    a = activity(fixedStart=540)
    b = activity(preferredStart=600)
    result = run(State(activities=[a, b], preferences=Preferences(transition=15)), 1)
    assert next(s for s in result.schedule if s.activityId == b.id).start == 615


def test_excluded_and_skip():
    a = activity(frequency='Diaria', skippedDates=[START], excluded=[Window(start=480, end=660)])
    result = run(State(activities=[a]), 2)
    assert len(result.schedule) == 1
    assert result.schedule[0].date == START + timedelta(days=1)
    assert result.schedule[0].start >= 660

@pytest.mark.parametrize('day,expected', [('2027-02-28', True), ('2028-02-29', True), ('2027-03-30', False)])
def test_month_end(day, expected):
    a = activity(startDate=date(2027, 1, 31), frequency='Mensual')
    assert occurs(a, date.fromisoformat(day)) == expected


def test_duplicate_rejected():
    a = activity()
    with pytest.raises(ValueError):
        State(activities=[a, a])


def test_invalid_window():
    with pytest.raises(ValueError):
        Window(start=600, end=540)


def test_reorganization_keeps_slots_outside_horizon():
    a = activity()
    old = Slot(activityId=a.id, date=START - timedelta(days=1), start=540, end=600)
    assert old in run(State(activities=[a], schedule=[old])).schedule


def test_timezone_and_anticipation():
    a = activity(leadMinutes=60)
    req = PlanRequest(state=State(activities=[a]), start=START, days=1,
                      now=datetime(2027, 1, 4, 12, tzinfo=timezone.utc))
    assert plan(req).schedule[0].start >= 600


def test_blocked_period_is_never_used():
    a = activity()
    block = Slot(activityId=uuid4(), date=START, start=480, end=720)
    result = run(State(activities=[a], blocked=[block]), 1)
    assert result.schedule[0].start >= 720


def test_cancelled_never_scheduled():
    assert run(State(activities=[activity(status='Cancelada')])).schedule == []


def test_custom_interval_and_weekday():
    a = activity(frequency='Personalizada', interval=2)
    result = run(State(activities=[a]))
    assert [s.date for s in result.schedule] == [START + timedelta(days=d) for d in [0, 2, 4, 6]]


def test_weekly_days_and_until():
    a = activity(frequency='Semanal', days=[0, 2], until=START + timedelta(days=8))
    result = run(State(activities=[a]), 14)
    assert [s.date for s in result.schedule] == [START, START + timedelta(days=2), START + timedelta(days=7)]


def test_fixed_conflict_returns_pending():
    first = activity(fixedStart=540, priority=1)
    second = activity(fixedStart=540, priority=2)
    result = run(State(activities=[first, second]), 1)
    assert len(result.schedule) == 1
    assert result.pending[0].activityId == second.id


def test_validate_catches_wrong_duration_and_outside_availability():
    a = activity()
    slot = Slot(activityId=a.id, date=START, start=100, end=110)
    errors = validate(State(activities=[a]), [slot])
    assert any('Duración' in e for e in errors)
    assert any('disponibilidad' in e for e in errors)


def test_fixed_unique_is_not_moved_to_another_day():
    a = activity(fixedStart=540, deadline=START + timedelta(days=6))
    block = Slot(activityId=uuid4(), date=START, start=480, end=720)
    result = run(State(activities=[a], blocked=[block]))
    assert not result.schedule
    assert result.pending[0].activityId == a.id


def test_expired_unique_has_pending_reason():
    a = activity(startDate=START - timedelta(days=3), deadline=START - timedelta(days=1))
    result = run(State(activities=[a]))
    assert not result.schedule
    assert 'Fecha límite' in result.pending[0].reason


def test_unique_validator_rejects_disallowed_weekday_and_skip():
    a = activity(days=[1], deadline=START + timedelta(days=2))
    invalid = Slot(activityId=a.id, date=START, start=540, end=600)
    assert any('días permitidos' in e for e in validate(State(activities=[a]), [invalid]))
    result = run(State(activities=[a]), 3)
    assert result.schedule[0].date.weekday() == 1
    assert not result.conflicts
    skipped = a.model_copy(update={'skippedDates': [START + timedelta(days=1)]})
    assert validate(State(activities=[skipped]), result.schedule)


def test_completed_collision_checked_independent_of_list_order():
    a, b = activity(), activity()
    active = Slot(activityId=a.id, date=START, start=540, end=600)
    history = Slot(activityId=b.id, date=START, start=550, end=610, completed=True)
    state = State(activities=[a, b])
    assert validate(state, [history, active]) == validate(state, [active, history])
    assert any('Superposición' in e for e in validate(state, [active, history]))


def test_dependencies_and_daily_load_are_shared_rules():
    first = activity(priority=4, duration=30)
    second = activity(priority=1, duration=30, dependencies=[first.id])
    state = State(activities=[second, first], preferences=Preferences(maxDailyMinutes=60))
    result = run(state, 1)
    assert len(result.schedule) == 2 and not result.conflicts
    before = next(s for s in result.schedule if s.activityId == first.id)
    after = next(s for s in result.schedule if s.activityId == second.id)
    assert before.end <= after.start
    overloaded = state.model_copy(update={'preferences': Preferences(maxDailyMinutes=30)})
    assert len(run(overloaded, 1).schedule) == 1
    assert validate(overloaded, result.schedule)
    assert validate(state, [after])


def test_dst_missing_wall_times_and_elapsed_duration():
    day = date(2027, 3, 14)
    a = activity(startDate=day, duration=60, fixedStart=150, windows=[Window(start=0, end=1440)])
    state = State(activities=[a], availability=[Window(start=0, end=1440)], preferences=Preferences(timezone='America/New_York'))
    result = plan(PlanRequest(state=state, start=day, days=1, now=NOW))
    assert not result.schedule and result.pending
    assert validate(state, [Slot(activityId=a.id, date=day, start=150, end=210)])


def test_monthly_weekday_restriction_and_cancelled_existing_slot():
    a = activity(frequency='Mensual', days=[1])
    assert not run(State(activities=[a])).schedule
    assert validate(State(activities=[a]), [Slot(activityId=a.id, date=START, start=540, end=600)])
    cancelled = activity(status='Cancelada')
    slot = Slot(activityId=cancelled.id, date=START, start=540, end=600)
    assert validate(State(activities=[cancelled]), [slot])
    assert not run(State(activities=[cancelled], schedule=[slot])).schedule
