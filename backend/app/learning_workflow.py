"""Application boundary coordinating learning and agenda in one database transaction."""

from fastapi import HTTPException
from sqlalchemy.orm import Session
from .database import Document, LearningDocument
from .learning_models import LearningMemory
from .models import State


class LearningWorkflow:
    def __init__(self, learning, agenda):
        self.learning, self.agenda = learning, agenda

    def __getattr__(self, name):
        return getattr(self.learning, name)

    def register_progress(self, owner, command):
        with Session(self.agenda.engine) as db:
            doc = db.get(Document, owner)
            state = State.model_validate_json(doc.payload) if doc else State()
            activity = next(
                (a for a in state.activities if a.id == command.activityId), None
            )
            if command.activityId:
                if not activity:
                    raise HTTPException(404, "Actividad no encontrada en este espacio")
                if activity.goalId and activity.goalId != command.goalId:
                    raise HTTPException(422, "La actividad corresponde a otro objetivo")
                if activity.status == "Cancelada":
                    raise HTTPException(
                        409,
                        "Revisá la actividad cancelada antes de registrar su finalización",
                    )
            day = command.occurrenceDate
            if day and not activity:
                raise HTTPException(422, "La ocurrencia requiere una actividad")
            if activity:
                slots = [s for s in state.schedule if s.activityId == activity.id]
                # A one-off session can recover a single scheduled occurrence (or its
                # original activity date when it has never been scheduled).
                if day is None and activity.frequency == "Única":
                    day = slots[0].date if len(slots) == 1 else activity.startDate if not slots else None
                if day is None:
                    raise HTTPException(409, "La sesión antigua no identifica la ocurrencia; seleccioná su fecha explícitamente")
                if not any(s.date == day for s in slots) and not (activity.frequency == "Única" and not slots and day == activity.startDate):
                    raise HTTPException(409, "La ocurrencia seleccionada ya no está en la agenda; revisala antes de continuar")
                # A completed session cannot be reassigned through another registration.
                if command.sessionId:
                    learning_doc = db.get(LearningDocument, owner)
                    memory = LearningMemory.model_validate_json(learning_doc.payload) if learning_doc else LearningMemory()
                    old = next((s for s in memory.sessions if s.id == command.sessionId), None)
                    if old and old.occurrenceDate and (old.occurrenceDate != day or old.activityId != activity.id):
                        raise HTTPException(409, "La sesión corresponde a otra ocurrencia")
            result = self.learning.register_progress(owner, command, session=db, occurrence_date=day)
            if activity:
                learning_doc = db.get(LearningDocument, owner)
                memory = LearningMemory.model_validate_json(learning_doc.payload)
                registered = next(s for s in memory.sessions if s.id == result.entityId)
                if registered.activityId != activity.id or (registered.occurrenceDate is not None and registered.occurrenceDate != day):
                    raise HTTPException(409, "El registro confirmado corresponde a otra ocurrencia")
                before = state.model_dump_json()
                if activity.frequency == "Única":
                    activity.status = "Completada"
                state.schedule = [
                    s.model_copy(update={"completed": True})
                    if s.activityId == activity.id and s.date == day
                    else s
                    for s in state.schedule
                ]
                if state.model_dump_json() != before:
                    self.agenda.save(
                        db,
                        owner,
                        doc.revision if doc else 0,
                        state,
                        "study.complete",
                        activity.id,
                    )
            self.agenda.commit(db)
            return result
