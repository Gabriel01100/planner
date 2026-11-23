from uuid import UUID
from fastapi import APIRouter, Depends
from pydantic import Field, StrictBool
from .learning import LearningService
from .learning_models import ResourceRegistration
from .behavior import insights, InsightsResult
from .learning_models import (Contract, LearningGoal, LearningMilestone, StudySession, SessionRegistration,
                              LearningMemory, MemoryEnvelope, MutationResult, GoalDetails, LearningPreferences)

class MemoryCorrection(Contract):
    revision: int = Field(ge=0)
    memory: LearningMemory
    confirmed: StrictBool

class Confirmation(Contract):
    revision: int = Field(ge=0)
    confirmed: StrictBool


class PreferencesUpdate(Contract):
    revision: int = Field(ge=0)
    preferences: LearningPreferences
    confirmed: StrictBool

def learning_router(service: LearningService, access):
    router = APIRouter(prefix='/api/v1', tags=['Aprendizaje'])
    read = access.owner_dependency('memory:read')
    write = access.owner_dependency('memory:write')

    @router.put('/learning/preferences', response_model=MutationResult)
    def preferences(command: PreferencesUpdate, owner=Depends(access.owner_dependency('preferences:write'))):
        from fastapi import HTTPException
        from uuid import uuid4
        if not command.confirmed: raise HTTPException(422, 'Confirmá las reglas de autonomía')
        def change(memory):
            memory.preferences = command.preferences
            return uuid4(), False
        return service.mutate(owner, 'preferences.update', change, expected=command.revision)

    @router.get('/learning', response_model=MemoryEnvelope)
    def memory(owner=Depends(read)):
        return service.read(owner)

    @router.put('/learning', response_model=MutationResult)
    def correct(command: MemoryCorrection, principal=Depends(access.principal_dependency('memory:write'))):
        if command.memory.preferences != service.read(principal.owner).memory.preferences:
            access.check(principal, 'preferences:write')
        return service.replace(principal.owner, command.memory, command.revision, command.confirmed)

    @router.post('/learning/goals', response_model=MutationResult)
    def create_goal(goal: LearningGoal, owner=Depends(write)):
        return service.create_goal(owner, goal)

    @router.get('/learning/goals/{goal_id}', response_model=GoalDetails)
    def goal_details(goal_id: UUID, owner=Depends(read)):
        return service.details(owner, goal_id)

    @router.post('/learning/goals/{goal_id}/delete', response_model=MutationResult)
    def delete_goal(goal_id: UUID, command: Confirmation, owner=Depends(write)):
        return service.delete_goal(owner, goal_id, command.revision, command.confirmed)

    @router.post('/learning/milestones', response_model=MutationResult)
    def create_milestone(milestone: LearningMilestone, owner=Depends(write)):
        return service.create_milestone(owner, milestone)

    @router.post('/learning/sessions', response_model=MutationResult)
    def create_session(session: StudySession, owner=Depends(write)):
        return service.create_session(owner, session)

    @router.post('/learning/progress', response_model=MutationResult)
    def register_progress(command: SessionRegistration, owner=Depends(write)):
        return service.register_progress(owner, command)

    @router.post('/learning/resources', response_model=MutationResult)
    def resource(command: ResourceRegistration, owner=Depends(write)):
        return service.register_resource(owner, command)

    @router.get('/learning/next-steps')
    def next_steps(goal_id: UUID | None = None, owner=Depends(read)):
        return {'items': service.recommended(owner, goal_id)}

    @router.get('/learning/insights', response_model=InsightsResult)
    def behavior_insights(owner=Depends(read)):
        return insights(service.read(owner).memory)

    @router.post('/connections/revoke')
    def revoke(principal=Depends(access.principal_dependency('memory:read'))):
        access.revoke(principal)
        return {'revoked': True}

    return router
