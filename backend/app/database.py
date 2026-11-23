"""Additive SQL migrations; the legacy agenda table and its rows are retained."""
from datetime import datetime, timezone
from sqlalchemy import Column, Integer, String, Text, create_engine, inspect, select
from sqlalchemy.orm import DeclarativeBase, Session
from .encryption import ProtectedText, cipher, seal, unseal, PREFIX

class Base(DeclarativeBase):
    pass

class Document(Base):
    __tablename__ = 'documents'
    owner = Column(String(64), primary_key=True)
    revision = Column(Integer, nullable=False, default=0)
    payload = Column(ProtectedText(), nullable=False)

class LearningDocument(Base):
    __tablename__ = 'learning_documents'
    owner = Column(String(64), primary_key=True)
    revision = Column(Integer, nullable=False, default=0)
    payload = Column(ProtectedText(), nullable=False)

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

class StoredProposal(Base):
    __tablename__ = 'plan_proposals'
    id = Column(String(36), primary_key=True)
    owner = Column(String(64), nullable=False, index=True)
    base_revision = Column(Integer, nullable=False)
    payload = Column(ProtectedText(), nullable=False)
    previous = Column(ProtectedText(), nullable=False)
    expires_at = Column(String(40), nullable=False)
    applied_revision = Column(Integer, nullable=True)
    status = Column(String(20), nullable=False, default='pending')

class SyncReceipt(Base):
    __tablename__ = 'sync_receipts'
    owner = Column(String(64), primary_key=True)
    request_id = Column(String(36), primary_key=True)
    fingerprint = Column(String(64), nullable=False)
    payload = Column(ProtectedText(), nullable=False)

class AccountLink(Base):
    __tablename__ = 'account_links'
    identity = Column(String(64), primary_key=True)
    owner = Column(String(64), nullable=False, index=True)

class LinkChallenge(Base):
    __tablename__ = 'link_challenges'
    digest = Column(String(64), primary_key=True)
    owner = Column(String(64), nullable=False)
    expires_at = Column(String(40), nullable=False)
    used = Column(Integer, nullable=False, default=0)

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
            if versions and max(versions) > 5:
                raise RuntimeError('Esta base pertenece a una versión más nueva')
    with engine.begin() as connection:
        if engine.dialect.name == 'sqlite':
            connection.exec_driver_sql('BEGIN IMMEDIATE')
        elif engine.dialect.name == 'postgresql':
            connection.exec_driver_sql('SELECT pg_advisory_xact_lock(70420113)')
        Base.metadata.create_all(connection)
        protector = cipher()
        # Validate every existing envelope first, then rewrite legacy plaintext in the same transaction.
        from sqlalchemy import text
        transforms = []
        for table, keys, columns in [('documents', ['owner'], ['payload']), ('learning_documents', ['owner'], ['payload']), ('plan_proposals', ['id'], ['payload', 'previous']), ('sync_receipts', ['owner','request_id'], ['payload'])]:
            for row in connection.exec_driver_sql(f"SELECT {', '.join(keys+columns)} FROM {table}").mappings():
                updates = {}
                for column in columns:
                    value = row[column]
                    unseal(value)
                    if protector and not value.startswith(PREFIX):
                        updates[column] = seal(value)
                if updates:
                    transforms.append((table, {key: row[key] for key in keys}, updates))
        for table, identities, values in transforms:
            assignments = ', '.join(f'{column} = :{column}' for column in values)
            condition = ' AND '.join(f'{key} = :identity_{key}' for key in identities)
            parameters = dict(values, **{f'identity_{key}': value for key,value in identities.items()})
            connection.execute(text(f'UPDATE {table} SET {assignments} WHERE {condition}'), parameters)
        existing = set(connection.execute(select(SchemaVersion.version)).scalars())
        for version in (1, 2, 3, 4, 5):
            if version not in existing:
                connection.execute(SchemaVersion.__table__.insert().values(
                    version=version, applied_at=datetime.now(timezone.utc).isoformat()))



def make_engine(url):
    return create_engine(url, connect_args={'check_same_thread': False} if url.startswith('sqlite') else {})
