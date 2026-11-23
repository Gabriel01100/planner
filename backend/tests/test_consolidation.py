from datetime import date, datetime, timezone
from uuid import uuid4
import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient
from app.main import create_app
from app.models import Activity, State
from app.synchronization import merge_states
from app.learning_models import LearningGoal, SessionRegistration, AssessmentData
from app.security import Principal


def test_merge_independent_fields_additions_and_conflicting_delete():
    a = Activity(title='Original', startDate=date(2027, 1, 4))
    base = State(activities=[a])
    local, remote = base.model_copy(deep=True), base.model_copy(deep=True)
    local.activities[0].title = 'Nuevo título'
    remote.activities[0].description = 'Descripción remota'
    result = merge_states(base, local, remote)
    assert result.activities[0].title == 'Nuevo título'
    assert result.activities[0].description == 'Descripción remota'
    with pytest.raises(HTTPException) as error:
        merge_states(base, State(), remote)
    assert error.value.status_code == 409
    extra = Activity(title='Otra', startDate=date(2027,1,4))
    remote.activities.append(extra)
    assert len(merge_states(base, local, remote).activities) == 2


def test_merge_api_retry_and_isolation(tmp_path):
    app = create_app('sqlite:///'+str(tmp_path/'sync.db'), 'test')
    client = TestClient(app); client.headers['Authorization'] = 'Bearer test'
    state = State(activities=[Activity(title='Local', startDate=date(2027,1,4))])
    command = {'requestId': str(uuid4()), 'revision': 0, 'base': State().model_dump(mode='json'), 'state': state.model_dump(mode='json')}
    first = client.post('/api/v1/sync/merge', json=command)
    assert first.status_code == 200, first.text
    assert client.post('/api/v1/sync/merge', json=command).json() == first.json()
    command['state']['activities'][0]['title'] = 'Diferente'
    assert client.post('/api/v1/sync/merge', json=command).status_code == 409
    token = app.state.access.issue('other', ['schedule:read', 'schedule:write'], 'test')
    client.headers['Authorization'] = 'Bearer '+token
    assert client.get('/api/sync').json()['state']['activities'] == []


def test_assessment_requires_results_and_is_idempotent(tmp_path):
    app = create_app('sqlite:///'+str(tmp_path/'memory.db'), 'test')
    service = app.state.learning
    goal = LearningGoal(title='Python')
    service.create_goal('personal', goal)
    command = SessionRegistration(requestId=uuid4(), goalId=goal.id, topic='Funciones',
        completedAt=datetime.now(timezone.utc), confirmed=True, assessment=AssessmentData(correctCount=3,totalCount=4,method='Cuatro ejercicios corregidos',confidence=.6))
    first = service.register_progress('personal', command)
    second = service.register_progress('personal', command)
    memory = service.read('personal').memory
    assert first.entityId == second.entityId and second.duplicate
    assert len(memory.competencies) == 1
    assert memory.competencies[0].estimatedLevel == .75
    assert memory.competencies[0].confidence == .6
    assert len(memory.competencies[0].evidenceIds) == 1
    with pytest.raises(ValueError): AssessmentData(correctCount=5,totalCount=4,method='test',confidence=.5)


def test_link_double_possession_consumption_and_existing_data(tmp_path):
    app = create_app('sqlite:///'+str(tmp_path/'link.db'), 'test')
    client = TestClient(app)
    scopes = frozenset(['memory:read','memory:write','schedule:write'])
    current = [Principal('personal', scopes, 'mobile', 'legacy')]
    app.state.access.authenticate = lambda authorization: current[0]
    code = client.post('/api/v1/connections/link/start').json()['code']
    # Administrative token cannot masquerade as OAuth.
    assert client.post('/api/v1/connections/link/claim',json={'code':code,'confirmed':True}).status_code == 403
    current[0] = Principal('oauth-user', scopes, 'jwt', 'oidc', 'oauth-user')
    assert client.post('/api/v1/connections/link/claim',json={'code':code,'confirmed':False}).status_code == 403
    assert client.post('/api/v1/connections/link/claim',json={'code':code,'confirmed':True}).json()['workspaceId'] == 'personal'
    assert client.post('/api/v1/connections/link/claim',json={'code':code,'confirmed':True}).status_code == 409
    current[0] = Principal('personal', scopes, 'mobile', 'legacy')
    code = client.post('/api/v1/connections/link/start').json()['code']
    app.state.learning.create_goal('populated', LearningGoal(title='No reemplazar'))
    current[0] = Principal('populated',scopes,'jwt2','oidc','populated')
    assert client.post('/api/v1/connections/link/claim',json={'code':code,'confirmed':True}).status_code == 409
