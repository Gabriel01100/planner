"""Personal legacy access plus revocable, scoped identities for new clients."""
import hashlib
import hmac
import os
import secrets
import threading
import time
from collections import defaultdict, deque
from dataclasses import dataclass
from fastapi import Header, HTTPException
from sqlalchemy.orm import Session
from .database import Credential, RevokedToken, AuditEntry, AccountLink
from uuid import uuid4
from datetime import datetime, timezone

SCOPES = {'memory:read', 'memory:write', 'schedule:read', 'schedule:write', 'schedule:approve', 'preferences:write'}

def digest(token):
    return hashlib.sha256(token.encode()).hexdigest()

@dataclass(frozen=True)
class Principal:
    owner: str
    scopes: frozenset[str]
    token_digest: str
    auth_kind: str = "admin"
    identity: str | None = None

class Access:
    def __init__(self, engine, legacy_token=''):
        self.engine, self.legacy_token = engine, legacy_token
        self.calls = defaultdict(deque)
        self.ai_calls = defaultdict(deque)
        self.lock = threading.Lock()
        self.jwt_client = None

    def authenticate(self, authorization):
        if not self.legacy_token and not os.getenv('OAUTH_ISSUER'):
            with Session(self.engine) as db:
                if db.query(Credential).first() is None:
                    raise HTTPException(503, 'Configurá credenciales de acceso en el servidor')
        token = authorization.removeprefix('Bearer ')
        if not authorization.startswith('Bearer ') or not token or len(token) > 16384:
            raise HTTPException(401, 'Credencial inválida', headers={'WWW-Authenticate': 'Bearer'})
        hashed = digest(token)
        with Session(self.engine) as db:
            if db.get(RevokedToken, hashed):
                raise HTTPException(401, 'Conexión revocada')
            if self.legacy_token and hmac.compare_digest(token.encode(), self.legacy_token.encode()):
                return Principal('personal', frozenset(SCOPES), hashed, 'legacy')
            credential = db.get(Credential, hashed)
            if credential and not credential.revoked:
                return Principal(credential.owner, frozenset(credential.scopes.split()), hashed)
        issuer = os.getenv('OAUTH_ISSUER', '')
        audience = os.getenv('OAUTH_RESOURCE_URL', '').rstrip('/')
        jwks = os.getenv('OAUTH_JWKS_URL', '')
        if issuer and audience and jwks:
            import jwt
            try:
                if not issuer.startswith('https://') or not audience.startswith('https://') or not jwks.startswith('https://'):
                    raise ValueError('OAuth requiere HTTPS')
                if self.jwt_client is None:
                    self.jwt_client = jwt.PyJWKClient(jwks, cache_keys=True, timeout=5)
                key = self.jwt_client.get_signing_key_from_jwt(token).key
                claims = jwt.decode(token, key, algorithms=['RS256'], audience=audience, issuer=issuer,
                                    options={'require': ['exp', 'iat', 'sub', 'iss', 'aud']})
                subject = claims['sub']
                if not isinstance(subject, str) or not subject or not isinstance(claims.get('scope', ''), str):
                    raise ValueError('Identidad inválida')
                scopes = frozenset(claims.get('scope', '').split()) & SCOPES
                identity = digest(issuer.rstrip('/') + '|' + subject)
                with Session(self.engine) as db:
                    link = db.get(AccountLink, identity)
                    owner = link.owner if link else identity
                return Principal(owner, scopes, hashed, 'oidc', identity)
            except (jwt.PyJWTError, ValueError, TypeError):
                raise HTTPException(401, 'Token OAuth inválido o vencido')
        if not self.legacy_token:
            # A configured scoped credential still works without a legacy token.
            with Session(self.engine) as db:
                if db.query(Credential).first() is None and not issuer:
                    raise HTTPException(503, 'Configurá credenciales de acceso en el servidor')
        raise HTTPException(401, 'Credencial inválida')

    def check(self, principal, scope):
        if scope not in principal.scopes:
            raise HTTPException(403, 'La conexión no tiene permiso para esta operación')
        with self.lock:
            now = time.monotonic()
            key = (principal.owner, scope)
            recent = self.calls[key]
            while recent and recent[0] <= now - 60:
                recent.popleft()
            if len(recent) >= 120:
                raise HTTPException(429, 'Demasiadas solicitudes. Intentá en un minuto.', headers={'Retry-After': '60'})
            recent.append(now)
            if len(self.calls) > 10000:
                for old in list(self.calls):
                    if not self.calls[old] or self.calls[old][-1] <= now - 60:
                        del self.calls[old]

    def consume_ai(self, owner):
        with self.lock:
            now = time.monotonic()
            recent = self.ai_calls[owner]
            while recent and recent[0] <= now - 3600:
                recent.popleft()
            try:
                limit = int(os.getenv('AI_REQUESTS_PER_HOUR', '20'))
            except ValueError:
                raise HTTPException(503, 'Revisá la configuración de cuota de IA del servidor')
            if len(recent) >= max(0, limit):
                raise HTTPException(429, 'Límite horario de IA alcanzado', headers={'Retry-After': '3600'})
            recent.append(now)
            for old in list(self.ai_calls):
                if self.ai_calls[old] and self.ai_calls[old][-1] <= now - 3600:
                    del self.ai_calls[old]

    def owner_dependency(self, scope):
        def dependency(authorization: str = Header(default='')):
            principal = self.authenticate(authorization)
            self.check(principal, scope)
            return principal.owner
        return dependency

    def principal_dependency(self, scope):
        def dependency(authorization: str = Header(default='')):
            principal = self.authenticate(authorization)
            self.check(principal, scope)
            return principal
        return dependency

    def issue(self, owner, scopes, label):
        if not owner or len(owner) > 64 or not set(scopes) <= SCOPES or not scopes:
            raise ValueError('Usuario o permisos inválidos')
        token = secrets.token_urlsafe(48)
        with Session(self.engine) as db:
            db.add(AuditEntry(id=str(uuid4()), owner=owner, action="connection.issue", at=datetime.now(timezone.utc).isoformat(), entity_id=digest(token)[:12]))
            db.add(Credential(digest=digest(token), owner=owner, scopes=' '.join(sorted(set(scopes))), label=label[:100], revoked=0))
            db.commit()
        return token

    def revoke(self, principal):
        with Session(self.engine) as db:
            if not db.get(RevokedToken, principal.token_digest):
                db.add(RevokedToken(digest=principal.token_digest))
            credential = db.get(Credential, principal.token_digest)
            if credential:
                credential.revoked = 1
            db.add(AuditEntry(id=str(uuid4()), owner=principal.owner, action="connection.revoke", at=datetime.now(timezone.utc).isoformat(), entity_id=principal.token_digest[:12]))
            db.commit()
