"""Three-way merge: preserve independent edits, explicitly reject ambiguous changes."""

import hashlib
import json
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field, ValidationError
from sqlalchemy.orm import Session
from .models import State
from .learning_models import Contract
from .database import SyncReceipt
from .planner import validate

MISSING = object()


def merge_value(base, local, remote, path="state"):
    if local == remote or remote == base:
        return local
    if local == base:
        return remote
    if all(isinstance(item, dict) for item in [base, local, remote]):
        result = {}
        for key in sorted(set(base) | set(local) | set(remote)):
            if (
                key == "updatedAt"
                and isinstance(local.get(key), str)
                and isinstance(remote.get(key), str)
            ):
                result[key] = max(local[key], remote[key])
                continue
            value = merge_value(
                base.get(key, MISSING),
                local.get(key, MISSING),
                remote.get(key, MISSING),
                path + "." + key,
            )
            if value is not MISSING:
                result[key] = value
        return result
    raise HTTPException(
        409,
        {
            "message": "Hay ediciones incompatibles. Se conservan ambas versiones.",
            "conflicts": [path],
        },
    )


def merge_rows(base, local, remote, key, path):
    maps = [{key(row): row for row in rows} for rows in [base, local, remote]]
    if any(
        len(mapping) != len(rows) for mapping, rows in zip(maps, [base, local, remote])
    ):
        raise HTTPException(422, "Identificadores duplicados al fusionar")
    result = merge_value(*maps, path=path)
    return list(result.values())


def merge_states(base, local, remote):
    values = [s.model_dump(mode="json") for s in [base, local, remote]]
    result = {}
    keyed = {
        "activities": lambda r: r["id"],
        "schedule": lambda r: r["activityId"] + "|" + r["date"],
        "blocked": lambda r: json.dumps(r, sort_keys=True),
        "pending": lambda r: r["activityId"] + "|" + r["date"],
    }
    for field in values[0]:
        rows = [value[field] for value in values]
        if field in keyed:
            result[field] = merge_rows(*rows, key=keyed[field], path=field)
        elif field == "history":
            result[field] = list(
                {
                    json.dumps(row, sort_keys=True): row for row in rows[2] + rows[1]
                }.values()
            )[-10000:]
        else:
            result[field] = merge_value(*rows, path=field)
    try:
        state = State.model_validate(result)
    except ValidationError:
        raise HTTPException(
            409,
            "La combinación viola referencias o dependencias; se conservan ambas versiones",
        )
    # Only reject newly introduced conflicts; existing historical constraints are not silently discarded.
    if set(validate(state, state.schedule)) - (
        set(validate(local, local.schedule)) | set(validate(remote, remote.schedule))
    ):
        raise HTTPException(
            409,
            "Los cambios combinados tienen conflictos de agenda; revisá ambas versiones",
        )
    return state


class MergeRequest(Contract):
    requestId: UUID
    revision: int = Field(ge=0)
    base: State
    state: State


def sync_router(agenda, access):
    router = APIRouter(prefix="/api/v1/sync", tags=["Sincronización"])

    @router.post("/merge")
    def merge(
        command: MergeRequest,
        principal=Depends(access.principal_dependency("schedule:write")),
    ):
        access.check(principal, "schedule:read")
        owner = principal.owner
        fingerprint = hashlib.sha256(command.model_dump_json().encode()).hexdigest()
        for attempt in range(3):
            with Session(agenda.engine) as db:
                receipt = db.get(SyncReceipt, (owner, str(command.requestId)))
                if receipt:
                    if receipt.fingerprint != fingerprint:
                        raise HTTPException(
                            409,
                            "La operación de sincronización ya tiene otro contenido",
                        )
                    return json.loads(receipt.payload)
                revision, remote = agenda.read(owner)
                if command.revision > revision:
                    raise HTTPException(
                        409, "La revisión remota retrocedió; revisá la recuperación"
                    )
                merged = merge_states(command.base, command.state, remote)
                if merged != remote:
                    revision = agenda.save(
                        db, owner, revision, merged, "sync.merge", command.requestId
                    )
                response = {
                    "revision": revision,
                    "state": merged.model_dump(mode="json"),
                }
                db.add(
                    SyncReceipt(
                        owner=owner,
                        request_id=str(command.requestId),
                        fingerprint=fingerprint,
                        payload=json.dumps(response),
                    )
                )
                try:
                    agenda.commit(db)
                    return response
                except HTTPException as error:
                    if error.status_code != 409 or attempt == 2:
                        raise
        raise HTTPException(409, "La agenda cambió; reintentá")

    return router
