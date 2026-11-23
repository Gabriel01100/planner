import hashlib
import hmac
import json
import os
import threading
import time
from collections import defaultdict, deque
from contextlib import asynccontextmanager

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from sqlalchemy import Column, Integer, String, Text, create_engine, select, update
from sqlalchemy.orm import DeclarativeBase, Session

from .models import AIRequest, AIResponse, PlanRequest, State, SyncRequest
from .planner import plan, validate
from .providers import OpenAICompatibleProvider

from .database import Document, make_engine, migrate
from .security import Access
from .learning import LearningService
from .learning_api import learning_router
from .agenda import AgendaService
from .agent import agent_router
from .planning_api import planning_router

def create_app(database_url=None, token=None):
    engine = make_engine(database_url or os.getenv('DATABASE_URL', 'sqlite:///./calenflex.db'))
    migrate(engine)
    api_token = token if token is not None else os.getenv('CALENFLEX_TOKEN', '')
    calls = defaultdict(deque)
    lock = threading.Lock()

    mcp_server = None

    @asynccontextmanager
    async def lifespan(app):
        if mcp_server is not None:
            async with mcp_server.session_manager.run():
                yield
        else:
            yield
        engine.dispose()

    app = FastAPI(title='CalenFleX', version='1.1.0', lifespan=lifespan)

    @app.middleware('http')
    async def body_limit(request: Request, call_next):
        # Enforce streamed size too; do not trust Content-Length.
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 5_000_000:
                return JSONResponse(status_code=413, content={'detail': 'Archivo demasiado grande'})
        request._body = bytes(body)
        return await call_next(request)

    access = Access(engine, api_token)
    authorize = access.owner_dependency('schedule:write')
    read_agenda = access.owner_dependency('schedule:read')
    service = LearningService(engine)
    app.state.access = access
    app.state.learning = service
    app.state.engine = engine
    app.state.agenda = AgendaService(engine)
    app.include_router(learning_router(service, access))
    app.include_router(agent_router(service, access))
    app.include_router(planning_router(app.state.agenda, access))

    @app.get('/health')
    def health():
        return {'status': 'ok', 'aiAvailable': bool(os.getenv('AI_API_KEY'))}

    @app.post('/api/plan', dependencies=[Depends(authorize)])
    @app.post('/api/reorganize', dependencies=[Depends(authorize)])
    def generate(req: PlanRequest):
        try:
            return plan(req)
        except ValueError as exc:
            raise HTTPException(422, str(exc))

    @app.post('/api/validate', dependencies=[Depends(authorize)])
    def check(state: State):
        conflicts = validate(state, state.schedule)
        return {'valid': not conflicts, 'conflicts': conflicts}

    @app.post('/api/import/validate', dependencies=[Depends(authorize)])
    def import_validate(state: State):
        return state

    @app.get('/api/sync')
    def download(owner=Depends(read_agenda)):
        with Session(engine) as db:
            doc = db.get(Document, owner)
            return {'revision': doc.revision if doc else 0, 'state': json.loads(doc.payload) if doc else State().model_dump(mode='json')}

    @app.put('/api/sync')
    def upload(req: SyncRequest, owner=Depends(authorize)):
        with lock, Session(engine) as db:
            doc = db.get(Document, owner)
            revision = doc.revision if doc else 0
            if revision != req.revision:
                raise HTTPException(409, 'Hay cambios remotos. Exportá una copia y elegí qué versión conservar.')
            if doc:
                previous = State.model_validate_json(doc.payload)
                old_by_id = {activity.id: activity for activity in previous.activities}
                for activity in req.state.activities:
                    if 'goalId' not in activity.model_fields_set and activity.id in old_by_id:
                        activity.goalId = old_by_id[activity.id].goalId
            payload = req.state.model_dump_json()
            if doc is None:
                db.add(Document(owner=owner, revision=1, payload=payload))
            else:
                changed = db.execute(update(Document).where(Document.owner == owner, Document.revision == req.revision)
                                     .values(revision=req.revision + 1, payload=payload)).rowcount
                if changed != 1:
                    db.rollback()
                    raise HTTPException(409, 'La versión remota cambió')
            try:
                db.commit()
            except Exception:
                db.rollback()
                # A concurrent first insert is a revision conflict, never an overwrite.
                if db.get(Document, owner):
                    raise HTTPException(409, 'La versión remota cambió')
                raise
            return {'revision': req.revision + 1, 'state': req.state.model_dump(mode='json')}

    @app.post('/api/ai', response_model=AIResponse)
    async def assistant(req: AIRequest, owner=Depends(authorize)):
        key = os.getenv('AI_API_KEY', '')
        if not key:
            raise HTTPException(503, 'La IA no está configurada. El resto de CalenFleX sigue disponible.')
        access.consume_ai(owner)
        try:
            provider = OpenAICompatibleProvider(key, os.getenv('AI_BASE_URL', 'https://api.openai.com/v1'), os.getenv('AI_MODEL', 'gpt-4.1-mini'))
            return await provider.complete(req)
        except (httpx.HTTPError, ValueError, KeyError, IndexError):
            raise HTTPException(502, 'El proveedor de IA no respondió con datos válidos. Intentá nuevamente.')

    issuer = os.getenv('OAUTH_ISSUER', '').rstrip('/')
    resource = os.getenv('OAUTH_RESOURCE_URL', '').rstrip('/')
    jwks = os.getenv('OAUTH_JWKS_URL', '')
    if issuer and resource and jwks:
        if not all(value.startswith('https://') for value in [issuer, resource, jwks]):
            raise RuntimeError('OAuth y MCP requieren URLs HTTPS reales')
        from .mcp_server import create_mcp
        mcp_server = create_mcp(access, service, app.state.agenda, resource, issuer)
        @app.get('/.well-known/oauth-protected-resource/mcp', include_in_schema=False)
        def oauth_metadata():
            from .security import SCOPES
            return {'resource': resource, 'authorization_servers': [issuer],
                    'scopes_supported': sorted(SCOPES), 'bearer_methods_supported': ['header']}
        app.mount('/', mcp_server.streamable_http_app())
    else:
        @app.api_route('/mcp', methods=['GET', 'POST', 'DELETE'], include_in_schema=False)
        def unconfigured_mcp():
            raise HTTPException(503, 'MCP requiere configurar OAuth, un servidor HTTPS y su audiencia')

    return app

app = create_app()
