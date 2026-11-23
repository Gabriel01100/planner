"""Learning domain service shared by REST, MCP and explicit imports."""
import hashlib
import json
from datetime import datetime, timezone
from uuid import UUID, uuid4
from fastapi import HTTPException
from sqlalchemy import update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from .database import LearningDocument, AuditEntry
from .learning_models import (LearningMemory, MemoryEnvelope, MutationResult, LearningGoal,
    LearningMilestone, Topic, StudySession, ProgressEvent, SourceReference, NextStep,
    SessionRegistration, Receipt, GoalDetails)


def normalized(text):
    return ' '.join(text.casefold().split())


def utcnow():
    return datetime.now(timezone.utc)

class LearningService:
    def __init__(self, engine):
        self.engine = engine

    def read(self, owner):
        with Session(self.engine) as db:
            row = db.get(LearningDocument, owner)
            return MemoryEnvelope(revision=row.revision if row else 0,
                                  memory=LearningMemory.model_validate_json(row.payload) if row else LearningMemory())

    def mutate(self, owner, action, change, expected=None):
        with Session(self.engine) as db:
            row = db.get(LearningDocument, owner)
            revision = row.revision if row else 0
            if expected is not None and expected != revision:
                raise HTTPException(409, 'La memoria cambió. Revisá la versión actual antes de continuar.')
            memory = LearningMemory.model_validate_json(row.payload) if row else LearningMemory()
            before = memory.model_dump_json()
            entity_id, duplicate = change(memory)
            # Revalidate the entire aggregate before any write.
            valid = LearningMemory.model_validate(memory.model_dump(mode='json'))
            payload = valid.model_dump_json()
            if before == payload:
                return MutationResult(revision=revision, entityId=entity_id, duplicate=duplicate, message='Registro ya existente' if duplicate else 'Sin cambios')
            if row:
                count = db.execute(update(LearningDocument).where(LearningDocument.owner == owner,
                    LearningDocument.revision == revision).values(payload=payload, revision=revision + 1)).rowcount
                if count != 1:
                    db.rollback()
                    raise HTTPException(409, 'La memoria fue modificada por otra conexión')
            else:
                db.add(LearningDocument(owner=owner, payload=payload, revision=1))
            db.add(AuditEntry(id=str(uuid4()), owner=owner, action=action, at=utcnow().isoformat(), entity_id=str(entity_id)))
            try:
                db.commit()
            except IntegrityError:
                db.rollback()
                raise HTTPException(409, 'La memoria fue modificada por otra conexión')
            return MutationResult(revision=revision + 1, entityId=entity_id, duplicate=duplicate, message='Guardado')

    @staticmethod
    def goal(memory, goal_id):
        goal = next((g for g in memory.goals if g.id == goal_id), None)
        if goal is None:
            raise HTTPException(404, 'Objetivo no encontrado. Elegí y confirmá un objetivo existente.')
        return goal

    def create_goal(self, owner, goal: LearningGoal):
        def change(memory):
            old = next((g for g in memory.goals if g.id == goal.id), None)
            if old and old != goal:
                raise HTTPException(409, 'El identificador ya pertenece a otro objetivo')
            if not old:
                memory.goals.append(goal)
            return goal.id, old is not None
        return self.mutate(owner, 'goal.create', change)

    def create_milestone(self, owner, milestone: LearningMilestone):
        def change(memory):
            self.goal(memory, milestone.goalId)
            old = next((m for m in memory.milestones if m.id == milestone.id), None)
            if old and old != milestone:
                raise HTTPException(409, 'El identificador ya pertenece a otro hito')
            if not old:
                memory.milestones.append(milestone)
            return milestone.id, old is not None
        return self.mutate(owner, 'milestone.create', change)

    def create_session(self, owner, session: StudySession):
        def change(memory):
            self.goal(memory, session.goalId)
            old = next((s for s in memory.sessions if s.id == session.id), None)
            if old and old != session:
                raise HTTPException(409, 'La sesión ya existe; usá el registro de progreso para finalizarla')
            if not old:
                memory.sessions.append(session)
            return session.id, old is not None
        return self.mutate(owner, 'session.create', change)

    def register_progress(self, owner, command: SessionRegistration):
        raw = command.model_dump(mode='json', exclude={'requestId', 'confirmed'}, exclude_defaults=True)
        fingerprint = hashlib.sha256(json.dumps(raw, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        def change(memory):
            self.goal(memory, command.goalId)
            receipt = next((r for r in memory.receipts if r.requestId == command.requestId), None)
            if receipt:
                if receipt.fingerprint != fingerprint:
                    raise HTTPException(409, 'La clave de idempotencia ya se utilizó con otros datos')
                return receipt.entityId, True
            same = next((r for r in memory.receipts if r.fingerprint == fingerprint), None)
            if same:
                return same.entityId, True
            if command.sourceContentDigest and command.sourceType != 'manual':
                source_ids = {source.id for source in memory.sources if source.contentDigest == command.sourceContentDigest}
                matching = next((event for event in memory.events if event.goalId == command.goalId and event.sourceReferenceId in source_ids and event.sessionId
                    and any(session.id == event.sessionId and session.completedAt.date() == command.completedAt.date()
                            and (not command.startedAt or session.startedAt == command.startedAt) for session in memory.sessions)
                    and any(topic.id == event.topicId and normalized(topic.title) == normalized(command.topic) and normalized(topic.subject) == normalized(command.subject) for topic in memory.topics)), None)
                if matching:
                    return matching.sessionId, True
            topic = next((t for t in memory.topics if t.goalId == command.goalId and normalized(t.title) == normalized(command.topic) and normalized(t.subject) == normalized(command.subject)), None)
            if not topic:
                topic = Topic(goalId=command.goalId, title=command.topic, subject=command.subject)
                memory.topics.append(topic)
            session_id = command.sessionId or uuid4()
            old = next((s for s in memory.sessions if s.id == session_id), None)
            if old and (old.goalId != command.goalId or old.status == 'completed'):
                raise HTTPException(409, 'La sesión completada requiere una corrección explícita, no otro registro')
            source = SourceReference(sourceType=command.sourceType, sourceTitle=command.sourceTitle,
                sourceUrl=command.sourceUrl, externalReference=command.externalReference,
                importedAt=utcnow(), contentDigest=command.sourceContentDigest or fingerprint)
            memory.sources.append(source)
            session = StudySession(id=session_id, goalId=command.goalId, topicId=topic.id,
                activityId=command.activityId or (old.activityId if old else None),
                startedAt=command.startedAt or (old.startedAt if old else None), completedAt=command.completedAt,
                plannedMinutes=command.plannedMinutes or (old.plannedMinutes if old else None),
                actualMinutes=command.actualMinutes, status='completed', notes=command.notes)
            memory.sessions = [s for s in memory.sessions if s.id != session_id] + [session]
            memory.events.append(ProgressEvent(goalId=command.goalId, topicId=topic.id, sessionId=session_id,
                eventType='session', description=command.notes or command.topic, occurredAt=command.completedAt,
                sourceReferenceId=source.id, evidenceType='confirmed', understood=command.understood,
                needsReinforcement=command.needsReinforcement, difficulties=command.difficulties,
                exercises=command.exercises, evaluation=command.evaluation, subtopics=command.subtopics))
            # A statement of understanding is a user report, not an assessment of mastery.
            for description in command.pendingTasks + ([command.recommendedNextTopic] if command.recommendedNextTopic else []):
                if not any(s.goalId == command.goalId and s.status in ['pending', 'active'] and normalized(s.description) == normalized(description) for s in memory.nextSteps):
                    memory.nextSteps.append(NextStep(goalId=command.goalId, topicId=topic.id,
                        description=description, estimatedMinutes=command.nextEstimatedMinutes,
                        rationale='Propuesto al registrar la sesión; estimación revisable', isEstimate=True))
            memory.receipts.append(Receipt(goalId=command.goalId, requestId=command.requestId, fingerprint=fingerprint, entityId=session_id))
            return session_id, False
        return self.mutate(owner, 'progress.register', change)

    def register_resource(self, owner, command):
        raw = command.model_dump(mode='json', exclude={'requestId', 'confirmed'}, exclude_defaults=True)
        fingerprint = hashlib.sha256(json.dumps(raw, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        def change(memory):
            self.goal(memory, command.goalId)
            old = next((r for r in memory.receipts if r.requestId == command.requestId), None)
            if old:
                if old.fingerprint != fingerprint:
                    raise HTTPException(409, 'La clave de idempotencia ya tiene otros datos')
                return old.entityId, True
            content_digest = command.contentDigest or fingerprint
            source = next((s for s in memory.sources if s.contentDigest == content_digest and s.sourceType == command.sourceType), None)
            if source and any(e.goalId == command.goalId and e.sourceReferenceId == source.id for e in memory.events):
                return source.id, True
            if not source:
                source = SourceReference(sourceType=command.sourceType, sourceTitle=command.title,
                    sourceUrl=command.sourceUrl, importedAt=utcnow(), contentDigest=content_digest)
                memory.sources.append(source)
            memory.events.append(ProgressEvent(goalId=command.goalId, eventType='note',
                description='Recurso: ' + command.title + (' · ' + command.summary if command.summary else ''),
                occurredAt=utcnow(), sourceReferenceId=source.id, evidenceType='confirmed'))
            memory.receipts.append(Receipt(goalId=command.goalId, requestId=command.requestId, fingerprint=fingerprint, entityId=source.id))
            return source.id, False
        return self.mutate(owner, 'resource.register', change)

    def details(self, owner, goal_id):
        memory = self.read(owner).memory
        goal = self.goal(memory, goal_id)
        completed = [s for s in memory.sessions if s.goalId == goal_id and s.status == 'completed']
        return GoalDetails(goal=goal, completedMinutes=sum(s.actualMinutes or 0 for s in completed),
            completedSessions=len(completed), unknownDurationSessions=sum(s.actualMinutes is None for s in completed),
            milestones=sorted([m for m in memory.milestones if m.goalId == goal_id], key=lambda m: m.order),
            topics=[t for t in memory.topics if t.goalId == goal_id],
            nextSteps=[s for s in memory.nextSteps if s.goalId == goal_id],
            events=[e for e in memory.events if e.goalId == goal_id][-100:])

    def recommended(self, owner, goal_id=None):
        memory = self.read(owner).memory
        done = {s.id for s in memory.nextSteps if s.status == 'completed'}
        active_goals = {g.id for g in memory.goals if g.status == 'active'}
        return sorted([s for s in memory.nextSteps if s.status in ['pending', 'active'] and s.goalId in active_goals
                       and (goal_id is None or s.goalId == goal_id) and all(d in done for d in s.dependencies)],
                      key=lambda s: (s.priority, str(s.id)))[:20]

    def replace(self, owner, memory, revision, confirmed):
        if not confirmed:
            raise HTTPException(422, 'Confirmá la corrección o importación de memoria')
        def change(old):
            for key in type(memory).model_fields:
                setattr(old, key, getattr(memory, key))
            return uuid4(), False
        return self.mutate(owner, 'memory.correct', change, expected=revision)

    def delete_goal(self, owner, goal_id, revision, confirmed):
        if not confirmed:
            raise HTTPException(422, 'Confirmá la eliminación del objetivo y su memoria asociada')
        def change(memory):
            self.goal(memory, goal_id)
            topic_ids = {t.id for t in memory.topics if t.goalId == goal_id}
            session_ids = {s.id for s in memory.sessions if s.goalId == goal_id}
            memory.goals = [g for g in memory.goals if g.id != goal_id]
            for key in ['milestones', 'topics', 'sessions', 'events', 'nextSteps']:
                setattr(memory, key, [item for item in getattr(memory, key) if item.goalId != goal_id])
            memory.competencies = [c for c in memory.competencies if c.topicId not in topic_ids]
            used_sources = {e.sourceReferenceId for e in memory.events}
            memory.sources = [s for s in memory.sources if s.id in used_sources]
            memory.receipts = [r for r in memory.receipts if r.goalId != goal_id and (r.entityId in {s.id for s in memory.sessions} or r.entityId in {source.id for source in memory.sources})]
            return goal_id, False
        return self.mutate(owner, 'goal.delete', change, expected=revision)
