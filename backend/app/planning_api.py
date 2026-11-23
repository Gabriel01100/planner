from datetime import date
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException
from pydantic import Field, StrictBool
from .learning_models import Contract
from .models import PlanRequest, PlanResult
from .agenda import stable_plan, PlanProposal, AgendaResult

class ProposalRequest(Contract):
    start: date
    days: int = Field(default=7, ge=1, le=31)

class Approval(Contract):
    confirmed: StrictBool


def planning_router(agenda, access):
    router = APIRouter(prefix='/api/v1/planning', tags=['Planificación revisable'])
    write = access.owner_dependency('schedule:write')
    approve = access.owner_dependency('schedule:approve')

    @router.post('/automatic')
    def automatic(principal=Depends(access.principal_dependency('schedule:approve'))):
        access.check(principal, 'schedule:write')
        access.check(principal, 'schedule:read')
        access.check(principal, 'memory:read')
        from .autonomy import automatic_plan
        return automatic_plan(agenda, principal.owner)

    @router.post('/preview', response_model=PlanResult)
    def preview(request: PlanRequest, owner=Depends(write)):
        try:
            return stable_plan(request)
        except ValueError as error:
            raise HTTPException(422, str(error))

    @router.post('/proposals', response_model=PlanProposal)
    def propose(request: ProposalRequest, principal=Depends(access.principal_dependency('schedule:write'))):
        access.check(principal, 'schedule:read')
        return agenda.propose(principal.owner, request.start, request.days)

    @router.post('/proposals/{proposal_id}/approve', response_model=AgendaResult)
    def apply(proposal_id: UUID, request: Approval, owner=Depends(approve)):
        return agenda.approve(owner, proposal_id, request.confirmed)

    @router.post('/proposals/{proposal_id}/undo', response_model=AgendaResult)
    def undo(proposal_id: UUID, request: Approval, owner=Depends(approve)):
        return agenda.undo(owner, proposal_id, request.confirmed)

    return router
