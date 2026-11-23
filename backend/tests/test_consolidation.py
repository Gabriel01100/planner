from datetime import date, datetime, timezone
from uuid import uuid4
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from app.main import create_app
from app.models import Activity, State
from app.synchronization import merge_states
from app.learning_models import LearningGoal, SessionRegistration, AssessmentData
from app.security import Principal


def test_merge_independent_fields_additions_and_conflicting_delete():
    a = Activity(title="Original", startDate=date(2027, 1, 4))
    base = State(activities=[a])
    local, remote = base.model_copy(deep=True), base.model_copy(deep=True)
    local.activities[0].updatedAt = "2027-01-01T10:00:00Z"
    remote.activities[0].updatedAt = "2027-01-01T11:00:00Z"
    local.activities[0].title = "Nuevo título"
    remote.activities[0].description = "Descripción remota"
    result = merge_states(base, local, remote)
    assert result.activities[0].title == "Nuevo título"
    assert result.activities[0].description == "Descripción remota"
    with pytest.raises(HTTPException) as error:
        merge_states(base, State(), remote)
    assert error.value.status_code == 409
    extra = Activity(title="Otra", startDate=date(2027, 1, 4))
    remote.activities.append(extra)
    assert len(merge_states(base, local, remote).activities) == 2


def test_merge_api_retry_and_isolation(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "sync.db"), "test")
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer test"
    state = State(activities=[Activity(title="Local", startDate=date(2027, 1, 4))])
    command = {
        "requestId": str(uuid4()),
        "revision": 0,
        "base": State().model_dump(mode="json"),
        "state": state.model_dump(mode="json"),
    }
    first = client.post("/api/v1/sync/merge", json=command)
    assert first.status_code == 200, first.text
    assert client.post("/api/v1/sync/merge", json=command).json() == first.json()
    command["state"]["activities"][0]["title"] = "Diferente"
    assert client.post("/api/v1/sync/merge", json=command).status_code == 409
    token = app.state.access.issue("other", ["schedule:read", "schedule:write"], "test")
    client.headers["Authorization"] = "Bearer " + token
    assert client.get("/api/sync").json()["state"]["activities"] == []


def test_assessment_requires_results_and_is_idempotent(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "memory.db"), "test")
    service = app.state.learning
    goal = LearningGoal(title="Python")
    service.create_goal("personal", goal)
    command = SessionRegistration(
        requestId=uuid4(),
        goalId=goal.id,
        topic="Funciones",
        completedAt=datetime.now(timezone.utc),
        confirmed=True,
        assessment=AssessmentData(
            correctCount=3,
            totalCount=4,
            method="Cuatro ejercicios corregidos",
            confidence=0.6,
        ),
    )
    first = service.register_progress("personal", command)
    second = service.register_progress("personal", command)
    memory = service.read("personal").memory
    assert first.entityId == second.entityId and second.duplicate
    assert len(memory.competencies) == 1
    assert memory.competencies[0].estimatedLevel == 0.75
    assert memory.competencies[0].confidence == 0.6
    assert len(memory.competencies[0].evidenceIds) == 1
    with pytest.raises(ValueError):
        AssessmentData(correctCount=5, totalCount=4, method="test", confidence=0.5)


def test_link_double_possession_consumption_and_existing_data(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "link.db"), "test")
    client = TestClient(app)
    scopes = frozenset(["memory:read", "memory:write", "schedule:write"])
    current = [Principal("personal", scopes, "mobile", "legacy")]
    app.state.access.authenticate = lambda authorization: current[0]
    code = client.post("/api/v1/connections/link/start").json()["code"]
    # Administrative token cannot masquerade as OAuth.
    assert (
        client.post(
            "/api/v1/connections/link/claim", json={"code": code, "confirmed": True}
        ).status_code
        == 403
    )
    current[0] = Principal("oauth-user", scopes, "jwt", "oidc", "oauth-user")
    assert (
        client.post(
            "/api/v1/connections/link/claim", json={"code": code, "confirmed": False}
        ).status_code
        == 403
    )
    assert (
        client.post(
            "/api/v1/connections/link/claim", json={"code": code, "confirmed": True}
        ).json()["workspaceId"]
        == "personal"
    )
    assert (
        client.post(
            "/api/v1/connections/link/claim", json={"code": code, "confirmed": True}
        ).status_code
        == 409
    )
    current[0] = Principal("personal", scopes, "mobile", "legacy")
    code = client.post("/api/v1/connections/link/start").json()["code"]
    app.state.learning.create_goal("populated", LearningGoal(title="No reemplazar"))
    current[0] = Principal("populated", scopes, "jwt2", "oidc", "populated")
    assert (
        client.post(
            "/api/v1/connections/link/claim", json={"code": code, "confirmed": True}
        ).status_code
        == 409
    )


def test_bounded_autonomy_opt_in_small_move_undo_and_limits(tmp_path):
    from app.models import Slot, Window
    from app.learning_models import LearningPreferences
    from app.autonomy import automatic_plan
    from sqlalchemy.orm import Session

    app = create_app("sqlite:///" + str(tmp_path / "auto.db"), "test")
    agenda = app.state.agenda
    day = date(2027, 1, 4)
    a = Activity(title="Estudiar", startDate=day, preferredStart=570)
    old = Slot(activityId=a.id, date=day, start=540, end=600)
    state = State(
        activities=[a], schedule=[old], availability=[Window(start=570, end=1200)]
    )
    with Session(agenda.engine) as db:
        agenda.save(db, "personal", 0, state, "test")
        agenda.commit(db)
    now = datetime(2027, 1, 4, 8, tzinfo=timezone.utc)
    assert not automatic_plan(agenda, "personal", now=now)["applied"]
    memory = app.state.learning.read("personal")
    memory.memory.preferences = LearningPreferences(
        automaticEnabled=True,
        autonomy="bounded",
        maxShiftMinutes=30,
        maxAutomaticMoves=1,
    )
    app.state.learning.replace("personal", memory.memory, 0, True)
    result = automatic_plan(agenda, "personal", now=now)
    assert result["applied"], result
    assert agenda.read("personal")[1].schedule[0].start == 570
    agenda.undo("personal", result["proposalId"], True)
    assert agenda.read("personal")[1].schedule[0].start == 540
    assert not automatic_plan(agenda, "personal", now=now)[
        "applied"
    ]  # rolling seven-day budget
    assert not automatic_plan(agenda, "other", now=now)["applied"]


def test_session_completion_updates_agenda_and_memory_transactionally(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "workflow.db"), "test")
    goal = LearningGoal(title="Funciones")
    app.state.learning.create_goal("personal", goal)
    activity = Activity(title="Practicar", goalId=goal.id, startDate=date(2027, 1, 4))
    app.state.agenda.create_task("personal", activity)
    command = SessionRegistration(
        requestId=uuid4(),
        goalId=goal.id,
        topic="Funciones",
        activityId=activity.id,
        completedAt=datetime(2027, 1, 4, 15, tzinfo=timezone.utc),
        confirmed=True,
    )
    app.state.learning.register_progress("personal", command)
    revision, state = app.state.agenda.read("personal")
    assert state.activities[0].status == "Completada"
    app.state.learning.register_progress("personal", command)
    assert app.state.agenda.read("personal")[0] == revision
    invalid = command.model_copy(update={"requestId": uuid4(), "activityId": uuid4()})
    before = app.state.learning.read("personal")
    with pytest.raises(HTTPException):
        app.state.learning.register_progress("personal", invalid)
    assert app.state.learning.read("personal") == before


def test_memory_writer_cannot_enable_autonomy_without_preferences_scope(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "scope.db"), "test")
    client = TestClient(app)
    token = app.state.access.issue(
        "limited", ["memory:read", "memory:write"], "limited"
    )
    client.headers["Authorization"] = "Bearer " + token
    data = client.get("/api/v1/learning").json()
    data["memory"]["preferences"]["automaticEnabled"] = True
    assert (
        client.put("/api/v1/learning", json={**data, "confirmed": True}).status_code
        == 403
    )
    assert (
        client.get("/api/v1/learning").json()["memory"]["preferences"][
            "automaticEnabled"
        ]
        is False
    )


def test_readiness_and_response_trace_do_not_expose_secrets(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "ready.db"), "private-test-token")
    client = TestClient(app)
    response = client.get("/ready")
    assert response.status_code == 200 and response.json()["schemaVersion"] == 5
    assert response.headers["X-Request-ID"]
    assert "private-test-token" not in response.text


def test_link_page_and_public_pkce_configuration(tmp_path, monkeypatch):
    app = create_app("sqlite:///" + str(tmp_path / "web.db"), "test")
    client = TestClient(app)
    page = client.get("/connect?code=private-callback-code")
    assert page.status_code == 200 and page.headers["Cache-Control"] == "no-store"
    assert "private-callback-code" not in page.text
    assert client.get("/account-link.js").status_code == 200
    assert client.get("/api/v1/connections/link/config").status_code == 503
    monkeypatch.setenv("OAUTH_ISSUER", "https://provider.test/")
    monkeypatch.setenv("OAUTH_RESOURCE_URL", "https://app.test/mcp")
    monkeypatch.setenv("OIDC_LINK_CLIENT_ID", "public-client")
    config = client.get("/api/v1/connections/link/config").json()
    assert config["issuer"] == "https://provider.test/"
    assert config["redirectUri"] == "https://app.test/connect"
    assert "secret" not in str(config)


def test_legacy_sync_cannot_orphan_restored_dependencies(tmp_path):
    app = create_app("sqlite:///" + str(tmp_path / "legacy-dependencies.db"), "test")
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer test"
    a = Activity(title="Primera", startDate=date(2027, 1, 4))
    b = Activity(title="Segunda", startDate=date(2027, 1, 4), dependencies=[a.id])
    state = State(activities=[a, b]).model_dump(mode="json")
    assert (
        client.put("/api/sync", json={"revision": 0, "state": state}).status_code == 200
    )
    state["activities"] = state["activities"][1:]
    state["activities"][0].pop("dependencies")
    assert (
        client.put("/api/sync", json={"revision": 1, "state": state}).status_code == 422
    )
    assert client.get("/api/sync").json()["revision"] == 1
