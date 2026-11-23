from datetime import date, datetime
from typing import Literal
from uuid import UUID, uuid4
from zoneinfo import ZoneInfo
from pydantic import BaseModel, Field, model_validator, field_validator

class Window(BaseModel):
    days: list[int] = Field(default_factory=lambda: list(range(7)), max_length=7)
    start: int = Field(default=480, ge=0, le=1439)
    end: int = Field(default=1320, ge=1, le=1440)

    @model_validator(mode='after')
    def valid(self):
        if self.end <= self.start or any(d not in range(7) for d in self.days):
            raise ValueError('Franja horaria inválida')
        return self

class Activity(BaseModel):
    goalId: UUID | None = None
    id: UUID = Field(default_factory=uuid4)
    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default='', max_length=4000)
    category: str = Field(default='Personal', max_length=80)
    startDate: date
    deadline: date | None = None
    duration: int = Field(default=60, ge=5, le=1440)
    priority: int = Field(default=2, ge=1, le=4)
    status: Literal['Pendiente', 'Programada', 'En curso', 'Completada', 'Cancelada', 'Vencida'] = 'Pendiente'
    frequency: Literal['Única', 'Diaria', 'Semanal', 'Mensual', 'Personalizada'] = 'Única'
    interval: int = Field(default=1, ge=1, le=365)
    days: list[int] = Field(default_factory=lambda: list(range(7)), max_length=7)
    until: date | None = None
    skippedDates: list[date] = Field(default_factory=list, max_length=3660)
    windows: list[Window] = Field(default_factory=lambda: [Window()], max_length=30)
    excluded: list[Window] = Field(default_factory=list, max_length=30)
    preferredStart: int = Field(default=540, ge=0, le=1439)
    fixedStart: int | None = Field(default=None, ge=0, le=1439)
    leadMinutes: int = Field(default=0, ge=0, le=525600)
    createdAt: str = ''
    updatedAt: str = ''

    @model_validator(mode='after')
    def valid(self):
        if not self.title.strip():
            raise ValueError('El título no puede estar vacío')
        if self.deadline and self.deadline < self.startDate:
            raise ValueError('La fecha límite es anterior al inicio')
        if self.until and self.until < self.startDate:
            raise ValueError('El fin de recurrencia es anterior al inicio')
        if any(d not in range(7) for d in self.days):
            raise ValueError('Día inválido')
        return self

class Slot(BaseModel):
    activityId: UUID
    date: date
    start: int = Field(ge=0, le=1439)
    end: int = Field(ge=1, le=1440)
    completed: bool = False

    @model_validator(mode='after')
    def valid(self):
        if self.end <= self.start:
            raise ValueError('Horario inválido')
        return self

class Preferences(BaseModel):
    name: str = Field(default='', max_length=100)
    timezone: str = 'America/Argentina/Buenos_Aires'
    theme: Literal['Sistema', 'Claro', 'Oscuro'] = 'Sistema'
    transition: int = Field(default=0, ge=0, le=180)
    reminderMinutes: int = Field(default=15, ge=0, le=1440)
    notifications: bool = True
    notificationKinds: list[Literal["before", "start", "overdue", "daily", "weekly", "pending"]] = Field(default_factory=lambda: ["before", "start"], max_length=6)

    @field_validator('timezone')
    @classmethod
    def timezone_valid(cls, v):
        try:
            ZoneInfo(v)
        except Exception:
            raise ValueError('Zona horaria desconocida')
        return v

class Event(BaseModel):
    at: str
    text: str = Field(max_length=500)

class Pending(BaseModel):
    activityId: UUID
    date: date
    reason: str

class State(BaseModel):
    schemaVersion: Literal[1] = 1
    activities: list[Activity] = Field(default_factory=list, max_length=2000)
    availability: list[Window] = Field(default_factory=lambda: [Window()], max_length=60)
    blocked: list[Slot] = Field(default_factory=list, max_length=5000)
    schedule: list[Slot] = Field(default_factory=list, max_length=10000)
    pending: list[Pending] = Field(default_factory=list, max_length=10000)
    preferences: Preferences = Field(default_factory=Preferences)
    history: list[Event] = Field(default_factory=list, max_length=10000)

    @model_validator(mode='after')
    def unique(self):
        ids = [a.id for a in self.activities]
        if len(ids) != len(set(ids)):
            raise ValueError('Identificadores de actividades duplicados')
        keys = [(s.activityId, s.date) for s in self.schedule]
        if len(keys) != len(set(keys)):
            raise ValueError('Ocurrencias duplicadas')
        if any(s.activityId not in ids for s in self.schedule):
            raise ValueError('Horario sin actividad')
        return self

class PlanRequest(BaseModel):
    state: State
    start: date
    days: int = Field(default=7, ge=1, le=93)
    now: datetime | None = None

class PlanResult(BaseModel):
    schedule: list[Slot]
    pending: list[Pending]
    conflicts: list[str]
    changes: list[str]

class SyncRequest(BaseModel):
    revision: int = Field(ge=0)
    state: State

class AIRequest(BaseModel):
    message: str = Field(min_length=1, max_length=4000)
    state: State

class AIResponse(BaseModel):
    answer: str = Field(max_length=12000)
    proposedActivity: Activity | None = None
