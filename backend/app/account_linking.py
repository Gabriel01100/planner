"""Explicit double possession: mobile challenge claimed by an authenticated OIDC identity."""

import secrets
import os
from urllib.parse import urlsplit
from datetime import datetime, timedelta, timezone
from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field, StrictBool
from sqlalchemy import update, select, delete
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .database import AccountLink, LinkChallenge, Document, LearningDocument, AuditEntry
from .security import digest
from .learning_models import Contract
from uuid import uuid4


class LinkRevoke(Contract):
    confirmed: StrictBool


class LinkClaim(Contract):
    code: str = Field(min_length=32, max_length=200)
    confirmed: StrictBool


def linking_router(access):
    router = APIRouter(prefix="/api/v1/connections", tags=["Vinculación"])
    identity = access.principal_dependency("memory:write")

    @router.get("/link/config")
    def config():
        issuer = os.getenv("OAUTH_ISSUER", "")
        resource = os.getenv("OAUTH_RESOURCE_URL", "")
        client = os.getenv("OIDC_LINK_CLIENT_ID", "")
        parameter = os.getenv("OIDC_AUDIENCE_PARAMETER", "resource")
        if (
            not issuer.startswith("https://")
            or not resource.startswith("https://")
            or not client
            or parameter not in ["resource", "audience"]
        ):
            raise HTTPException(
                503, "Configurá OAuth y el cliente público PKCE de vinculación"
            )
        parsed = urlsplit(resource)
        return {
            "issuer": issuer,
            "resource": resource,
            "clientId": client,
            "redirectUri": f"{parsed.scheme}://{parsed.netloc}/connect",
            "scopes": "openid memory:read memory:write schedule:read schedule:write",
            "audienceParameter": parameter,
        }

    @router.get("/identity")
    def who(principal=Depends(access.principal_dependency("memory:read"))):
        return {"workspaceId": principal.owner, "authentication": principal.auth_kind}

    @router.post("/link/start")
    def start(principal=Depends(identity)):
        access.check(principal, "schedule:write")
        code = secrets.token_urlsafe(32)
        expires = datetime.now(timezone.utc) + timedelta(minutes=5)
        with Session(access.engine) as db:
            db.execute(
                delete(LinkChallenge).where(
                    LinkChallenge.expires_at < datetime.now(timezone.utc).isoformat()
                )
            )
            db.add(
                LinkChallenge(
                    digest=digest(code),
                    owner=principal.owner,
                    expires_at=expires.isoformat(),
                    used=0,
                )
            )
            db.commit()
        return {
            "code": code,
            "expiresAt": expires.isoformat(),
            "instruction": "Ingresá este código solo en el flujo de vinculación de tu sesión OAuth autenticada; no lo pegues en una conversación.",
        }

    @router.post("/link/claim")
    def claim(command: LinkClaim, principal=Depends(identity)):
        access.check(principal, "schedule:write")
        if (
            principal.auth_kind != "oidc"
            or not principal.identity
            or not command.confirmed
        ):
            raise HTTPException(
                403, "Se requiere una sesión OAuth y confirmación explícita"
            )
        with Session(access.engine) as db:
            challenge = db.get(LinkChallenge, digest(command.code))
            if (
                not challenge
                or challenge.used
                or datetime.fromisoformat(challenge.expires_at)
                <= datetime.now(timezone.utc)
            ):
                raise HTTPException(409, "Código vencido o utilizado")
            if principal.owner == challenge.owner:
                raise HTTPException(409, "Las conexiones ya comparten el espacio")
            # Never merge or orphan an existing second workspace.
            if (
                db.get(Document, principal.owner)
                or db.get(LearningDocument, principal.owner)
                or db.get(AccountLink, principal.identity)
            ):
                raise HTTPException(
                    409,
                    "El espacio OAuth ya tiene datos o está vinculado. No se reemplazará.",
                )
            consumed = db.execute(
                update(LinkChallenge)
                .where(
                    LinkChallenge.digest == challenge.digest, LinkChallenge.used == 0
                )
                .values(used=1)
            ).rowcount
            if consumed != 1:
                raise HTTPException(409, "Código utilizado")
            db.add(AccountLink(identity=principal.identity, owner=challenge.owner))
            db.add(
                AuditEntry(
                    id=str(uuid4()),
                    owner=challenge.owner,
                    action="connection.link",
                    at=datetime.now(timezone.utc).isoformat(),
                    entity_id=principal.identity,
                )
            )
            target = challenge.owner
            try:
                db.commit()
            except IntegrityError:
                db.rollback()
                raise HTTPException(
                    409, "La vinculación cambió; revisá el estado antes de reintentar"
                )
        return {"workspaceId": target, "linked": True}

    @router.get("/links")
    def links(principal=Depends(access.principal_dependency("memory:read"))):
        with Session(access.engine) as db:
            return {
                "items": db.scalars(
                    select(AccountLink.identity).where(
                        AccountLink.owner == principal.owner
                    )
                ).all()
            }

    @router.post("/links/{identity_id}/revoke")
    def revoke_link(identity_id: str, command: LinkRevoke, principal=Depends(identity)):
        access.check(principal, "schedule:write")
        if not command.confirmed:
            raise HTTPException(422, "Confirmá la desvinculación")
        with Session(access.engine) as db:
            link = db.get(AccountLink, identity_id)
            if not link or link.owner != principal.owner:
                raise HTTPException(404, "Vinculación no encontrada")
            db.execute(
                delete(AccountLink).where(
                    AccountLink.identity == identity_id,
                    AccountLink.owner == principal.owner,
                )
            )
            db.add(
                AuditEntry(
                    id=str(uuid4()),
                    owner=principal.owner,
                    action="connection.unlink",
                    at=datetime.now(timezone.utc).isoformat(),
                    entity_id=identity_id,
                )
            )
            db.commit()
        return {"unlinked": True, "dataPreserved": True}

    return router
