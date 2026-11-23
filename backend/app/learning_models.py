"""Versioned learning contracts. Imported claims are never inferred mastery."""
from datetime import date, datetime
from typing import Literal
from uuid import UUID, uuid4
from pydantic import BaseModel, ConfigDict, Field, AwareDatetime, StrictBool, model_validator

class Contract(BaseModel):
    model_config = ConfigDict(extra='forbid', str_strip_whitespace=True)

class LearningGoal(Contract):
    id: UUID = Field(default_factory=uuid4)
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default='', max_length=4000)
    category: str = Field(default='Aprendizaje', max_length=80)
    priority: int = Field(default=2, ge=1, le=4)
    targetDate: date | None = None
    status: Literal['active', 'paused', 'completed', 'archived'] = 'active'
    estimatedTotalMinutes: int | None = Field(default=None, ge=5, le=1000000)

class LearningMilestone(Contract):
    id: UUID = Field(default_factory=uuid4)
    goalId: UUID
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default='', max_length=2000)
    order: int = Field(default=0, ge=0, le=10000)
    status: Literal['pending', 'active', 'completed'] = 'pending'
    estimatedMinutes: int | None = Field(default=None, ge=5, le=100000)
    completedAt: AwareDatetime | None = None

class Topic(Contract):
    subject: str = Field(default='', max_length=200)
    id: UUID = Field(default_factory=uuid4)
    goalId: UUID
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default='', max_length=2000)
    parentTopicId: UUID | None = None

class Competency(Contract):
    id: UUID = Field(default_factory=uuid4)
    topicId: UUID
    estimatedLevel: float | None = Field(default=None, ge=0, le=1)
    confidence: float = Field(default=0, ge=0, le=1)
    lastEvaluatedAt: AwareDatetime | None = None
    evidenceIds: list[UUID] = Field(default_factory=list, max_length=1000)
    assessmentMethod: str = Field(default='', max_length=1000)

    @model_validator(mode='after')
    def justified(self):
        if self.estimatedLevel is not None and (not self.evidenceIds or not self.assessmentMethod):
            raise ValueError('El dominio estimado requiere evidencia y método de evaluación')
        if self.estimatedLevel is None and self.confidence != 0:
            raise ValueError('Sin nivel estimado no hay confianza evaluada')
        return self

class StudySession(Contract):
    id: UUID = Field(default_factory=uuid4)
    goalId: UUID
    topicId: UUID | None = None
    activityId: UUID | None = None
    startedAt: AwareDatetime | None = None
    completedAt: AwareDatetime | None = None
    plannedMinutes: int | None = Field(default=None, ge=1, le=1440)
    actualMinutes: int | None = Field(default=None, ge=1, le=1440)
    status: Literal['planned', 'in_progress', 'completed', 'skipped'] = 'planned'
    notes: str = Field(default='', max_length=4000)

    @model_validator(mode='after')
    def timing(self):
        if self.startedAt and self.completedAt and self.completedAt < self.startedAt:
            raise ValueError('El fin de sesión es anterior al inicio')
        if self.status == 'completed' and self.completedAt is None:
            raise ValueError('Una sesión completada requiere fecha de finalización')
        return self

class SourceReference(Contract):
    id: UUID = Field(default_factory=uuid4)
    sourceType: Literal['chatgpt', 'notebooklm', 'text', 'pdf', 'link', 'manual'] = 'manual'
    sourceTitle: str = Field(default='', max_length=200)
    sourceUrl: str | None = Field(default=None, max_length=2000)
    externalReference: str | None = Field(default=None, max_length=200)
    importedAt: AwareDatetime
    contentDigest: str = Field(pattern=r'^[0-9a-f]{64}$')

    @model_validator(mode='after')
    def safe_url(self):
        if self.sourceUrl and not self.sourceUrl.startswith(('https://', 'http://')):
            raise ValueError('Solo se admiten enlaces HTTP(S) como referencias; no se descargan')
        return self

class ProgressEvent(Contract):
    id: UUID = Field(default_factory=uuid4)
    goalId: UUID
    topicId: UUID | None = None
    sessionId: UUID | None = None
    eventType: Literal['session', 'assessment', 'correction', 'note'] = 'note'
    description: str = Field(max_length=4000)
    occurredAt: AwareDatetime
    sourceReferenceId: UUID | None = None
    evidenceType: Literal['confirmed', 'estimate', 'recommendation'] = 'confirmed'
    understood: list[str] = Field(default_factory=list, max_length=50)
    needsReinforcement: list[str] = Field(default_factory=list, max_length=50)
    difficulties: list[str] = Field(default_factory=list, max_length=50)
    exercises: list[str] = Field(default_factory=list, max_length=50)
    evaluation: str | None = Field(default=None, max_length=2000)
    subtopics: list[str] = Field(default_factory=list, max_length=50)

class NextStep(Contract):
    id: UUID = Field(default_factory=uuid4)
    goalId: UUID
    topicId: UUID | None = None
    description: str = Field(min_length=1, max_length=2000)
    estimatedMinutes: int | None = Field(default=None, ge=5, le=1440)
    priority: int = Field(default=2, ge=1, le=4)
    dependencies: list[UUID] = Field(default_factory=list, max_length=100)
    status: Literal['pending', 'active', 'completed', 'cancelled'] = 'pending'
    activityId: UUID | None = None
    rationale: str = Field(default='', max_length=2000)
    isEstimate: bool = True

class LearningPreferences(Contract):
    autonomy: Literal['suggestions', 'bounded'] = 'bounded'
    preferredSessionMinutes: int = Field(default=45, ge=5, le=180)
    breakMinutes: int = Field(default=10, ge=0, le=60)
    dailyStudyLimitMinutes: int = Field(default=180, ge=15, le=720)
    maxAutomaticMoves: int = Field(default=2, ge=0, le=5)
    # Fully autonomous mode intentionally does not exist in this release.

class Receipt(Contract):
    goalId: UUID | None = None
    requestId: UUID
    fingerprint: str = Field(pattern=r'^[0-9a-f]{64}$')
    entityId: UUID

class LearningMemory(Contract):
    schemaVersion: Literal[1] = 1
    goals: list[LearningGoal] = Field(default_factory=list, max_length=1000)
    milestones: list[LearningMilestone] = Field(default_factory=list, max_length=5000)
    topics: list[Topic] = Field(default_factory=list, max_length=5000)
    competencies: list[Competency] = Field(default_factory=list, max_length=5000)
    sessions: list[StudySession] = Field(default_factory=list, max_length=10000)
    events: list[ProgressEvent] = Field(default_factory=list, max_length=10000)
    sources: list[SourceReference] = Field(default_factory=list, max_length=10000)
    nextSteps: list[NextStep] = Field(default_factory=list, max_length=5000)
    preferences: LearningPreferences = Field(default_factory=LearningPreferences)
    receipts: list[Receipt] = Field(default_factory=list, max_length=20000)

    @model_validator(mode='after')
    def references(self):
        for name in ['goals', 'milestones', 'topics', 'competencies', 'sessions', 'events', 'sources', 'nextSteps']:
            ids = [item.id for item in getattr(self, name)]
            if len(set(ids)) != len(ids):
                raise ValueError(f'Identificadores duplicados en {name}')
        goals = {g.id for g in self.goals}
        topics = {t.id: t for t in self.topics}
        sessions = {s.id: s for s in self.sessions}
        sources = {s.id for s in self.sources}
        events = {e.id: e for e in self.events}
        steps = {s.id: s for s in self.nextSteps}
        for group in [self.milestones, self.topics, self.sessions, self.events, self.nextSteps]:
            for item in group:
                if item.goalId not in goals:
                    raise ValueError('Referencia a un objetivo inexistente')
                topic_id = getattr(item, 'topicId', None)
                if topic_id and (topic_id not in topics or topics[topic_id].goalId != item.goalId):
                    raise ValueError('El tema no corresponde al objetivo')
        for topic in self.topics:
            seen = {topic.id}
            parent = topic.parentTopicId
            while parent:
                if parent in seen or parent not in topics or topics[parent].goalId != topic.goalId:
                    raise ValueError('Jerarquía de temas inválida')
                seen.add(parent)
                parent = topics[parent].parentTopicId
        for event in self.events:
            if event.sessionId and (event.sessionId not in sessions or sessions[event.sessionId].goalId != event.goalId):
                raise ValueError('Sesión de evidencia inválida')
            if event.sourceReferenceId and event.sourceReferenceId not in sources:
                raise ValueError('Fuente inexistente')
        for competency in self.competencies:
            if competency.topicId not in topics or any(e not in events or events[e].topicId != competency.topicId or events[e].evidenceType != 'confirmed' for e in competency.evidenceIds):
                raise ValueError('Evidencia de competencia inválida')
        from collections import deque
        counts = {step.id: len(set(step.dependencies)) for step in self.nextSteps}
        dependents = {step.id: [] for step in self.nextSteps}
        for step in self.nextSteps:
            for dependency in set(step.dependencies):
                if dependency not in steps or steps[dependency].goalId != step.goalId:
                    raise ValueError('Dependencia inexistente o de otro objetivo')
                dependents[dependency].append(step.id)
        ready = deque(id for id, count in counts.items() if count == 0)
        visited = 0
        while ready:
            id = ready.popleft()
            visited += 1
            for dependent in dependents[id]:
                counts[dependent] -= 1
                if counts[dependent] == 0:
                    ready.append(dependent)
        if visited != len(steps):
            raise ValueError('Dependencias cíclicas')
        if len({r.requestId for r in self.receipts}) != len(self.receipts) or any((r.entityId not in sessions and r.entityId not in sources) or (r.goalId is not None and r.goalId not in goals) for r in self.receipts):
            raise ValueError('Recibos de idempotencia inválidos')
        return self

class MemoryEnvelope(Contract):
    revision: int = Field(ge=0)
    memory: LearningMemory

class AssessmentData(Contract):
    correctCount: int = Field(ge=0, le=10000)
    totalCount: int = Field(ge=1, le=10000)
    method: str = Field(min_length=1, max_length=1000)
    # Explicitly supplied estimate, not a calibrated probability or inferred by the LLM.
    confidence: float = Field(ge=0, le=1)

    @model_validator(mode='after')
    def valid_score(self):
        if self.correctCount > self.totalCount:
            raise ValueError('El resultado correcto supera el total evaluado')
        return self

class SessionRegistration(Contract):
    assessment: AssessmentData | None = None
    requestId: UUID
    goalId: UUID
    topic: str = Field(min_length=1, max_length=200)
    subject: str = Field(default='', max_length=200)
    notes: str = Field(default='', max_length=4000)
    sessionId: UUID | None = None
    activityId: UUID | None = None
    startedAt: AwareDatetime | None = None
    completedAt: AwareDatetime
    plannedMinutes: int | None = Field(default=None, ge=1, le=1440)
    actualMinutes: int | None = Field(default=None, ge=1, le=1440)
    understood: list[str] = Field(default_factory=list, max_length=50)
    needsReinforcement: list[str] = Field(default_factory=list, max_length=50)
    difficulties: list[str] = Field(default_factory=list, max_length=50)
    exercises: list[str] = Field(default_factory=list, max_length=50)
    subtopics: list[str] = Field(default_factory=list, max_length=50)
    evaluation: str | None = Field(default=None, max_length=2000)
    pendingTasks: list[str] = Field(default_factory=list, max_length=20)
    recommendedNextTopic: str | None = Field(default=None, max_length=200)
    nextEstimatedMinutes: int | None = Field(default=None, ge=5, le=1440)
    sourceType: Literal['chatgpt', 'notebooklm', 'text', 'pdf', 'link', 'manual'] = 'manual'
    sourceTitle: str = Field(default='', max_length=200)
    sourceUrl: str | None = Field(default=None, max_length=2000)
    externalReference: str | None = Field(default=None, max_length=200)
    sourceContentDigest: str | None = Field(default=None, pattern=r'^[0-9a-f]{64}$')
    confirmed: StrictBool

    @model_validator(mode='after')
    def explicitly_confirmed(self):
        if self.sourceUrl and not self.sourceUrl.startswith(('https://', 'http://')):
            raise ValueError('Solo se admiten referencias HTTP(S)')
        if not self.confirmed:
            raise ValueError('Revisá y confirmá la sesión antes de guardarla')
        if self.startedAt and self.completedAt < self.startedAt:
            raise ValueError('Fecha de finalización anterior al inicio')
        for texts in [self.understood, self.needsReinforcement, self.difficulties, self.exercises, self.subtopics, self.pendingTasks]:
            if any(not t.strip() or len(t) > 1000 for t in texts):
                raise ValueError('Los contenidos deben tener entre 1 y 1000 caracteres')
        return self

class MutationResult(Contract):
    revision: int
    entityId: UUID
    duplicate: bool = False
    message: str

class GoalDetails(Contract):
    goal: LearningGoal
    completedMinutes: int
    completedSessions: int
    unknownDurationSessions: int
    milestones: list[LearningMilestone]
    topics: list[Topic]
    nextSteps: list[NextStep]
    events: list[ProgressEvent]

class ResourceRegistration(Contract):
    requestId: UUID
    goalId: UUID
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(default='', max_length=2000)
    sourceType: Literal['chatgpt', 'notebooklm', 'text', 'pdf', 'link', 'manual'] = 'text'
    sourceUrl: str | None = Field(default=None, max_length=2000)
    contentDigest: str | None = Field(default=None, pattern=r'^[0-9a-f]{64}$')
    confirmed: StrictBool

    @model_validator(mode='after')
    def checked(self):
        if not self.confirmed:
            raise ValueError('Confirmá la referencia antes de guardarla')
        if self.sourceUrl and not self.sourceUrl.startswith(('https://', 'http://')):
            raise ValueError('La referencia debe ser HTTP(S)')
        return self
