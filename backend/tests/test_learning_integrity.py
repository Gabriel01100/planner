"""Permanent regression coverage for audit A-03 and the server side of M-01."""
from datetime import date, datetime
from uuid import uuid4

import pytest
from fastapi import HTTPException
from sqlalchemy.orm import Session
from app.main import create_app
from app.models import Activity, State, Slot, Preferences
from app.learning_models import LearningGoal, SessionRegistration, StudySession


@pytest.fixture
def study(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "integrity.db"), "test")
    goal = LearningGoal(title="Estudio")
    app.state.learning.create_goal("personal", goal)
    activity = Activity(title="Diaria", goalId=goal.id, frequency="Diaria", startDate=date(2027, 1, 4))
    state = State(activities=[activity], preferences=Preferences(timezone="America/Argentina/Buenos_Aires"), schedule=[
        Slot(activityId=activity.id, date=date(2027, 1, day), start=540, end=600) for day in (4, 5)
    ])
    with Session(app.state.agenda.engine) as db:
        app.state.agenda.save(db, "personal", 0, state, "test.setup")
        app.state.agenda.commit(db)
    return app, goal, activity


def command(goal, activity, **overrides):
    return SessionRegistration.model_validate(dict(
        requestId=uuid4(), goalId=goal.id, activityId=activity.id,
        occurrenceDate="2027-01-04", topic="Funciones", confirmed=True,
        startedAt="2027-01-04T23:30:00-03:00", completedAt="2027-01-05T00:30:00-03:00",
    ) | overrides)


@pytest.mark.parametrize("started,completed", [
    ("2027-01-04T09:00:00Z", "2027-01-04T10:00:00Z"),
    ("2027-01-04T23:30:00Z", "2027-01-05T00:30:00Z"),
    ("2027-01-04T23:30:00-03:00", "2027-01-05T00:30:00-03:00"),
    ("2027-01-04T09:00:00-03:00", "2027-01-05T08:00:00-03:00"),
    ("2027-01-04T09:00:00-03:00", "2027-01-09T08:00:00-03:00"),
])
def test_selected_occurrence_survives_midnight_delays_and_retries(study, started, completed):
    app, goal, activity = study
    original = command(goal, activity, startedAt=started, completedAt=completed)
    # Same JSON round trip used by queued commands after process death.
    restored = SessionRegistration.model_validate_json(original.model_dump_json())
    app.state.learning.register_progress("personal", restored)
    revision, state = app.state.agenda.read("personal")
    assert [(s.date.day, s.completed) for s in state.schedule] == [(4, True), (5, False)]
    assert state.activities[0].status == "Pendiente"
    memory = app.state.learning.read("personal")
    assert memory.memory.sessions[0].occurrenceDate == date(2027, 1, 4)
    assert memory.memory.sessions[0].completedAt == restored.completedAt
    result = app.state.learning.register_progress("personal", restored)
    assert result.duplicate
    assert app.state.agenda.read("personal")[0] == revision
    assert app.state.learning.read("personal") == memory


@pytest.mark.parametrize("overrides", [{"occurrenceDate": None}, {"occurrenceDate": "2027-01-06"}])
def test_ambiguous_or_missing_occurrence_preserves_both_documents(study, overrides):
    app, goal, activity = study
    before = (app.state.agenda.read("personal"), app.state.learning.read("personal"))
    with pytest.raises(HTTPException) as error:
        app.state.learning.register_progress("personal", command(goal, activity, **overrides))
    assert error.value.status_code == 409
    assert (app.state.agenda.read("personal"), app.state.learning.read("personal")) == before


def test_session_cannot_be_reassigned_to_another_occurrence(study):
    app, goal, activity = study
    original = command(goal, activity)
    result = app.state.learning.register_progress("personal", original)
    before = (app.state.agenda.read("personal"), app.state.learning.read("personal"))
    wrong = command(goal, activity, sessionId=result.entityId, occurrenceDate="2027-01-05")
    with pytest.raises(HTTPException) as error:
        app.state.learning.register_progress("personal", wrong)
    assert error.value.status_code == 409
    assert (app.state.agenda.read("personal"), app.state.learning.read("personal")) == before


def test_request_id_same_payload_retries_but_changed_payload_conflicts(study):
    app, goal, activity = study
    original = command(goal, activity, actualMinutes=30)
    app.state.learning.register_progress("personal", original)
    before = (app.state.agenda.read("personal"), app.state.learning.read("personal"))
    assert app.state.learning.register_progress("personal", original).duplicate
    for changes in ({"actualMinutes": 90}, {"topic": "Tema corregido"}, {"occurrenceDate": date(2027, 1, 5)}):
        with pytest.raises(HTTPException) as error:
            app.state.learning.register_progress("personal", original.model_copy(update=changes))
        assert error.value.status_code == 409
        assert (app.state.agenda.read("personal"), app.state.learning.read("personal")) == before
    result = app.state.learning.register_progress("personal", original.model_copy(update={"requestId": uuid4()}))
    assert not result.duplicate
    assert len(app.state.learning.read("personal").memory.sessions) == 2


def test_old_session_json_remains_readable_without_inventing_identity():
    session = StudySession.model_validate(dict(id=uuid4(), goalId=uuid4(), activityId=uuid4(), status="completed", completedAt="2027-01-05T12:00:00Z"))
    assert session.occurrenceDate is None


def test_one_off_session_uses_its_scheduled_date_even_if_postponed(study):
    app, goal, activity = study
    with Session(app.state.agenda.engine) as db:
        revision, state = app.state.agenda.read("personal")
        state.activities[0].frequency = "Única"
        state.schedule = state.schedule[1:]
        app.state.agenda.save(db, "personal", revision, state, "test.postpone")
        app.state.agenda.commit(db)
    app.state.learning.register_progress("personal", command(goal, activity, occurrenceDate=None))
    assert app.state.agenda.read("personal")[1].schedule[0].completed
    assert app.state.learning.read("personal").memory.sessions[0].occurrenceDate == date(2027, 1, 5)


def test_deduplicated_import_still_records_the_new_request_id(study):
    app, goal, activity = study
    original = command(goal, activity, sourceType="text", sourceContentDigest="a" * 64)
    first = app.state.learning.register_progress("personal", original)
    second_command = original.model_copy(update={"requestId": uuid4()})
    second = app.state.learning.register_progress("personal", second_command)
    assert second.duplicate and second.entityId == first.entityId
    before = (app.state.agenda.read("personal"), app.state.learning.read("personal"))
    assert len(before[1].memory.receipts) == 2
    assert app.state.learning.register_progress("personal", second_command).duplicate
    with pytest.raises(HTTPException) as error:
        app.state.learning.register_progress("personal", second_command.model_copy(update={"actualMinutes": 90}))
    assert error.value.status_code == 409
    assert (app.state.agenda.read("personal"), app.state.learning.read("personal")) == before


def test_receipt_fingerprint_matches_the_android_contract_golden(study):
    from uuid import UUID

    app, _, _ = study
    goal = LearningGoal(id=UUID("00000000-0000-4000-8000-000000000002"), title="Golden")
    app.state.learning.create_goal("personal", goal)
    payload = SessionRegistration.model_validate({
        "requestId": "00000000-0000-4000-8000-000000000001", "goalId": str(goal.id),
        "topic": " Funciones ", "completedAt": "2027-01-05T01:00:00.120000+00:00",
        "actualMinutes": 30, "understood": [" algo "], "confirmed": True,
        "notes": "", "sourceType": "manual",
        "assessment": {"correctCount": 1, "totalCount": 2, "method": " Práctica ", "confidence": 1},
    })
    app.state.learning.register_progress("personal", payload)
    receipt = app.state.learning.read("personal").memory.receipts[0]
    assert receipt.fingerprint == "d17ab08a09ff261362c6a92655e02748648713e6dceee5dadaa7592aaeb00be9"

    for confidence, expected in [
        (0.0001, "750c0701808e0a2214e44bddcf8bea70dc3e2758046fd5af4837a8882ce56bc6"),
        (-0.0, "77a1d94fd2bce1e201fc3daa8a068c7f6cf104cb0421758faf326beb3ee31337"),
        (5e-324, "a59a1a0192afb9f9a165d12577482c63dbfe244581ae62e82e1db63cb5408d73"),
    ]:
        variant = payload.model_copy(update={"requestId": uuid4(), "assessment": payload.assessment.model_copy(update={"confidence": confidence})})
        app.state.learning.register_progress("personal", variant)
        assert app.state.learning.read("personal").memory.receipts[-1].fingerprint == expected
