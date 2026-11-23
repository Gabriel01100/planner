"""Versioned agenda mutations; no LLM writes SQL or applies plans directly."""
import json
from datetime import datetime, timezone, timedelta
from uuid import uuid4, UUID
from fastapi import HTTPException
from pydantic import Field
from sqlalchemy import Column, Integer, String, Text, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .database import Document, AuditEntry, StoredProposal
from .models import State, Activity, PlanRequest, PlanResult
from .planner import plan, validate
from .learning_models import Contract

class PlanProposal(Contract):
    id: UUID
    agendaRevision: int
    expiresAt: str
    requiresApproval: bool = True
    result: PlanResult
    reason: str

class AgendaResult(Contract):
    revision: int
    entityId: UUID | None = None
    message: str


def stable_plan(request):
    """Reuse the existing engine, pinning independently valid future sessions."""
    original = request.state
    now = request.now or datetime.now(timezone.utc)
    from zoneinfo import ZoneInfo
    zone = ZoneInfo(original.preferences.timezone)
    now = now.replace(tzinfo=zone) if now.tzinfo is None else now.astimezone(zone)
    last = request.start + timedelta(days=request.days - 1)
    keep = []
    by_id = {a.id: a for a in original.activities}
    for slot in sorted(original.schedule, key=lambda s: (s.date, s.start, str(s.activityId))):
        activity = by_id[slot.activityId]
        start = datetime.combine(slot.date, datetime.min.time(), tzinfo=zone) + timedelta(minutes=slot.start)
        if slot.completed or activity.status == 'Completada' or not request.start <= slot.date <= last:
            keep.append(slot)
        elif activity.status != 'Cancelada' and start >= now + timedelta(minutes=activity.leadMinutes) and not validate(original, keep + [slot]):
            keep.append(slot)
    pinned = {(s.activityId, s.date): s.completed for s in keep}
    state = original.model_copy(deep=True)
    state.schedule = [s.model_copy(update={'completed': True}) if (s.activityId, s.date) in pinned else s for s in original.schedule]
    result = plan(request.model_copy(update={'state': state}))
    result.schedule = [s.model_copy(update={'completed': pinned[(s.activityId, s.date)]}) if (s.activityId, s.date) in pinned else s for s in result.schedule]
    result.conflicts = validate(original, result.schedule)
    return result

class AgendaService:
    def __init__(self, engine):
        self.engine = engine

    def read(self, owner):
        with Session(self.engine) as db:
            row = db.get(Document, owner)
            return (row.revision, State.model_validate_json(row.payload)) if row else (0, State())

    @staticmethod
    def save(db, owner, expected, state, action, entity_id=None):
        state = State.model_validate(state.model_dump(mode='json'))
        row = db.get(Document, owner)
        revision = row.revision if row else 0
        if revision != expected:
            raise HTTPException(409, 'La agenda cambió. Revisá la versión actual.')
        payload = state.model_dump_json()
        if row:
            changed = db.execute(update(Document).where(Document.owner == owner, Document.revision == expected)
                                 .values(payload=payload, revision=expected + 1)).rowcount
            if changed != 1:
                raise HTTPException(409, 'La agenda cambió durante la operación')
        else:
            db.add(Document(owner=owner, revision=1, payload=payload))
        db.add(AuditEntry(id=str(uuid4()), owner=owner, action=action, at=datetime.now(timezone.utc).isoformat(), entity_id=str(entity_id) if entity_id else None))
        return expected + 1

    @staticmethod
    def commit(db):
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
            raise HTTPException(409, 'La agenda cambió durante la operación')

    def create_task(self, owner, activity):
        revision, state = self.read(owner)
        old = next((a for a in state.activities if a.id == activity.id), None)
        if old:
            if old != activity:
                raise HTTPException(409, 'La actividad ya existe con otros datos')
            return AgendaResult(revision=revision, entityId=activity.id, message='Actividad ya existente')
        state.activities.append(activity)
        with Session(self.engine) as db:
            revision = self.save(db, owner, revision, state, 'task.create', activity.id)
            self.commit(db)
        return AgendaResult(revision=revision, entityId=activity.id, message='Actividad creada')

    def add_deadline(self, owner, activity_id, deadline, revision, confirmed):
        if not confirmed:
            raise HTTPException(422, 'Confirmá el cambio de fecha límite')
        current, state = self.read(owner)
        if current != revision:
            raise HTTPException(409, 'La agenda cambió')
        found = next((a for a in state.activities if a.id == activity_id), None)
        if not found:
            raise HTTPException(404, 'Actividad no encontrada')
        if deadline < found.startDate:
            raise HTTPException(422, 'La fecha límite es anterior al inicio')
        found.deadline = deadline
        with Session(self.engine) as db:
            new_revision = self.save(db, owner, revision, state, 'task.deadline', activity_id)
            self.commit(db)
        return AgendaResult(revision=new_revision, entityId=activity_id, message='Fecha límite actualizada')

    def propose(self, owner, start, days=7):
        revision, state = self.read(owner)
        result = stable_plan(PlanRequest(state=state, start=start, days=days))
        proposal_id = uuid4()
        expires = (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()
        with Session(self.engine) as db:
            db.add(StoredProposal(id=str(proposal_id), owner=owner, base_revision=revision,
                payload=result.model_dump_json(), previous=state.model_dump_json(), expires_at=expires, status='pending'))
            self.commit(db)
        return PlanProposal(id=proposal_id, agendaRevision=revision, expiresAt=expires, result=result,
            reason='Se conservan sesiones válidas y completadas; revisá los cambios antes de aplicarlos.')

    def approve(self, owner, proposal_id, confirmed):
        if not confirmed:
            raise HTTPException(422, 'Confirmá expresamente la planificación propuesta')
        with Session(self.engine) as db:
            proposal = db.get(StoredProposal, str(proposal_id))
            if not proposal or proposal.owner != owner:
                raise HTTPException(404, 'Propuesta no encontrada')
            if proposal.status != 'pending' or datetime.fromisoformat(proposal.expires_at) < datetime.now(timezone.utc):
                raise HTTPException(409, 'La propuesta venció o ya fue utilizada')
            result = PlanResult.model_validate_json(proposal.payload)
            state = State.model_validate_json(proposal.previous)
            if result.conflicts:
                raise HTTPException(422, 'La propuesta tiene conflictos que requieren revisión')
            if validate(state, result.schedule):
                raise HTTPException(422, 'La propuesta no cumple las restricciones')
            state.schedule, state.pending = result.schedule, result.pending
            revision = self.save(db, owner, proposal.base_revision, state, 'plan.approve', proposal_id)
            proposal.status, proposal.applied_revision = 'applied', revision
            self.commit(db)
            return AgendaResult(revision=revision, entityId=proposal_id, message='Planificación aplicada')

    def undo(self, owner, proposal_id, confirmed):
        if not confirmed:
            raise HTTPException(422, 'Confirmá la reversión')
        with Session(self.engine) as db:
            proposal = db.get(StoredProposal, str(proposal_id))
            if not proposal or proposal.owner != owner:
                raise HTTPException(404, 'Propuesta no encontrada')
            if proposal.status != 'applied':
                raise HTTPException(409, 'La propuesta no está aplicada')
            old = State.model_validate_json(proposal.previous)
            revision = self.save(db, owner, proposal.applied_revision, old, 'plan.undo', proposal_id)
            proposal.status = 'reverted'
            self.commit(db)
            return AgendaResult(revision=revision, entityId=proposal_id, message='Planificación anterior restaurada')
