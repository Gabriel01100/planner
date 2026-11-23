"""Run explicitly against a disposable PostgreSQL service (never a production URL)."""

import os
from uuid import uuid4
from datetime import date
from concurrent.futures import ThreadPoolExecutor
import pytest
from sqlalchemy import create_engine, text
from fastapi import HTTPException
from fastapi.testclient import TestClient
from app.database import migrate, Document, SchemaVersion
from app.main import create_app
from app.models import State, Activity
from app.learning_models import LearningGoal
from sqlalchemy.orm import Session


@pytest.fixture
def database():
    url = os.environ["TEST_POSTGRES_URL"]
    schema = "cf_test_" + uuid4().hex
    root = create_engine(url)
    with root.begin() as connection:
        connection.exec_driver_sql("CREATE SCHEMA " + schema)
    isolated = url + ("&" if "?" in url else "?") + "options=-csearch_path%3D" + schema
    try:
        yield isolated
    finally:
        with root.begin() as connection:
            connection.exec_driver_sql("DROP SCHEMA " + schema + " CASCADE")
        root.dispose()


def test_postgres_legacy_migration_encryption_and_isolation(database, monkeypatch):
    engine = create_engine(database)
    original = State(
        activities=[Activity(title="Conservar", startDate=date(2027, 1, 4))]
    ).model_dump_json()
    with engine.begin() as connection:
        connection.exec_driver_sql(
            "CREATE TABLE documents (owner VARCHAR(64) PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL)"
        )
        connection.execute(
            text("INSERT INTO documents VALUES (:owner,7,:payload)"),
            {"owner": "personal", "payload": original},
        )
    from cryptography.fernet import Fernet

    monkeypatch.setenv("CALENFLEX_DATA_KEY", Fernet.generate_key().decode())
    migrate(engine)
    migrate(engine)
    with Session(engine) as db:
        assert db.get(Document, "personal").payload == original
        assert db.get(Document, "personal").revision == 7
        assert len(db.query(SchemaVersion).all()) == 5
    with engine.connect() as connection:
        assert (
            connection.exec_driver_sql("SELECT payload FROM documents")
            .scalar()
            .startswith("enc:v1:")
        )
    app = create_app(database, "test")
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer test"
    assert client.get("/api/sync").json()["revision"] == 7
    other = app.state.access.issue(
        "other", ["schedule:read", "memory:read", "memory:write"], "test"
    )
    client.headers["Authorization"] = "Bearer " + other
    assert client.get("/api/sync").json()["state"]["activities"] == []
    goal = LearningGoal(title="Otra cuenta")
    assert (
        client.post(
            "/api/v1/learning/goals", json=goal.model_dump(mode="json")
        ).status_code
        == 200
    )
    client.headers["Authorization"] = "Bearer test"
    assert client.get("/api/v1/learning").json()["memory"]["goals"] == []
    app.state.engine.dispose()
    engine.dispose()


def test_postgres_concurrent_revision_conflict_does_not_overwrite(database):
    app = create_app(database, "test")
    agenda = app.state.agenda
    agenda.create_task("personal", Activity(title="Base", startDate=date(2027, 1, 4)))
    revision, state = agenda.read("personal")

    def write(title):
        changed = state.model_copy(deep=True)
        changed.activities[0].title = title
        with Session(agenda.engine) as db:
            try:
                result = agenda.save(db, "personal", revision, changed, "test")
                agenda.commit(db)
                return result
            except HTTPException as error:
                db.rollback()
                return error.status_code

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(write, ["A", "B"]))
    assert sorted(results) == [2, 409]
    assert agenda.read("personal")[0] == 2
    app.state.engine.dispose()


def test_postgres_sync_receipt_replay_and_roundtrip(database):
    app = create_app(database, "test")
    client = TestClient(app)
    client.headers["Authorization"] = "Bearer test"
    state = State(
        activities=[Activity(title="Sincronizada", startDate=date(2027, 1, 4))]
    )
    command = {
        "requestId": str(uuid4()),
        "revision": 0,
        "base": State().model_dump(mode="json"),
        "state": state.model_dump(mode="json"),
    }
    first = client.post("/api/v1/sync/merge", json=command)
    assert first.status_code == 200, first.text
    assert client.post("/api/v1/sync/merge", json=command).json() == first.json()
    engine = app.state.engine
    engine.dispose()
    reopened = create_app(database, "test")
    again = TestClient(reopened)
    again.headers["Authorization"] = "Bearer test"
    assert (
        again.get("/api/sync").json()["state"]["activities"][0]["title"]
        == "Sincronizada"
    )
    reopened.state.engine.dispose()


def test_offline_transfer_to_postgres_preserves_revisions_and_refuses_nonempty(
    database, tmp_path, monkeypatch
):
    from app.transfer import transfer

    source = "sqlite:///" + str(tmp_path / "source.db")
    app = create_app(source, "test")
    app.state.learning.create_goal(
        "personal", LearningGoal(title="Transferir sin duplicar")
    )
    app.state.agenda.create_task(
        "personal", Activity(title="Conservar revisión", startDate=date(2027, 1, 4))
    )
    before = app.state.agenda.read("personal")
    assert transfer(source, database, False)["documents"] == 1
    transfer(source, database, True)
    destination = create_app(database, "test")
    assert destination.state.agenda.read("personal") == before
    assert (
        destination.state.learning.read("personal").memory.goals[0].title
        == "Transferir sin duplicar"
    )
    from cryptography.fernet import Fernet

    monkeypatch.setenv("CALENFLEX_DATA_KEY", Fernet.generate_key().decode())
    with pytest.raises(ValueError):
        transfer(source, database, True)
    with destination.state.engine.connect() as connection:
        assert (
            not connection.exec_driver_sql("SELECT payload FROM documents")
            .scalar()
            .startswith("enc:v1:")
        )
    assert app.state.agenda.read("personal") == before
    destination.state.engine.dispose()
    app.state.engine.dispose()
