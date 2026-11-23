"""Application boundary coordinating learning and agenda in one database transaction."""

from zoneinfo import ZoneInfo
from fastapi import HTTPException
from sqlalchemy.orm import Session
from .database import Document
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
            result = self.learning.register_progress(owner, command, session=db)
            if activity:
                day = command.completedAt.astimezone(
                    ZoneInfo(state.preferences.timezone)
                ).date()
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
