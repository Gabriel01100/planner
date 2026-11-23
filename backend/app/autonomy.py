"""Opt-in conservative automatic moves. Never modifies activities or preferences."""

from datetime import datetime, timedelta, timezone
from uuid import uuid4
from zoneinfo import ZoneInfo
from fastapi import HTTPException
from sqlalchemy import select, update
from sqlalchemy.orm import Session
from .database import LearningDocument, StoredProposal, AuditEntry
from .learning_models import LearningMemory
from .models import PlanRequest
from .agenda import stable_plan


def automatic_plan(agenda, owner, *, now=None):
    now = now or datetime.now(timezone.utc)
    revision, state = agenda.read(owner)
    with Session(agenda.engine) as db:
        memory_row = db.get(LearningDocument, owner)
        memory = (
            LearningMemory.model_validate_json(memory_row.payload)
            if memory_row
            else LearningMemory()
        )
        prefs = memory.preferences
        if (
            not prefs.automaticEnabled
            or prefs.autonomy != "bounded"
            or prefs.maxAutomaticMoves == 0
        ):
            return {
                "applied": False,
                "reason": "La aplicación automática no está autorizada",
                "revision": revision,
            }
        today = now.astimezone(ZoneInfo(state.preferences.timezone)).date()
        request = PlanRequest(state=state, start=today, days=1, now=now)
        try:
            result = stable_plan(request)
        except ValueError as error:
            raise HTTPException(422, str(error))
        old = {(s.activityId, s.date): s for s in state.schedule}
        new = {(s.activityId, s.date): s for s in result.schedule}
        changed = [
            key for key in old.keys() | new.keys() if old.get(key) != new.get(key)
        ]
        if not changed:
            return {
                "applied": False,
                "reason": "Las sesiones válidas ya se conservan",
                "revision": revision,
            }
        recent = db.scalars(
            select(AuditEntry).where(
                AuditEntry.owner == owner,
                AuditEntry.action == "plan.auto.move",
                AuditEntry.at >= (now - timedelta(days=7)).isoformat(),
            )
        ).all()
        by_id = {a.id: a for a in state.activities}
        safe = (
            not result.conflicts
            and len(changed) + len(recent) <= prefs.maxAutomaticMoves
        )
        for key in changed:
            before, after, activity = old.get(key), new.get(key), by_id[key[0]]
            safe &= (
                before is not None
                and after is not None
                and not before.completed
                and activity.fixedStart is None
                and before.date == today
                and after.date == today
                and abs(before.start - after.start) <= prefs.maxShiftMinutes
            )
        study_ids = {
            a.id
            for a in state.activities
            if a.goalId is not None or a.category == "Estudio"
        }
        safe &= (
            sum(
                s.end - s.start
                for s in result.schedule
                if s.date == today and s.activityId in study_ids
            )
            <= prefs.dailyStudyLimitMinutes
        )
        if not safe:
            return {
                "applied": False,
                "requiresApproval": True,
                "reason": "El cambio excede las reglas autorizadas. Generá y revisá una propuesta.",
                "revision": revision,
            }
        # Lock/check consent in the same transaction as the agenda CAS.
        consent = db.execute(
            update(LearningDocument)
            .where(
                LearningDocument.owner == owner,
                LearningDocument.revision == memory_row.revision,
            )
            .values(revision=memory_row.revision)
        ).rowcount
        if consent != 1:
            raise HTTPException(409, "Las reglas de autonomía cambiaron")
        proposal_id = uuid4()
        updated = state.model_copy(deep=True)
        updated.schedule, updated.pending = result.schedule, result.pending
        next_revision = agenda.save(
            db, owner, revision, updated, "plan.auto", proposal_id
        )
        db.add(
            StoredProposal(
                id=str(proposal_id),
                owner=owner,
                base_revision=revision,
                payload=result.model_dump_json(),
                previous=state.model_dump_json(),
                expires_at=(now + timedelta(hours=1)).isoformat(),
                status="applied",
                applied_revision=next_revision,
            )
        )
        for key in changed:
            db.add(
                AuditEntry(
                    id=str(uuid4()),
                    owner=owner,
                    action="plan.auto.move",
                    at=now.isoformat(),
                    entity_id=str(key[0]),
                )
            )
        agenda.commit(db)
        return {
            "applied": True,
            "proposalId": str(proposal_id),
            "revision": next_revision,
            "reason": f"{len(changed)} cambio(s) dentro del mismo día y de los límites autorizados; se puede revertir.",
        }
