import asyncio
from datetime import date, datetime, timedelta, timezone
from uuid import uuid4
import json
import os
import pytest
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
from app.main import create_app
from app.security import SCOPES
from app.models import Activity, State, Slot, PlanRequest
from app.agenda import AgendaService, stable_plan
from app.planner import plan

RESOURCE = 'https://calenflex.test/mcp'
ISSUER = 'https://issuer.test'

@pytest.fixture
def mcp_api(tmp_path, monkeypatch):
    monkeypatch.setenv('OAUTH_ISSUER', ISSUER)
    monkeypatch.setenv('OAUTH_RESOURCE_URL', RESOURCE)
    monkeypatch.setenv('OAUTH_JWKS_URL', ISSUER + '/jwks')
    app = create_app('sqlite:///' + str(tmp_path / 'mcp.db'), token='legacy-test')
    token = app.state.access.issue('mcp-user', SCOPES, 'MCP tests only')
    with TestClient(app) as client:
        client.headers.update({'Authorization': 'Bearer ' + token, 'Accept': 'application/json, text/event-stream'})
        init = rpc(client, 'initialize', {'protocolVersion': '2025-06-18', 'capabilities': {}, 'clientInfo': {'name': 'test', 'version': '1'}})
        assert 'result' in init, init
        yield client, app, token


def rpc(client, method, params, id=1):
    response = client.post('/mcp', json={'jsonrpc': '2.0', 'id': id, 'method': method, 'params': params})
    assert response.status_code == 200, response.text
    return response.json()


def call(client, name, arguments):
    return rpc(client, 'tools/call', {'name': name, 'arguments': arguments})['result']


def structured(result):
    assert not result.get('isError', False), result
    return result['structuredContent']


def test_tool_catalog_is_strict_and_complete(mcp_api):
    client, *_ = mcp_api
    tools = rpc(client, 'tools/list', {})['result']['tools']
    required = {'create_study_session', 'register_study_progress', 'create_task', 'create_study_goal',
        'create_milestone', 'create_recurring_task', 'add_deadline', 'get_schedule', 'get_today_plan',
        'get_pending_tasks', 'get_learning_progress', 'get_goal_details', 'get_recommended_next_steps',
        'propose_reorganization', 'approve_reorganization', 'create_study_plan', 'update_user_preferences'}
    assert required <= {tool['name'] for tool in tools}
    for tool in tools:
        assert tool['inputSchema']['additionalProperties'] is False
        assert tool['outputSchema']['additionalProperties'] is False
        assert tool['description']
    approve = next(t for t in tools if t['name'] == 'approve_reorganization')
    assert approve['annotations']['destructiveHint']
    assert approve['securitySchemes'] == approve['_meta']['securitySchemes']
    assert approve['_meta']['securitySchemes'][0]['scopes'] == ['schedule:approve']


def test_protocol_register_and_query_session_explicitly(mcp_api):
    client, *_ = mcp_api
    goal_id = str(uuid4())
    assert structured(call(client, 'create_study_goal', {'goal': {'id': goal_id, 'title': 'Python'}}))['entityId'] == goal_id
    progress = {'requestId': str(uuid4()), 'goalId': goal_id, 'topic': 'Listas',
        'completedAt': '2026-11-23T12:00:00Z', 'actualMinutes': 30, 'confirmed': True,
        'needsReinforcement': ['Comprensiones'], 'recommendedNextTopic': 'Practicar comprensiones',
        'sourceType': 'chatgpt'}
    first = structured(call(client, 'register_study_progress', {'progress': progress}))
    assert not first['duplicate']
    assert structured(call(client, 'register_study_progress', {'progress': progress}))['duplicate']
    summary = structured(call(client, 'get_learning_progress', {'goalId': goal_id}))
    assert summary['completedMinutes'] == 30 and summary['completedSessions'] == 1
    assert summary['needsReinforcement'] == ['Comprensiones']
    assert len(structured(call(client, 'get_recommended_next_steps', {'goalId': goal_id}))['items']) == 1


def test_mcp_scopes_isolate_users_and_unknown_arguments_are_rejected(mcp_api):
    client, app, token = mcp_api
    goal_id = str(uuid4())
    structured(call(client, 'create_study_goal', {'goal': {'id': goal_id, 'title': 'Privado'}}))
    readonly = app.state.access.issue('other-user', ['memory:read', 'schedule:read'], 'Read only')
    client.headers['Authorization'] = 'Bearer ' + readonly
    assert structured(call(client, 'get_study_goals', {}))['items'] == []
    assert call(client, 'get_goal_details', {'goalId': goal_id})['isError']
    assert call(client, 'create_study_goal', {'goal': {'id': str(uuid4()), 'title': 'No permitido'}})['isError']
    client.headers['Authorization'] = 'Bearer ' + token
    assert call(client, 'get_study_goals', {'owner': 'other-user'})['isError']
    assert call(client, 'approve_reorganization', {'proposalId': str(uuid4()), 'confirmed': 'true'})['isError']


def test_mcp_auth_and_protected_resource_metadata(mcp_api):
    client, app, token = mcp_api
    meta = client.get('/.well-known/oauth-protected-resource/mcp').json()
    assert meta['resource'] == RESOURCE and meta['authorization_servers'] == [ISSUER]
    assert 'schedule:approve' in meta['scopes_supported']
    response = client.post('/mcp', headers={'Authorization': ''}, json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'})
    assert response.status_code == 401
    assert 'resource_metadata=' in response.headers['www-authenticate']
    principal = app.state.access.authenticate('Bearer ' + token)
    app.state.access.revoke(principal)
    assert client.post('/mcp', json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}).status_code == 401


def test_jwt_issuer_audience_expiry_and_scopes(mcp_api, monkeypatch):
    client, app, *_ = mcp_api
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    class Keys:
        def get_signing_key_from_jwt(self, token):
            class SigningKey: pass
            signing = SigningKey(); signing.key = key.public_key(); return signing
    app.state.access.jwt_client = Keys()
    now = datetime.now(timezone.utc)
    claims = {'iss': ISSUER, 'aud': RESOURCE, 'sub': 'user-1', 'iat': now, 'exp': now + timedelta(minutes=5), 'scope': 'memory:read schedule:read'}
    signed = jwt.encode(claims, key, algorithm='RS256')
    client.headers['Authorization'] = 'Bearer ' + signed
    assert structured(call(client, 'get_study_goals', {}))['items'] == []
    for changed in [dict(claims, aud='wrong-resource'), dict(claims, iss='https://untrusted.test'), dict(claims, exp=now - timedelta(minutes=1))]:
        client.headers['Authorization'] = 'Bearer ' + jwt.encode(changed, key, algorithm='RS256')
        assert client.post('/mcp', json={'jsonrpc': '2.0', 'id': 1, 'method': 'tools/list'}).status_code == 401


def test_proposal_confirmation_revisions_and_undo(mcp_api):
    client, app, token = mcp_api
    future = date.today() + timedelta(days=3)
    activity = Activity(title='Sesión', startDate=future)
    structured(call(client, 'create_task', {'task': activity.model_dump(mode='json')}))
    proposal = structured(call(client, 'propose_reorganization', {'start': future.isoformat()}))
    assert app.state.agenda.read('mcp-user')[1].schedule == []
    assert call(client, 'approve_reorganization', {'proposalId': proposal['id'], 'confirmed': False})['isError']
    approved = structured(call(client, 'approve_reorganization', {'proposalId': proposal['id'], 'confirmed': True}))
    assert app.state.agenda.read('mcp-user')[1].schedule
    reverted = app.state.agenda.undo('mcp-user', proposal['id'], True)
    assert reverted.revision == approved['revision'] + 1
    assert not app.state.agenda.read('mcp-user')[1].schedule
    proposal = structured(call(client, 'propose_reorganization', {'start': future.isoformat()}))
    app.state.agenda.create_task('mcp-user', Activity(title='Cambio posterior', startDate=future))
    assert call(client, 'approve_reorganization', {'proposalId': proposal['id'], 'confirmed': True})['isError']


def test_stability_wrapper_keeps_valid_original_slots_and_completion():
    future = date.today() + timedelta(days=2)
    a = Activity(title='Estudio', startDate=future)
    b = Activity(title='Completada', startDate=future, status='Completada')
    original = Slot(activityId=a.id, date=future, start=780, end=840)
    done = Slot(activityId=b.id, date=future, start=540, end=600, completed=True)
    request = PlanRequest(state=State(activities=[a, b], schedule=[original, done]), start=future)
    current = plan(request)
    stable = stable_plan(request)
    assert original in stable.schedule and done in stable.schedule
    assert not stable.conflicts
    assert original not in current.schedule  # original engine is unchanged


def test_remaining_tools_use_same_validated_services_and_autonomy_cannot_be_escalated(mcp_api):
    client, app, *_ = mcp_api
    goal_id, milestone_id, session_id = (str(uuid4()) for _ in range(3))
    structured(call(client, 'create_study_goal', {'goal': {'id': goal_id, 'title': 'Inglés'}}))
    structured(call(client, 'create_milestone', {'milestone': {'id': milestone_id, 'goalId': goal_id, 'title': 'Verbos'}}))
    structured(call(client, 'create_study_session', {'session': {'id': session_id, 'goalId': goal_id, 'status': 'in_progress', 'startedAt': '2026-11-23T13:00:00Z'}}))
    future = date.today() + timedelta(days=3)
    task = Activity(title='Repasar', startDate=future, frequency='Diaria')
    structured(call(client, 'create_recurring_task', {'task': task.model_dump(mode='json')}))
    revision = structured(call(client, 'get_today_plan', {}))['revision']
    structured(call(client, 'add_deadline', {'activityId': str(task.id), 'deadline': (future + timedelta(days=7)).isoformat(), 'agendaRevision': revision, 'confirmed': True}))
    assert len(structured(call(client, 'get_pending_tasks', {}))['items']) == 1
    body = {'requestId': str(uuid4()), 'goalId': goal_id, 'title': 'Estudiar inglés', 'startDate': future.isoformat()}
    first = structured(call(client, 'create_study_plan', {'plan': body}))
    again = structured(call(client, 'create_study_plan', {'plan': body}))
    assert first['revision'] == again['revision']
    assert structured(call(client, 'get_goal_details', {'goalId': goal_id}))['milestones'][0]['id'] == milestone_id
    memory_revision = app.state.learning.read('mcp-user').revision
    structured(call(client, 'update_user_preferences', {'preferences': {'autonomy': 'suggestions'}, 'memoryRevision': memory_revision, 'confirmed': True}))
    assert call(client, 'update_user_preferences', {'preferences': {'autonomy': 'fully_autonomous'}, 'memoryRevision': memory_revision + 1, 'confirmed': True})['isError']
    assert app.state.learning.read('mcp-user').memory.preferences.autonomy == 'suggestions'
    assert structured(call(client, 'get_behavior_insights', {}))['items'] == []


def test_oidc_link_resolves_mobile_owner_and_isolates_other_subject(mcp_api):
    from types import SimpleNamespace
    client,app,_=mcp_api
    private=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    app.state.access.jwt_client=SimpleNamespace(get_signing_key_from_jwt=lambda token: SimpleNamespace(key=private.public_key()))
    def token(subject):
        now=datetime.now(timezone.utc)
        return jwt.encode({'sub':subject,'iss':ISSUER,'aud':RESOURCE,'iat':now,'exp':now+timedelta(minutes=5),'scope':' '.join(SCOPES)},private,algorithm='RS256')
    client.headers['Authorization']='Bearer legacy-test'
    from app.learning_models import LearningGoal
    app.state.learning.create_goal('personal',LearningGoal(title='Memoria del móvil'))
    code=client.post('/api/v1/connections/link/start').json()['code']
    client.headers['Authorization']='Bearer '+token('linked')
    assert client.get('/api/v1/learning').json()['memory']['goals']==[]
    assert client.post('/api/v1/connections/link/claim',json={'code':code,'confirmed':True}).status_code==200
    assert client.get('/api/v1/connections/identity').json()['workspaceId']=='personal'
    assert structured(call(client,'get_study_goals',{}))['items'][0]['title']=='Memoria del móvil'
    client.headers['Authorization']='Bearer '+token('stranger')
    assert structured(call(client,'get_study_goals',{}))['items']==[]


def test_unlink_removes_oidc_access_but_preserves_mobile_data(mcp_api):
    from app.database import AccountLink
    from app.learning_models import LearningGoal
    from sqlalchemy.orm import Session
    client,app,_=mcp_api
    app.state.learning.create_goal('personal',LearningGoal(title='Conservar'))
    with Session(app.state.engine) as db:
        db.add(AccountLink(identity='oauth-id',owner='personal'));db.commit()
    client.headers['Authorization']='Bearer legacy-test'
    assert client.get('/api/v1/connections/links').json()['items']==['oauth-id']
    assert client.post('/api/v1/connections/links/oauth-id/revoke',json={'confirmed':False}).status_code==422
    assert client.post('/api/v1/connections/links/oauth-id/revoke',json={'confirmed':True}).status_code==200
    assert app.state.learning.read('personal').memory.goals[0].title=='Conservar'
    assert client.get('/api/v1/connections/links').json()['items']==[]
