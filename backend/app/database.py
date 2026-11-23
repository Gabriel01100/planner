"""Additive SQL migrations; the legacy agenda table and its rows are retained."""
from datetime import datetime, timezone
from sqlalchemy import Column, Integer, String, Text, create_engine, inspect, select
from sqlalchemy.orm import DeclarativeBase, Session

class Base(DeclarativeBase):
    pass

class Document(Base):
    __tablename__ = 'documents'
    owner = Column(String(64), primary_key=True)
    revision = Column(Integer, nullable=False, default=0)
    payload = Column(Text, nullable=False)

class LearningDocument(Base):
    __tablename__ = 'learning_documents'
    owner = Column(String(64), primary_key=True)
    revision = Column(Integer, nullable=False, default=0)
    payload = Column(Text, nullable=False)

class Credential(Base):
    __tablename__ = 'credentials'
    digest = Column(String(64), primary_key=True)
    owner = Column(String(64), nullable=False, index=True)
    scopes = Column(Text, nullable=False)
    label = Column(String(100), nullable=False)
    revoked = Column(Integer, nullable=False, default=0)

class RevokedToken(Base):
    __tablename__ = 'revoked_tokens'
    digest = Column(String(64), primary_key=True)

class AuditEntry(Base):
    __tablename__ = 'audit_entries'
    id = Column(String(36), primary_key=True)
    owner = Column(String(64), nullable=False, index=True)
    action = Column(String(100), nullable=False)
    at = Column(String(40), nullable=False)
    # No imported text, private notes, URLs, prompts or credentials in audit metadata.
    entity_id = Column(String(100), nullable=True)

class SchemaVersion(Base):
    __tablename__ = 'schema_versions'
    version = Column(Integer, primary_key=True)
    applied_at = Column(String(40), nullable=False)


def migrate(engine):
    # Refuse an incompatible legacy table rather than silently replacing it.
    inspector = inspect(engine)
    if inspector.has_table('documents'):
        actual = {c['name'] for c in inspector.get_columns('documents')}
        if not {'owner', 'revision', 'payload'} <= actual:
            raise RuntimeError('Esquema de agenda incompatible; restaurá una copia antes de migrar')
    if inspector.has_table('schema_versions'):
        with Session(engine) as db:
            versions = db.scalars(select(SchemaVersion.version)).all()
            if versions and max(versions) > 2:
                raise RuntimeError('Esta base pertenece a una versión más nueva')
    with engine.begin() as connection:
        Base.metadata.create_all(connection)
    with Session(engine) as db:
        for version in (1, 2):
            if db.get(SchemaVersion, version) is None:
                db.add(SchemaVersion(version=version, applied_at=datetime.now(timezone.utc).isoformat()))
        db.commit()


def make_engine(url):
    return create_engine(url, connect_args={'check_same_thread': False} if url.startswith('sqlite') else {})
