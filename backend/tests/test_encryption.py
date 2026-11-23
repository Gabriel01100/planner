import pytest
from cryptography.fernet import Fernet
from sqlalchemy import create_engine, text
from sqlalchemy.orm import Session
from app.database import migrate, Document, LearningDocument
from app.encryption import PREFIX


def test_additive_migration_encrypts_existing_rows_and_preserves_revision(tmp_path, monkeypatch):
    engine = create_engine('sqlite:///' + str(tmp_path / 'legacy.db'))
    value = '{"private":"contenido personal de prueba"}'
    with engine.begin() as c:
        c.execute(text('CREATE TABLE documents (owner VARCHAR(64) PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL)'))
        c.execute(text('INSERT INTO documents VALUES (:owner, 9, :payload)'), {'owner': 'personal', 'payload': value})
    key = Fernet.generate_key().decode()
    monkeypatch.setenv('CALENFLEX_DATA_KEY', key)
    migrate(engine)
    migrate(engine)
    with engine.connect() as c:
        raw = c.execute(text('SELECT payload FROM documents')).scalar_one()
        assert raw.startswith(PREFIX) and 'contenido personal' not in raw
    with Session(engine) as db:
        row = db.get(Document, 'personal')
        assert row.payload == value and row.revision == 9
        db.add(LearningDocument(owner='personal', revision=1, payload='{"goals":[]}'))
        db.commit()
    with engine.connect() as c:
        assert c.execute(text('SELECT payload FROM learning_documents')).scalar_one().startswith(PREFIX)
    monkeypatch.setenv('CALENFLEX_DATA_KEY', Fernet.generate_key().decode())
    with pytest.raises(RuntimeError, match='clave de cifrado'):
        migrate(engine)
    monkeypatch.setenv('CALENFLEX_DATA_KEY', key)
    with Session(engine) as db:
        assert db.get(Document, 'personal').payload == value


def test_production_requires_real_at_rest_key(tmp_path, monkeypatch):
    monkeypatch.setenv('CALENFLEX_ENV', 'production')
    monkeypatch.delenv('CALENFLEX_DATA_KEY', raising=False)
    with pytest.raises(RuntimeError, match='CALENFLEX_DATA_KEY'):
        migrate(create_engine('sqlite:///' + str(tmp_path / 'production.db')))
